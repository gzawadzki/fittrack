package com.fittrack.platform.reminders

import android.content.Context

/**
 * Minimal scaffold so app/ compiles against a stable reminders API.
 * Owner: Agent M4 (core/network, platform/reminders).
 * Replace with WorkManager-based scheduling per IMPLEMENTATION_PLAN.md section 3 (M4):
 * default -60 min reminder, +120 min missed follow-up, "snooze 15 min" / "reschedule" actions,
 * boot/timezone-change resilience, Android 13+ notification permission handling.
 */
object ReminderScheduler {

    fun createChannel(context: Context) {
        // TODO(M4): create the workout reminder notification channel.
    }

    fun rescheduleAll(context: Context) {
        // TODO(M4): reschedule all pending reminders from persisted state (core/database).
    }
}
