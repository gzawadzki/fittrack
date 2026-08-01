package com.fittrack.platform.reminders

import java.time.DayOfWeek
import java.time.Instant

/** Civil-time schedule. Recalculating from this keeps reminders correct after timezone changes. */
data class WorkoutReminderPlan(
    val planId: String,
    val title: String,
    val dayOfWeek: DayOfWeek,
    val minuteOfDay: Int,
) {
    init {
        require(planId.isNotBlank())
        require(minuteOfDay in 0 until 24 * 60)
    }
}

data class ScheduledReminder(
    val planId: String,
    val title: String,
    val workoutStartsAt: Instant,
    val kind: ReminderKind,
)

enum class ReminderKind { PRE_WORKOUT, MISSED_FOLLOW_UP }

interface WorkoutStartStateProvider {
    /** True if a session for [planId] began at or after the scheduled occurrence. */
    suspend fun hasWorkoutStarted(planId: String, scheduledAt: Instant): Boolean
}
