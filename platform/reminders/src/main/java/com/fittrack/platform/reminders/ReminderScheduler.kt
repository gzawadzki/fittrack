package com.fittrack.platform.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    const val CHANNEL_ID = "scheduled_workouts"
    internal const val INPUT_PLAN_ID = "plan_id"
    internal const val INPUT_TITLE = "title"
    internal const val INPUT_SCHEDULED_AT = "scheduled_at"
    internal const val INPUT_KIND = "kind"
    private const val COORDINATOR_WORK = "fittrack.reminder.coordinator"
    private const val PLAN_TAG_PREFIX = "fittrack.reminder.plan."

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Przypomnienia o treningu", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Planowane treningi i propozycje przełożenia"
        })
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun updatePlans(context: Context, plans: List<WorkoutReminderPlan>) {
        val store = ReminderStore(context)
        val previous = store.read().associateBy(WorkoutReminderPlan::planId)
        val current = plans.associateBy(WorkoutReminderPlan::planId)
        previous.values
            .filter { old -> current[old.planId] != old }
            .forEach { cancelPlan(context, it.planId) }
        store.write(plans)
        reschedulePersisted(context)
    }

    fun reschedulePersisted(context: Context, clock: Clock = Clock.systemDefaultZone()) {
        createChannel(context)
        ensureCoordinator(context)
        val workManager = WorkManager.getInstance(context)
        ReminderStore(context).read().forEach { plan ->
            val now = clock.instant()
            val nextStart = nextOccurrence(plan, clock)
            schedule(workManager, plan, nextStart, ReminderKind.PRE_WORKOUT, now)
            schedule(workManager, plan, nextStart, ReminderKind.MISSED_FOLLOW_UP, now)

            // A reboot or timezone change between the session start and +120 min must retain the follow-up.
            val latestStart = previousOccurrence(plan, clock)
            if (!now.isBefore(latestStart) && now.isBefore(latestStart.plus(Duration.ofMinutes(120)))) {
                schedule(workManager, plan, latestStart, ReminderKind.MISSED_FOLLOW_UP, now)
            }
        }
    }

    fun snooze(context: Context, planId: String, title: String, scheduledAt: Instant, minutes: Long = 15) {
        val request = OneTimeWorkRequestBuilder<WorkoutReminderWorker>()
            .setInputData(workDataOf(
                INPUT_PLAN_ID to planId, INPUT_TITLE to title, INPUT_SCHEDULED_AT to scheduledAt.toString(), INPUT_KIND to ReminderKind.PRE_WORKOUT.name,
            ))
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("fittrack.reminder.snooze.$planId", ExistingWorkPolicy.REPLACE, request)
    }

    private fun ensureCoordinator(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderCoordinatorWorker>(24, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            COORDINATOR_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun cancelPlan(context: Context, planId: String) {
        WorkManager.getInstance(context).apply {
            cancelAllWorkByTag(planTag(planId))
            cancelUniqueWork("fittrack.reminder.snooze.$planId")
        }
    }

    private fun schedule(
        workManager: WorkManager,
        plan: WorkoutReminderPlan,
        startsAt: Instant,
        kind: ReminderKind,
        now: Instant,
    ) {
        val triggerAt = when (kind) {
            ReminderKind.PRE_WORKOUT -> startsAt.minus(Duration.ofMinutes(60))
            ReminderKind.MISSED_FOLLOW_UP -> startsAt.plus(Duration.ofMinutes(120))
        }
        val workName = "fittrack.reminder.${kind.name}.${plan.planId}"
        if (!triggerAt.isAfter(now)) {
            workManager.cancelUniqueWork(workName)
            return
        }
        val delay = Duration.between(now, triggerAt)
        val request = OneTimeWorkRequestBuilder<WorkoutReminderWorker>()
            .setInputData(workDataOf(
                INPUT_PLAN_ID to plan.planId, INPUT_TITLE to plan.title, INPUT_SCHEDULED_AT to startsAt.toString(), INPUT_KIND to kind.name,
            ))
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .addTag(planTag(plan.planId))
            .build()
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
    }

    internal fun nextOccurrence(plan: WorkoutReminderPlan, clock: Clock): Instant {
        val now = ZonedDateTime.now(clock)
        var candidate = now.withHour(plan.minuteOfDay / 60).withMinute(plan.minuteOfDay % 60).withSecond(0).withNano(0)
        val delta = (plan.dayOfWeek.value - candidate.dayOfWeek.value + 7) % 7
        candidate = candidate.plusDays(delta.toLong())
        if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1)
        return candidate.toInstant()
    }

    internal fun previousOccurrence(plan: WorkoutReminderPlan, clock: Clock): Instant {
        val now = ZonedDateTime.now(clock)
        var candidate = now.withHour(plan.minuteOfDay / 60).withMinute(plan.minuteOfDay % 60).withSecond(0).withNano(0)
        val delta = (candidate.dayOfWeek.value - plan.dayOfWeek.value + 7) % 7
        candidate = candidate.minusDays(delta.toLong())
        if (candidate.isAfter(now)) candidate = candidate.minusWeeks(1)
        return candidate.toInstant()
    }

    private fun planTag(planId: String) = "$PLAN_TAG_PREFIX$planId"
}
