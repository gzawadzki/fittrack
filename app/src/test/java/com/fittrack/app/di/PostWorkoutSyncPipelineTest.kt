package com.fittrack.app.di

import com.fittrack.core.model.CardioActivity
import com.fittrack.core.model.CoachSuggestion
import com.fittrack.core.model.ExerciseHeartRateSummary
import com.fittrack.core.model.ExerciseSession
import com.fittrack.core.model.FitbitDataPayload
import com.fittrack.core.model.FitbitSyncStatus
import com.fittrack.core.model.HeartRateSample
import com.fittrack.core.model.SetLog
import com.fittrack.core.model.WorkoutPlanSnapshot
import com.fittrack.core.model.WorkoutSession
import com.fittrack.core.model.WorkoutStatus
import com.fittrack.core.model.repository.FitbitDataRepository
import com.fittrack.core.model.repository.WorkoutRepository
import com.fittrack.core.network.ExerciseHrAggregate
import com.fittrack.core.network.ExerciseTimeRange
import com.fittrack.core.network.HealthConnectDailyMetric
import com.fittrack.core.network.HealthConnectGateway
import com.fittrack.core.network.HealthConnectReadOutcome
import com.fittrack.core.network.HealthConnectSyncGateway
import com.fittrack.core.network.NetworkError
import com.fittrack.core.network.SyncBatchRequest
import com.fittrack.core.network.SyncBatchResponse
import com.fittrack.core.network.SyncClient
import com.fittrack.core.network.SyncResult
import com.fittrack.core.network.WorkoutHeartRateReadResult
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exercises [PostWorkoutSyncPipeline] directly - no WorkManager, no
 * `FitTrackApplication`, no Android framework at all (architecture review
 * candidate 2). Written without a compiler/Gradle in this sandbox, same
 * caveat as the rest of this repo's tests - see fitbit-HANDOFF.md.
 */
class PostWorkoutSyncPipelineTest {
    private val workoutId = "workout-1"
    private val startedAt = Instant.parse("2026-08-01T10:00:00Z")
    private val endedAt = Instant.parse("2026-08-01T10:45:00Z")

    @Test
    fun `not permitted is a permanent failure`() = runTest {
        val pipeline = pipeline(healthConnectGateway = FakeHealthConnectGateway(available = false))

        assertEquals(PostWorkoutSyncOutcome.PERMANENT_FAILURE, pipeline.sync(workoutId))
    }

    @Test
    fun `missing workout is a permanent failure`() = runTest {
        val pipeline = pipeline(workoutRepository = FakeWorkoutRepository(workout = null))

        assertEquals(PostWorkoutSyncOutcome.PERMANENT_FAILURE, pipeline.sync(workoutId))
    }

    @Test
    fun `workout still in progress is retried`() = runTest {
        val pipeline = pipeline(workoutRepository = FakeWorkoutRepository(workout = workout(endedAt = null)))

        assertEquals(PostWorkoutSyncOutcome.RETRY, pipeline.sync(workoutId))
    }

    @Test
    fun `retryable read failure is retried`() = runTest {
        val pipeline = pipeline(
            healthConnectGateway = FakeHealthConnectGateway(
                workoutHeartRateOutcome = HealthConnectReadOutcome.Failed(
                    NetworkError("HEALTH_CONNECT_READ_FAILED", "transient", retryable = true),
                ),
            ),
        )

        assertEquals(PostWorkoutSyncOutcome.RETRY, pipeline.sync(workoutId))
    }

    @Test
    fun `non-retryable read failure is a permanent failure`() = runTest {
        val pipeline = pipeline(
            healthConnectGateway = FakeHealthConnectGateway(
                workoutHeartRateOutcome = HealthConnectReadOutcome.Failed(
                    NetworkError("HEALTH_CONNECT_READ_FAILED", "permission revoked", retryable = false),
                ),
            ),
        )

        assertEquals(PostWorkoutSyncOutcome.PERMANENT_FAILURE, pipeline.sync(workoutId))
    }

    @Test
    fun `successful read and push persists and syncs`() = runTest {
        val fitbitDataRepository = FakeFitbitDataRepository()
        val pipeline = pipeline(
            fitbitDataRepository = fitbitDataRepository,
            healthConnectSyncGateway = HealthConnectSyncGateway(
                syncClient = RecordingSyncClient(SyncResult.Success(SyncBatchResponse(emptyList(), emptyList(), 0))),
            ),
        )

        val outcome = pipeline.sync(workoutId)

        assertEquals(PostWorkoutSyncOutcome.SYNCED, outcome)
        assertEquals(FitbitSyncStatus.SYNCED, fitbitDataRepository.persisted.single().workoutSyncStatus)
    }

