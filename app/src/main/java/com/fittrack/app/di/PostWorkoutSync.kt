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
        val healthConnect = OneTimeWorkRequestBuilder<PostWorkoutHealthConnectWorker>()
            .setInputData(workDataOf(INPUT_WORKOUT_ID to workoutId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).beginUniqueWork(
            "fittrack.post-workout.$workoutId",
            ExistingWorkPolicy.KEEP,
            upload,
        ).then(healthConnect).enqueue()
    }
}

/**
 * Thin WorkManager adapter around [PostWorkoutSyncPipeline] (architecture
 * review candidate 2): resolves the pipeline's collaborators from
 * [PostWorkoutSyncDependencies] and maps its [PostWorkoutSyncOutcome] to a
 * `Result`. Replaces the old `PostWorkoutFitbitWorker`, which called the
 * now-deleted server-side Fitbit `WORKOUT_HR` sync mode - see
 * docs/adr/0001-fitbit-health-connect-pivot.md.
 */
class PostWorkoutHealthConnectWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val workoutId = inputData.getString(PostWorkoutSyncScheduler.INPUT_WORKOUT_ID) ?: return Result.failure()

        val workoutRepository = PostWorkoutSyncDependencies.workoutRepository ?: return Result.retry()
        val healthConnectGateway = PostWorkoutSyncDependencies.healthConnectGateway ?: return Result.retry()
        val fitbitDataRepository = PostWorkoutSyncDependencies.fitbitDataRepository ?: return Result.retry()
        val healthConnectSyncGateway = PostWorkoutSyncDependencies.healthConnectSyncGateway ?: return Result.retry()

        val pipeline = PostWorkoutSyncPipeline(
            workoutRepository = workoutRepository,
            healthConnectGateway = healthConnectGateway,
            fitbitDataRepository = fitbitDataRepository,
            healthConnectSyncGateway = healthConnectSyncGateway,
        )

        return when (pipeline.sync(workoutId)) {
            PostWorkoutSyncOutcome.SYNCED -> Result.success()
            PostWorkoutSyncOutcome.RETRY -> Result.retry()
            PostWorkoutSyncOutcome.PERMANENT_FAILURE -> Result.failure()
        }
    }
}
