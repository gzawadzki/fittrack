package com.fittrack.platform.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant

/** Manifest receiver: exported=false. Start/reschedule open the app; snooze remains local. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val planId = intent.getStringExtra(EXTRA_PLAN_ID) ?: return
        when (intent.action) {
            ACTION_SNOOZE -> {
                val scheduledAt = runCatching { Instant.parse(intent.getStringExtra(EXTRA_SCHEDULED_AT)) }.getOrNull() ?: return
                ReminderScheduler.snooze(context, planId, intent.getStringExtra(EXTRA_TITLE).orEmpty(), scheduledAt)
            }
            ACTION_START, ACTION_RESCHEDULE -> context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.putExtra(EXTRA_PLAN_ID, planId)
                ?.putExtra(EXTRA_REMINDER_ACTION, intent.action)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ?.let(context::startActivity)
        }
    }
}