    @Test
    fun `retryable push failure is retried`() = runTest {
        val pipeline = pipeline(
            // A push failure can only surface if there's something to push - an
            // empty aggregates list short-circuits HealthConnectSyncGateway.push()
            // to Success before it ever reaches syncClient, silently skipping the
            // RecordingSyncClient's configured Failure below.
            healthConnectGateway = FakeHealthConnectGateway(workoutHeartRateOutcome = okWithOneAggregate()),
            healthConnectSyncGateway = HealthConnectSyncGateway(
                syncClient = RecordingSyncClient(
                    SyncResult.Failure(NetworkError("OFFLINE", "no network", retryable = true)),
                ),
            ),
        )

        assertEquals(PostWorkoutSyncOutcome.RETRY, pipeline.sync(workoutId))
    }

    @Test
    fun `non-retryable push failure - eg Supabase never configured - is a permanent failure, not an endless retry`() = runTest {
        val pipeline = pipeline(
            healthConnectGateway = FakeHealthConnectGateway(workoutHeartRateOutcome = okWithOneAggregate()),
            healthConnectSyncGateway = HealthConnectSyncGateway(
                syncClient = RecordingSyncClient(
                    SyncResult.Failure(NetworkError("SYNC_NOT_CONFIGURED", "not configured", retryable = false)),
                ),
            ),
        )

        assertEquals(PostWorkoutSyncOutcome.PERMANENT_FAILURE, pipeline.sync(workoutId))
    }

    private fun okWithOneAggregate() = HealthConnectReadOutcome.Ok(
        WorkoutHeartRateReadResult(
            samples = emptyList(),
            aggregates = listOf(ExerciseHrAggregate("exercise-1", avgBpm = 120.0, maxBpm = 150, recoveryDeltaBpm = 20, sampleCount = 10)),
        ),
    )

    @Test
    fun `exception while persisting is retried`() = runTest {
        val pipeline = pipeline(fitbitDataRepository = ThrowingFitbitDataRepository())

        assertEquals(PostWorkoutSyncOutcome.RETRY, pipeline.sync(workoutId))
    }

    private fun pipeline(
        workoutRepository: WorkoutRepository = FakeWorkoutRepository(workout = workout()),
        healthConnectGateway: HealthConnectGateway = FakeHealthConnectGateway(),
        fitbitDataRepository: FitbitDataRepository = FakeFitbitDataRepository(),
        healthConnectSyncGateway: HealthConnectSyncGateway = HealthConnectSyncGateway(
            syncClient = RecordingSyncClient(SyncResult.Success(SyncBatchResponse(emptyList(), emptyList(), 0))),
        ),
    ) = PostWorkoutSyncPipeline(workoutRepository, healthConnectGateway, fitbitDataRepository, healthConnectSyncGateway)

    private fun workout(endedAt: Instant? = this.endedAt) = WorkoutSession(
        id = workoutId,
        planSnapshot = WorkoutPlanSnapshot("plan-1", "Push Day", 1, "Day A", emptyList()),
        status = if (endedAt == null) WorkoutStatus.ACTIVE else WorkoutStatus.COMPLETED,
        startedAt = startedAt,
        endedAt = endedAt,
        note = "",
        fitbitSyncStatus = FitbitSyncStatus.PENDING,
    )

