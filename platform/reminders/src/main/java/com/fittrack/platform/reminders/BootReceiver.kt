package com.fittrack.platform.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Rebuilds one-off WorkManager reminders from civil-time plans after boot or a clock/zone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                ReminderScheduler.reschedulePersisted(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
