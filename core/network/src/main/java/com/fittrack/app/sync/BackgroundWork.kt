package com.fittrack.app.sync

import android.content.Context
import com.fittrack.core.network.NetworkWorkScheduler

/** Legacy app facade; app DI should install NetworkWorkDependencies before calling this. */
object BackgroundWork {
    fun schedule(context: Context) = NetworkWorkScheduler.schedule(context)
    fun syncAfterWorkout(context: Context) = NetworkWorkScheduler.enqueueImmediateSync(context)
}
