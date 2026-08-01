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
import com.fittrack.core.model.FitbitSyncStatus
import com.fittrack.core.network.ExerciseTimeRange
import com.fittrack.core.network.SyncResult
import com.fittrack.core.network.SyncWorker
import com.fittrack.core.network.toFitbitDataPayload
import java.time.Instant
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
 * Reads the just-finished workout's heart-rate window from on-device Health
 * Connect, persists the normalized result locally, and pushes the computed
 * per-exercise aggregates to Supabase. Replaces the old
 * `PostWorkoutFitbitWorker`, which called the now-deleted server-side Fitbit
 * `WORKOUT_HR` sync mode - see docs/adr/0001-fitbit-health-connect-pivot.md.
 */
class PostWorkoutHealthConnectWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val workoutId = inputData.getString(PostWorkoutSyncScheduler.INPUT_WORKOUT_ID) ?: return Result.failure()
        val container = (applicationContext as FitTrackApplication).container

        val workout = container.workoutRepository.getWorkout(workoutId) ?: return Result.failure()
        val endedAt = workout.endedAt ?: return Result.retry()

        val exerciseRanges = container.workoutRepository.getExerciseSessions(workoutId).mapNotNull { session ->
            val startedAt = session.startedAt ?: return@mapNotNull null
            val sessionEndedAt = session.endedAt ?: return@mapNotNull null
            ExerciseTimeRange(exerciseId = session.id, startAt = startedAt, endAt = sessionEndedAt)
        }

        return try {
            val readResult = container.healthConnectGateway.readWorkoutHeartRate(
                workoutStartedAt = workout.startedAt,
                workoutEndedAt = endedAt,
                exercises = exerciseRanges,
            )
            val syncedAt = Instant.now()
            container.fitbitDataRepository.persist(
                readResult.toFitbitDataPayload(
                    workoutSessionId = workoutId,
                    workoutSyncStatus = FitbitSyncStatus.SYNCED,
                    syncedAt = syncedAt,
                ),
            )
            when (container.healthConnectSyncGateway.pushWorkoutAggregates(workoutId, readResult.aggregates)) {
                is SyncResult.Success -> Result.success()
                is SyncResult.Failure -> Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