    private class FakeWorkoutRepository(
        private val workout: WorkoutSession?,
        private val exerciseSessions: List<ExerciseSession> = emptyList(),
    ) : WorkoutRepository {
        override fun observeActiveWorkout(): Flow<WorkoutSession?> = flowOf(null)
        override suspend fun getActiveWorkout(): WorkoutSession? = null
        override fun observeHistory(limit: Int): Flow<List<WorkoutSession>> = flowOf(emptyList())
        override suspend fun getWorkout(workoutId: String): WorkoutSession? = workout
        override fun observeExerciseSessions(workoutId: String): Flow<List<ExerciseSession>> = flowOf(exerciseSessions)
        override suspend fun getExerciseSessions(workoutId: String): List<ExerciseSession> = exerciseSessions
        override fun observeSetLogs(exerciseSessionId: String): Flow<List<SetLog>> = flowOf(emptyList())
        override suspend fun getSetLogs(exerciseSessionId: String): List<SetLog> = emptyList()
        override suspend fun getExerciseSessionHistory(planExerciseId: String, excludeWorkoutId: String?, limit: Int) = emptyList<ExerciseSession>()
        override suspend fun startWorkout(planId: String) = throw UnsupportedOperationException()
        override suspend fun startExercise(exerciseSessionId: String) = throw UnsupportedOperationException()
        override suspend fun completeSet(setId: String, reps: Int, weightKg: Double, rir: Int?, pain: Boolean, techniqueIssue: Boolean, concerningSymptom: Boolean) = throw UnsupportedOperationException()
        override suspend fun skipSet(setId: String) = throw UnsupportedOperationException()
        override suspend fun clearLoggedSet(setId: String) = throw UnsupportedOperationException()
        override suspend fun finishExercise(exerciseSessionId: String, note: String) = throw UnsupportedOperationException()
        override suspend fun skipExercise(exerciseSessionId: String, reason: String) = throw UnsupportedOperationException()
        override suspend fun swapExercise(exerciseSessionId: String, replacementExerciseId: String) = throw UnsupportedOperationException()
        override suspend fun finishWorkout(workoutId: String, note: String) = throw UnsupportedOperationException()
        override suspend fun cancelWorkout(workoutId: String) = throw UnsupportedOperationException()
        override suspend fun deleteWorkout(workoutId: String) = throw UnsupportedOperationException()
        override fun observePendingSuggestions(): Flow<List<CoachSuggestion>> = emptyFlow()
        override suspend fun getSuggestion(id: String): CoachSuggestion? = null
        override suspend fun saveSuggestions(suggestions: List<CoachSuggestion>) = throw UnsupportedOperationException()
        override suspend fun decideSuggestion(id: String, accept: Boolean, reason: String) = throw UnsupportedOperationException()
    }

    private class FakeHealthConnectGateway(
        private val available: Boolean = true,
        private val granted: Boolean = true,
        private val workoutHeartRateOutcome: HealthConnectReadOutcome<WorkoutHeartRateReadResult> =
            HealthConnectReadOutcome.Ok(WorkoutHeartRateReadResult(emptyList(), emptyList())),
    ) : HealthConnectGateway {
        override fun isAvailable(): Boolean = available
        override fun requiredPermissions(): Set<String> = emptySet()
        override suspend fun hasAllPermissions(): Boolean = granted
        override suspend fun readDailyMetrics(days: Int): HealthConnectReadOutcome<List<HealthConnectDailyMetric>> =
            HealthConnectReadOutcome.Ok(emptyList())

        override suspend fun readWorkoutHeartRate(
            workoutStartedAt: Instant,
            workoutEndedAt: Instant,
            exercises: List<ExerciseTimeRange>,
        ): HealthConnectReadOutcome<WorkoutHeartRateReadResult> = workoutHeartRateOutcome
    }

    private class FakeFitbitDataRepository : FitbitDataRepository {
        val persisted = mutableListOf<FitbitDataPayload>()
        override fun observeCardioActivities(): Flow<List<CardioActivity>> = emptyFlow()
        override fun observeHeartRateSamples(workoutSessionId: String?): Flow<List<HeartRateSample>> = emptyFlow()
        override fun observeExerciseHeartRateSummaries(workoutSessionId: String?): Flow<List<ExerciseHeartRateSummary>> = emptyFlow()
        override suspend fun persist(payload: FitbitDataPayload) {
            persisted += payload
        }
    }

    private class ThrowingFitbitDataRepository : FitbitDataRepository {
        override fun observeCardioActivities(): Flow<List<CardioActivity>> = emptyFlow()
        override fun observeHeartRateSamples(workoutSessionId: String?): Flow<List<HeartRateSample>> = emptyFlow()
        override fun observeExerciseHeartRateSummaries(workoutSessionId: String?): Flow<List<ExerciseHeartRateSummary>> = emptyFlow()
        override suspend fun persist(payload: FitbitDataPayload): Nothing = error("local database unavailable")
    }

    private class RecordingSyncClient(private val result: SyncResult) : SyncClient {
        override suspend fun sync(request: SyncBatchRequest): SyncResult = result
    }
}
