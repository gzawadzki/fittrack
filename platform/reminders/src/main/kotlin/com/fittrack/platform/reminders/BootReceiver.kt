package com.fittrack.platform.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Minimal scaffold so the manifest-declared receiver resolves and app/ compiles.
 * Owner: Agent M4 (core/network, platform/reminders).
 * Reschedules reminders on boot / timezone / clock change per
 * IMPLEMENTATION_PLAN.md section 3 (M4).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.rescheduleAll(context)
    }
}
