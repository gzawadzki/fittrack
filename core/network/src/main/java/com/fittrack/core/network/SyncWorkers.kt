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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

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

/** Terminal outcome of a manual "sync now" trigger, for UI feedback. */
enum class SyncTriggerOutcome { SUCCEEDED, FAILED }

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

    /** Call immediately after a workout ends, or when the user explicitly asks to sync now.
     * REPLACE (not KEEP): a manual "sync now" tap should always get a fresh attempt rather
     * than silently no-op against an already-enqueued request that's sitting in exponential
     * backoff from an earlier failure - KEEP made that button do nothing for up to an hour
     * after a few failed attempts. Network constraint keeps offline logging non-blocking. */
    fun enqueueImmediateSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_SYNC, ExistingWorkPolicy.REPLACE, request)
    }

    /** Same as [enqueueImmediateSync], but suspends until the request reaches a
     * terminal state so the UI (Settings' "Synchronizuj teraz" button) can show
     * success/failure feedback instead of leaving the tap looking like a no-op.
     * Observes the request by its own id rather than the [IMMEDIATE_SYNC] unique
     * work name - REPLACE briefly surfaces the *previous* request's CANCELLED
     * state on that name, which would otherwise be misread as this attempt failing. */
    suspend fun enqueueImmediateSyncAndAwait(context: Context): SyncTriggerOutcome {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(IMMEDIATE_SYNC, ExistingWorkPolicy.REPLACE, request)
        val terminalState = workManager.getWorkInfoByIdFlow(request.id)
            .mapNotNull { it?.state }
            .first { it == WorkInfo.State.SUCCEEDED || it == WorkInfo.State.FAILED || it == WorkInfo.State.CANCELLED }
        return if (terminalState == WorkInfo.State.SUCCEEDED) SyncTriggerOutcome.SUCCEEDED else SyncTriggerOutcome.FAILED
    }
}
