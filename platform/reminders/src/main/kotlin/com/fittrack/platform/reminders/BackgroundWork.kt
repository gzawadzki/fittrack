package com.fittrack.platform.reminders

import android.content.Context

/**
 * Minimal scaffold so app/ compiles against a stable background-work API.
 * Owner: Agent M4 (core/network, platform/reminders).
 * Replace with periodic sync + reminder WorkManager jobs, immediate post-workout sync,
 * and 90-day HR cleanup per IMPLEMENTATION_PLAN.md section 3 (M4).
 */
object BackgroundWork {

    fun schedule(context: Context) {
        // TODO(M4): enqueue periodic sync and reminder WorkManager jobs.
    }
}
