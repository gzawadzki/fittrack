package com.fittrack.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fittrack.core.database.repository.RoomPlanRepository
import com.fittrack.core.database.repository.RoomWorkoutRepository
import com.fittrack.core.model.Exercise
import com.fittrack.core.model.PlanExercise
import com.fittrack.core.model.WorkoutPlan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Exercises the transactional guarantees RoomWorkoutRepository must provide:
 * only one active workout, an immutable plan snapshot at start, and
 * idempotent set completion. Requires JDK + Robolectric to actually run —
 * see this module's contribution to HANDOFF.md for the current toolchain
 * limitation (no local JDK/Android SDK in this workspace at time of writing).
 */
@RunWith(RobolectricTestRunner::class)
class RoomWorkoutRepositoryTest {
    private lateinit var database: FitTrackDatabase
    private lateinit var planRepository: RoomPlanRepository
    private lateinit var workoutRepository: RoomWorkoutRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = FitTrackDatabase.createInMemory(context)
        planRepository = RoomPlanRepository(database)
        workoutRepository = RoomWorkoutRepository(database)
    }

    private suspend fun seedPlan(): WorkoutPlan {
        val exerciseId = UUID.randomUUID().toString()
        planRepository.upsertExercise(Exercise(exerciseId, "Squat", "SQUAT", "QUADS", "BARBELL"))
        val plan = WorkoutPlan(
            id = UUID.randomUUID().toString(),
            name = "Test plan",
            dayLabel = "Day A",
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
                    warmupSets = 1,
                    workingSets = 2,
                    repMin = 6,
                    repMax = 8,
                    targetRir = 2,
                    startingWeightKg = 40.0,
                    restSeconds = 120,
                    weightIncrementKg = 2.5,
                ),
            ),
        )
        planRepository.createPlan(plan)
        return plan
    }

    @Test
    fun `starting a workout twice returns the same active session`() = runTest {
        val plan = seedPlan()
        val first = workoutRepository.startWorkout(plan.id)
        val second = workoutRepository.startWorkout(plan.id)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `plan snapshot survives a later plan edit`() = runTest {
        val plan = seedPlan()
        val session = workoutRepository.startWorkout(plan.id)
        assertEquals(40.0, session.planSnapshot.exercises.first().plannedSets.last().targetWeightKg, 0.0)

        planRepository.updatePlan(plan.copy(exercises = plan.exercises.map { it.copy(startingWeightKg = 999.0) }))

        val reloaded = workoutRepository.getWorkout(session.id)
        assertNotNull(reloaded)
        assertEquals(40.0, reloaded!!.planSnapshot.exercises.first().plannedSets.last().targetWeightKg, 0.0)
    }

    @Test
    fun `completing a set twice is idempotent, not duplicated`() = runTest {
        val plan = seedPlan()
        val session = workoutRepository.startWorkout(plan.id)
        val exerciseSession = workoutRepository.getExerciseSessions(session.id).first()
        val setLog = workoutRepository.getSetLogs(exerciseSession.id).first()

        workoutRepository.completeSet(setLog.id, reps = 8, weightKg = 40.0, rir = 2, pain = false, techniqueIssue = false, concerningSymptom = false)
        workoutRepository.completeSet(setLog.id, reps = 8, weightKg = 40.0, rir = 2, pain = false, techniqueIssue = false, concerningSymptom = false)

        val logsAfter = workoutRepository.getSetLogs(exerciseSession.id)
        assertEquals(2, logsAfter.size) // 1 warmup + 1 working, unchanged count
        assertTrue(logsAfter.any { it.actualReps == 8 })
    }

    @Test
    fun `finishing a workout closes still-open exercises instead of leaving them dangling`() = runTest {
        val plan = seedPlan()
        val session = workoutRepository.startWorkout(plan.id)
        workoutRepository.finishWorkout(session.id, note = "done")

        val sessions = workoutRepository.getExerciseSessions(session.id)
        assertTrue(sessions.all { it.status.name == "COMPLETED" || it.status.name == "SKIPPED" })
        assertNull(workoutRepository.getActiveWorkout())
    }
}
