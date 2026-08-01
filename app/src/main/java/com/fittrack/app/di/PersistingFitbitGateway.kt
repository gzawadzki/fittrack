package com.fittrack.app.di

import com.fittrack.core.model.CardioActivity
import com.fittrack.core.model.ExerciseHeartRateSummary
import com.fittrack.core.model.FitbitDataPayload
import com.fittrack.core.model.FitbitSyncStatus
import com.fittrack.core.model.HealthDay
import com.fittrack.core.model.HeartRateSample
import com.fittrack.core.model.repository.FitbitDataRepository
import com.fittrack.core.network.FitbitGateway
import com.fittrack.core.network.FitbitGatewayError
import com.fittrack.core.network.FitbitOAuthStartResult
import com.fittrack.core.network.FitbitRemoteSyncStatus
import com.fittrack.core.network.FitbitSyncMode
import com.fittrack.core.network.FitbitSyncRequest
import com.fittrack.core.network.FitbitSyncResult
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/** Persists only the backend's normalized Fitbit response; raw Fitbit JSON never reaches Room. */
class PersistingFitbitGateway(
    private val delegate: FitbitGateway,
    private val repository: FitbitDataRepository,
    private val now: () -> Instant = Instant::now,
) : FitbitGateway {
    override val state: StateFlow<com.fittrack.core.network.FitbitGatewayState> = delegate.state

    override suspend fun startOAuth(redirectUri: String): FitbitOAuthStartResult =
        delegate.startOAuth(redirectUri)

    override fun handleOAuthCallback(success: Boolean, reason: String?) =
        delegate.handleOAuthCallback(success, reason)

    override suspend fun sync(request: FitbitSyncRequest): FitbitSyncResult {
        val result = delegate.sync(request)
        try {
            when (result) {
                is FitbitSyncResult.Success -> repository.persist(result.toPayload(request, now()))
                is FitbitSyncResult.Failure -> if (request.mode == FitbitSyncMode.WORKOUT_HR) {
                    repository.persist(
                        FitbitDataPayload(
                            workoutSessionId = request.workoutSessionId,
                            workoutSyncStatus = if (result.error.retryable) FitbitSyncStatus.PENDING else FitbitSyncStatus.FAILED,
                            syncedAt = now(),
                        ),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return FitbitSyncResult.Failure(
                FitbitGatewayError(
                    code = "LOCAL_FITBIT_PERSIST_FAILED",
                    userMessage = "Dane Fitbit pobrano, ale nie udało się zapisać ich lokalnie.",
                    retryable = true,
                ),
            )
        }
        return result
    }
}

private fun FitbitSyncResult.Success.toPayload(request: FitbitSyncRequest, syncedAt: Instant) = FitbitDataPayload(
    workoutSessionId = request.workoutSessionId,
    workoutSyncStatus = request.workoutSessionId?.let {
        when (status) {
            FitbitRemoteSyncStatus.PENDING, FitbitRemoteSyncStatus.PARTIAL -> FitbitSyncStatus.PENDING
            FitbitRemoteSyncStatus.SYNCED -> FitbitSyncStatus.SYNCED
            FitbitRemoteSyncStatus.FAILED -> FitbitSyncStatus.FAILED
        }
    },
    dailyHealth = dailyMetrics.map {
        HealthDay(it.date, it.sleepMinutes, it.restingHeartRate, it.steps, it.source)
    },
    cardioActivities = cardioActivities.map { item ->
        val duration = item.durationSeconds?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt()
            ?: item.endedAt?.let { java.time.Duration.between(item.startedAt, it).seconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
            ?: 0
        CardioActivity(
            id = "",
            sourceId = item.sourceId,
            kind = item.kind,
            startedAt = item.startedAt,
            endedAt = item.endedAt ?: item.startedAt.plusSeconds(duration.toLong()),
            durationSeconds = duration,
            distanceMeters = item.distanceMeters,
            steps = item.steps,
            averageHeartRate = item.averageHeartRate,
        )
    },
    heartRateSamples = heartRateSamples.map { HeartRateSample(it.workoutSessionId, it.sampledAt, it.bpm) },
    exerciseHeartRateSummaries = exerciseHeartRateSummaries.map {
        ExerciseHeartRateSummary(
            exerciseSessionId = it.exerciseSessionId,
            averageBpm = it.averageBpm?.roundToInt(),
            maximumBpm = it.maximumBpm,
            recoveryDeltaBpm = it.recoveryDeltaBpm,
            sampleCount = it.sampleCount,
        )
    },
    syncedAt = syncedAt,
)
