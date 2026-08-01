package com.fittrack.core.network

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Installed by app DI before a [SyncWorker] can execute. */
object NetworkWorkDependencies {
    @Volatile var syncOrchestrator: SyncOrchestrator? = null
}

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val orchestrator = NetworkWorkDependencies.syncOrchestrator ?: return Result.retry()
        return when (val result = orchestrator.syncOnce()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.Failure -> when {
                result.error.code == "NO_SESSION" -> Result.success()
                result.error.retryable -> Result.retry()
                else -> Result.failure()
            }
        }
    }
}

class HeartRateRetentionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val orchestrator = NetworkWorkDependencies.syncOrchestrator ?: return Result.retry()
        orchestrator.cleanupExpiredHeartRate()
        return Result.success()
    }
}

object NetworkWorkScheduler {
    private const val PERIODIC_SYNC = "fittrack.periodic.sync"
    private const val IMMEDIATE_SYNC = "fittrack.immediate.sync"
    private const val HR_RETENTION = "fittrack.heart-rate-retention"

    fun schedule(context: Context) {
        val constraints = androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodicSync = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        val cleanup = PeriodicWorkRequestBuilder<HeartRateRetentionWorker>(24, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_SYNC, ExistingPeriodicWorkPolicy.KEEP, periodicSync)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(HR_RETENTION, ExistingPeriodicWorkPolicy.KEEP, cleanup)
    }

    /** Call immediately after a workout ends. Network constraint keeps offline logging non-blocking. */
    fun enqueueImmediateSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_SYNC, ExistingWorkPolicy.KEEP, request)
    }
}
