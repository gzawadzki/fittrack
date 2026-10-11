package com.fittrack.platform.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant

class WorkoutReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!ReminderScheduler.hasNotificationPermission(applicationContext)) return Result.success()
        val planId = inputData.getString(ReminderScheduler.INPUT_PLAN_ID) ?: return Result.failure()
        val title = inputData.getString(ReminderScheduler.INPUT_TITLE).orEmpty().ifBlank { "Trening" }
        val scheduledAt = runCatching { Instant.parse(inputData.getString(ReminderScheduler.INPUT_SCHEDULED_AT)) }.getOrElse { return Result.failure() }
        val kind = runCatching { ReminderKind.valueOf(inputData.getString(ReminderScheduler.INPUT_KIND).orEmpty()) }.getOrElse { return Result.failure() }
        if (kind == ReminderKind.MISSED_FOLLOW_UP) {
            val provider = ReminderDependencies.workoutStartStateProvider ?: return Result.retry()
            if (provider.hasWorkoutStarted(planId, scheduledAt)) return Result.success()
        }
        notify(planId, title, scheduledAt, kind)
        return Result.success()
    }

    private fun notify(planId: String, title: String, scheduledAt: Instant, kind: ReminderKind) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val startIntent = activityIntent(planId, ACTION_START)
        val snoozeIntent = actionIntent(ACTION_SNOOZE, planId, title, scheduledAt)
        val rescheduleIntent = activityIntent(planId, ACTION_RESCHEDULE)
        val content = if (kind == ReminderKind.PRE_WORKOUT) "Za godzinę: $title" else "Trening „$title” nie został rozpoczęty. Przełożyć?"
        val notification = NotificationCompat.Builder(applicationContext, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(if (kind == ReminderKind.PRE_WORKOUT) "Czas przygotować trening" else "Trening do przełożenia")
            .setContentText(content)
            .setContentIntent(startIntent)
            .setAutoCancel(true)
            .addAction(0, "Rozpocznij", startIntent)
            .addAction(0, "Odłóż 15 min", snoozeIntent)
            .addAction(0, "Przełóż", rescheduleIntent)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(notificationId(planId, kind), notification)
    }

    private fun activityIntent(planId: String, action: String): PendingIntent {
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?: Intent()
        intent.setPackage(applicationContext.packageName)
            .putExtra(EXTRA_PLAN_ID, planId)
            .putExtra(EXTRA_REMINDER_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(applicationContext, (planId + action).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(action: String, planId: String, title: String, scheduledAt: Instant): PendingIntent {
        val intent = Intent(applicationContext, ReminderActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_PLAN_ID, planId)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SCHEDULED_AT, scheduledAt.toString())
        return PendingIntent.getBroadcast(applicationContext, (planId + action).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notificationId(planId: String, kind: ReminderKind) = 31_000 + (planId + kind.name).hashCode().and(0x3fff)
}

/**
 * Recomputes one-time work from civil-time plans every day. The actual
 * notifications remain one-time jobs, so timezone and DST changes never
 * accumulate the drift of a seven-day periodic request.
 */
class ReminderCoordinatorWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        ReminderScheduler.reschedulePersisted(applicationContext)
        return Result.success()
    }
}

const val ACTION_START = "com.fittrack.app.reminders.START"
const val ACTION_SNOOZE = "com.fittrack.app.reminders.SNOOZE"
const val ACTION_RESCHEDULE = "com.fittrack.app.reminders.RESCHEDULE"
const val EXTRA_PLAN_ID = "plan_id"
const val EXTRA_TITLE = "title"
const val EXTRA_SCHEDULED_AT = "scheduled_at"
const val EXTRA_REMINDER_ACTION = "reminder_action"
