package com.fittrack.app.di

import com.fittrack.core.model.FitbitDataPayload
import com.fittrack.core.model.FitbitSyncStatus
import com.fittrack.core.model.repository.FitbitDataRepository
import com.fittrack.core.network.FitbitExerciseHeartRateSummary
import com.fittrack.core.network.FitbitGateway
import com.fittrack.core.network.FitbitGatewayState
import com.fittrack.core.network.FitbitHeartRateSample
import com.fittrack.core.network.FitbitOAuthStartResult
import com.fittrack.core.network.FitbitRemoteSyncStatus
import com.fittrack.core.network.FitbitSyncMode
import com.fittrack.core.network.FitbitSyncRequest
import com.fittrack.core.network.FitbitSyncResult
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PersistingFitbitGatewayTest {
    @Test
    fun `workout response is normalized and persisted before it is returned`() = runTest {
        val workoutId = "workout-1"
        val sampledAt = Instant.parse("2026-08-01T10:00:00Z")
        val remoteResult = FitbitSyncResult.Success(
            status = FitbitRemoteSyncStatus.SYNCED,
            importedCounts = mapOf("heartRateSamples" to 1),
            retryAfterSeconds = null,
            heartRateSamples = listOf(FitbitHeartRateSample(workoutId, sampledAt, 142)),
            exerciseHeartRateSummaries = listOf(
                FitbitExerciseHeartRateSummary("exercise-1", 141.6, 165, 18, 20),
            ),
        )
        val repository = CapturingFitbitRepository()
        val gateway = PersistingFitbitGateway(
            delegate = FakeFitbitGateway(remoteResult),
            repository = repository,
            now = { Instant.parse("2026-08-01T10:30:00Z") },
        )

        val returned = gateway.sync(FitbitSyncRequest(FitbitSyncMode.WORKOUT_HR, workoutId))

        assertEquals(remoteResult, returned)
        val payload = requireNotNull(repository.payload)
        assertEquals(workoutId, payload.workoutSessionId)
        assertEquals(FitbitSyncStatus.SYNCED, payload.workoutSyncStatus)
        assertEquals(142, payload.heartRateSamples.single().bpm)
        assertEquals(142, payload.exerciseHeartRateSummaries.single().averageBpm)
    }

    private class FakeFitbitGateway(private val result: FitbitSyncResult) : FitbitGateway {
        override val state = MutableStateFlow<FitbitGatewayState>(FitbitGatewayState.Connected)
        override suspend fun startOAuth(redirectUri: String): FitbitOAuthStartResult =
            error("Not used")
        override fun handleOAuthCallback(success: Boolean, reason: String?) = Unit
        override suspend fun sync(request: FitbitSyncRequest): FitbitSyncResult = result
    }

    private class CapturingFitbitRepository : FitbitDataRepository {
        var payload: FitbitDataPayload? = null
        override fun observeCardioActivities() = emptyFlow<List<com.fittrack.core.model.CardioActivity>>()
        override fun observeHeartRateSamples(workoutSessionId: String?) =
            emptyFlow<List<com.fittrack.core.model.HeartRateSample>>()
        override fun observeExerciseHeartRateSummaries(workoutSessionId: String?) =
            emptyFlow<List<com.fittrack.core.model.ExerciseHeartRateSummary>>()
        override suspend fun persist(payload: FitbitDataPayload) {
            this.payload = payload
        }
    }
}
