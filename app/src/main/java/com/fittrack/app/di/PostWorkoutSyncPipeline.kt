package com.fittrack.app.di

import com.fittrack.core.model.FitbitSyncStatus
import com.fittrack.core.model.repository.FitbitDataRepository
import com.fittrack.core.model.repository.WorkoutRepository
import com.fittrack.core.network.ExerciseTimeRange
import com.fittrack.core.network.HealthConnectGateway
import com.fittrack.core.network.HealthConnectReadOutcome
import com.fittrack.core.network.HealthConnectSyncGateway
import com.fittrack.core.network.SyncResult
import com.fittrack.core.network.toFitbitDataPayload
import java.time.Instant

/** Installed by [AppContainer.start] before [PostWorkoutHealthConnectWorker]
 * can run — same pattern as `NetworkWorkDependencies` in core/network, needed
 * because WorkManager instantiates workers through its default factory, so a
 * worker's constructor can't take these directly. Any field still `null`
 * means the container hasn't finished starting yet; the worker treats that
 * as transient and retries. */
object PostWorkoutSyncDependencies {
    @Volatile var workoutRepository: WorkoutRepository? = null
    @Volatile var healthConnectGateway: HealthConnectGateway? = null
    @Volatile var fitbitDataRepository: FitbitDataRepository? = null
    @Volatile var healthConnectSyncGateway: HealthConnectSyncGateway? = null
}

enum class PostWorkoutSyncOutcome { SYNCED, RETRY, PERMANENT_FAILURE }

/**
 * Owns the entire post-workout Health Connect flow: permission guard, fetch
 * the workout, read its heart-rate window, persist locally, push to
 * Supabase. Extracted from [PostWorkoutHealthConnectWorker] (architecture
 * review candidate 2) so the whole chain is testable as plain Kotlin,
 * without WorkManager or a real `FitTrackApplication` — the worker itself is
 * now a thin adapter that resolves [PostWorkoutSyncDependencies] and maps
 * [PostWorkoutSyncOutcome] to a WorkManager `Result`.
 */
class PostWorkoutSyncPipeline(
    private val workoutRepository: WorkoutRepository,
    private val healthConnectGateway: HealthConnectGateway,
    private val fitbitDataRepository: FitbitDataRepository,
    private val healthConnectSyncGateway: HealthConnectSyncGateway,
) {
    suspend fun sync(workoutId: String): PostWorkoutSyncOutcome {
        // Health Connect being unavailable or not permitted is a permanent
        // condition until the user acts in Settings, not a transient one —
        // retrying would just waste battery.
        val permitted = try {
            healthConnectGateway.isAvailable() && healthConnectGateway.hasAllPermissions()
        } catch (e: Exception) {
            false
        }
        if (!permitted) return PostWorkoutSyncOutcome.PERMANENT_FAILURE

        val workout = workoutRepository.getWorkout(workoutId) ?: return PostWorkoutSyncOutcome.PERMANENT_FAILURE
        val endedAt = workout.endedAt ?: return PostWorkoutSyncOutcome.RETRY

        val exerciseRanges = workoutRepository.getExerciseSessions(workoutId).mapNotNull { session ->
            val startedAt = session.startedAt ?: return@mapNotNull null
            val sessionEndedAt = session.endedAt ?: return@mapNotNull null
            ExerciseTimeRange(exerciseId = session.id, startAt = startedAt, endAt = sessionEndedAt)
        }

        val readResult = when (
            val outcome = healthConnectGateway.readWorkoutHeartRate(
                workoutStartedAt = workout.startedAt,
                workoutEndedAt = endedAt,
                exercises = exerciseRanges,
            )
        ) {
            is HealthConnectReadOutcome.Ok -> outcome.data
            is HealthConnectReadOutcome.PartiallyFailed -> outcome.data
            is HealthConnectReadOutcome.Failed ->
                return if (outcome.error.retryable) PostWorkoutSyncOutcome.RETRY else PostWorkoutSyncOutcome.PERMANENT_FAILURE
        }

        return try {
            val syncedAt = Instant.now()
            fitbitDataRepository.persist(
                readResult.toFitbitDataPayload(
                    workoutSessionId = workoutId,
                    workoutSyncStatus = FitbitSyncStatus.SYNCED,
                    syncedAt = syncedAt,
                ),
            )
            when (val pushResult = healthConnectSyncGateway.pushWorkoutAggregates(workoutId, readResult.aggregates)) {
                is SyncResult.Success -> PostWorkoutSyncOutcome.SYNCED
                is SyncResult.Failure ->
                    if (pushResult.error.retryable) PostWorkoutSyncOutcome.RETRY else PostWorkoutSyncOutcome.PERMANENT_FAILURE
            }
        } catch (e: Exception) {
            PostWorkoutSyncOutcome.RETRY
        }
    }
}
