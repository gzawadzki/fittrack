package com.fittrack.app.di

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fittrack.app.FitTrackApplication
import com.fittrack.core.network.FitbitRemoteSyncStatus
import com.fittrack.core.network.FitbitSyncMode
import com.fittrack.core.network.FitbitSyncRequest
import com.fittrack.core.network.FitbitSyncResult
import com.fittrack.core.network.SyncWorker
import java.util.concurrent.TimeUnit

object PostWorkoutSyncScheduler {
    internal const val INPUT_WORKOUT_ID = "workout_id"

    fun enqueue(context: Context, workoutId: String) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val upload = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        val fitbit = OneTimeWorkRequestBuilder<PostWorkoutFitbitWorker>()
            .setInputData(workDataOf(INPUT_WORKOUT_ID to workoutId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).beginUniqueWork(
            "fittrack.post-workout.$workoutId",
            ExistingWorkPolicy.KEEP,
            upload,
        ).then(fitbit).enqueue()
    }
}

class PostWorkoutFitbitWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val workoutId = inputData.getString(PostWorkoutSyncScheduler.INPUT_WORKOUT_ID) ?: return Result.failure()
        val gateway = (applicationContext as FitTrackApplication).container.fitbitGateway
        return when (val result = gateway.sync(FitbitSyncRequest(FitbitSyncMode.WORKOUT_HR, workoutId))) {
            is FitbitSyncResult.Failure -> if (result.error.retryable || result.error.code == "AUTH_REQUIRED") {
                Result.retry()
            } else {
                Result.failure()
            }
            is FitbitSyncResult.Success -> when (result.status) {
                FitbitRemoteSyncStatus.SYNCED -> Result.success()
                FitbitRemoteSyncStatus.PENDING, FitbitRemoteSyncStatus.PARTIAL -> Result.retry()
                FitbitRemoteSyncStatus.FAILED -> if (result.retryAfterSeconds != null) Result.retry() else Result.failure()
            }
        }
    }
}
