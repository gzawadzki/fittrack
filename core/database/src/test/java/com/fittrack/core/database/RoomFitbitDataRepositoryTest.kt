package com.fittrack.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fittrack.core.database.repository.RoomFitbitDataRepository
import com.fittrack.core.database.repository.RoomPlanRepository
import com.fittrack.core.database.repository.RoomWorkoutRepository
import com.fittrack.core.model.CardioActivity
import com.fittrack.core.model.Exercise
import com.fittrack.core.model.ExerciseHeartRateSummary
import com.fittrack.core.model.FitbitDataPayload
import com.fittrack.core.model.FitbitSyncStatus
import com.fittrack.core.model.HealthDay
import com.fittrack.core.model.HeartRateSample
import com.fittrack.core.model.PlanExercise
import com.fittrack.core.model.WorkoutPlan
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomFitbitDataRepositoryTest {
    private lateinit var database: FitTrackDatabase
    private lateinit var plans: RoomPlanRepository
    private lateinit var workouts: RoomWorkoutRepository
    private lateinit var repository: RoomFitbitDataRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = FitTrackDatabase.createInMemory(context)
        plans = RoomPlanRepository(database)
        workouts = RoomWorkoutRepository(database)
        repository = RoomFitbitDataRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `normalized payload is replay-safe and no-data aggregate stays absent`() = runTest {
        val workoutId = completedWorkout()
        val exerciseSessionId = workouts.getExerciseSessions(workoutId).single().id
        val syncedAt = Instant.parse("2026-08-01T12:00:00Z")
        val sampledAt = Instant.parse("2026-08-01T10:10:00Z")
        val sourceId = "fitbit-log-42"
        val firstPayload = FitbitDataPayload(
            workoutSessionId = workoutId,
            workoutSyncStatus = FitbitSyncStatus.SYNCED,
            dailyHealth = listOf(HealthDay("2026-08-01", 430, 57, 9_500, "FITBIT")),
            cardioActivities = listOf(cardio(id = "caller-id-is-ignored", sourceId = sourceId, steps = 5_000)),
            heartRateSamples = listOf(HeartRateSample(workoutId, sampledAt, 135)),
            exerciseHeartRateSummaries = listOf(
                ExerciseHeartRateSummary(exerciseSessionId, 132, 150, 25, 3),
                ExerciseHeartRateSummary("not-present", null, null, null, 0),
            ),
            syncedAt = syncedAt,
        )

        repository.persist(firstPayload)
        repository.persist(
            firstPayload.copy(
                cardioActivities = listOf(cardio(id = "another-caller-id", sourceId = sourceId, steps = 5_100)),
            ),
        )

        val cardio = repository.observeCardioActivities().first()
        assertEquals(1, cardio.size)
        assertEquals(5_100, cardio.single().steps)
        assertEquals(stableCardioId(sourceId), cardio.single().id)
        assertEquals(1, repository.observeHeartRateSamples().first().size)
        assertEquals(1, repository.observeHeartRateSamples(workoutId).first().size)

        val summaries = repository.observeExerciseHeartRateSummaries(workoutId).first()
        assertEquals(1, summaries.size)
        assertEquals(exerciseSessionId, summaries.single().exerciseSessionId)
        assertTrue(summaries.single().hasData)
        assertEquals(FitbitSyncStatus.SYNCED, workouts.getWorkout(workoutId)?.fitbitSyncStatus)
    }

    @Test
    fun `payload rollback keeps data and workout status unchanged when aggregate is invalid`() = runTest {
        val workoutId = completedWorkout()
        val before = workouts.getWorkout(workoutId)?.fitbitSyncStatus
        val payload = FitbitDataPayload(
            workoutSessionId = workoutId,
            workoutSyncStatus = FitbitSyncStatus.SYNCED,
            cardioActivities = listOf(cardio("ignored", "rollback-source", 100)),
            heartRateSamples = listOf(
                HeartRateSample(workoutId, Instant.parse("2026-08-01T10:10:00Z"), 120),
            ),
            exerciseHeartRateSummaries = listOf(
                ExerciseHeartRateSummary(UUID.randomUUID().toString(), 120, 140, 20, 2),
            ),
            syncedAt = Instant.parse("2026-08-01T12:00:00Z"),
        )

        assertTrue(runCatching { repository.persist(payload) }.isFailure)

        assertTrue(repository.observeCardioActivities().first().isEmpty())
        assertTrue(repository.observeHeartRateSamples().first().isEmpty())
        assertTrue(repository.observeExerciseHeartRateSummaries().first().isEmpty())
        assertEquals(before, workouts.getWorkout(workoutId)?.fitbitSyncStatus)
    }

    private suspend fun completedWorkout(): String {
        val exerciseId = UUID.randomUUID().toString()
        plans.upsertExercise(Exercise(exerciseId, "Squat", "SQUAT", "QUADS", "BARBELL"))
        val plan = WorkoutPlan(
            id = UUID.randomUUID().toString(),
            name = "Fitbit persistence",
            dayLabel = "A",
            scheduledDayOfWeek = null,
            scheduledMinuteOfDay = null,
            version = 1,
            active = true,
            exercises = listOf(
                PlanExercise(
                    id = UUID.randomUUID().toString(),
                    exerciseId = exerciseId,
                    exerciseName = "Squat",
                    position = 0,
                    warmupSets = 0,
                    workingSets = 1,
                    repMin = 5,
                    repMax = 8,
                    targetRir = 2,
                    startingWeightKg = 40.0,
                    restSeconds = 120,
                    weightIncrementKg = 2.5,
                ),
            ),
        )
        plans.createPlan(plan)
        val workout = workouts.startWorkout(plan.id)
        workouts.finishWorkout(workout.id, "done")
        return workout.id
    }

    private fun cardio(id: String, sourceId: String, steps: Int) = CardioActivity(
        id = id,
        sourceId = sourceId,
        kind = "Run",
        startedAt = Instant.parse("2026-08-01T06:00:00Z"),
        endedAt = Instant.parse("2026-08-01T06:30:00Z"),
        durationSeconds = 1_800,
        distanceMeters = 5_000.0,
        steps = steps,
        averageHeartRate = 142,
    )

    private fun stableCardioId(sourceId: String): String = UUID.nameUUIDFromBytes(
        "fitbit-cardio:$sourceId".toByteArray(StandardCharsets.UTF_8),
    ).toString()
}
