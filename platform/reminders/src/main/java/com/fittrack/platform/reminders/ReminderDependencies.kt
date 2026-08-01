package com.fittrack.platform.reminders

/** Set from application DI. A missing provider never produces a false missed-workout notification. */
object ReminderDependencies {
    @Volatile var workoutStartStateProvider: WorkoutStartStateProvider? = null
}
