package com.fittrack.core.training

import com.fittrack.core.model.CoachSuggestion
import com.fittrack.core.model.Exercise
import com.fittrack.core.model.ExerciseSession
import com.fittrack.core.model.ExerciseSessionStatus
import com.fittrack.core.model.HealthDay
import com.fittrack.core.model.PlanExercise
import com.fittrack.core.model.PlannedSet
import com.fittrack.core.model.SetLog
import com.fittrack.core.model.SetType
import com.fittrack.core.model.SuggestionType
import com.fittrack.core.model.WorkoutPlan
import com.fittrack.core.model.WorkoutPlanSnapshot
import com.fittrack.core.model.WorkoutSession
import com.fittrack.core.model.WorkoutStatus
import com.fittrack.core.model.FitbitSyncStatus
import com.fittrack.core.model.repository.HealthRepository
import com.fittrack.core.model.repository.PlanRepository
import com.fittrack.core.model.repository.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * End-to-end test of CoachEngine's wiring: it reads history through the
 * repository interfaces, derives consecutive-success/miss streaks, and asks
 * ProgressionEngine for a recommendation, without ever auto-applying it.
 */
class CoachEngineTest {
    private val planExerciseId = UUID.randomUUID().toString()
    private val plan = PlanExercise(
        id = planExerciseId,
        exerciseId = "ex-1",
        exerciseName = "Squat",
        position = 0,
        warmupSets = 0,
        workingSets = 2,
        repMin = 8,
        repMax = 10,
        targetRir = null, // missing-RIR path
        startingWeightKg = 100.0,
        restSeconds = 120,
        weightIncrementKg = 2.5,
    )

    private fun session(id: String, sets: List<SetLog>, status: ExerciseSessionStatus = ExerciseSessionStatus.COMPLETED) =
        ExerciseSession(id, "wk-$id", planExerciseId, "ex-1", "ex-1", "Squat", 0, 120, status, Instant.now(), Instant.now(), null, "", "") to sets

    private fun workingSet(reps: Int) = SetLog(
        id = UUID.randomUUID().toString(),
        exerciseSessionId = "unused",
        position = 0,
        planned = PlannedSet(SetType.WORKING, plan.repMin, plan.repMax, plan.targetRir, plan.startingWeightKg),
        actualReps = reps,
        actualWeightKg = plan.startingWeightKg,
        actualRir = null,
        pain = false,
        techniqueIssue = false,
        concerningSymptom = false,
        completedAt = Instant.now(),
    )

    private class FakeWorkoutRepository(
        private val current: Pair<ExerciseSession, List<SetLog>>,
        private val history: List<Pair<ExerciseSession, List<SetLog>>>,
    ) : WorkoutRepository {
        override fun observeActiveWorkout(): Flow<WorkoutSession?> = flowOf(null)
        override suspend fun getActiveWorkout(): WorkoutSession? = null
        override fun observeHistory(limit: Int): Flow<List<WorkoutSession>> = flowOf(emptyList())
        override suspend fun getWorkout(workoutId: String): WorkoutSession? = null
        override fun observeExerciseSessions(workoutId: String): Flow<List<ExerciseSession>> = flowOf(listOf(current.first))
        override suspend fun getExerciseSessions(workoutId: String): List<ExerciseSession> = listOf(current.first)
        override fun observeSetLogs(exerciseSessionId: String): Flow<List<SetLog>> = flowOf(setsFor(exerciseSessionId))
        override suspend fun getSetLogs(exerciseSessionId: String): List<SetLog> = setsFor(exerciseSessionId)
        override suspend fun getExerciseSessionHistory(planExerciseId: String, excludeWorkoutId: String?, limit: Int) =
            history.map { it.first }.take(limit)
        override suspend fun startWorkout(planId: String) = throw UnsupportedOperationException()
        override suspend fun completeSet(setId: String, reps: Int, weightKg: Double, rir: Int?, pain: Boolean, techniqueIssue: Boolean, concerningSymptom: Boolean) = throw UnsupportedOperationException()
        override suspend fun finishExercise(exerciseSessionId: String, note: String) = Unit
        override suspend fun skipExercise(exerciseSessionId: String, reason: String) = Unit
        override suspend fun swapExercise(exerciseSessionId: String, replacementExerciseId: String) = Unit
        override suspend fun finishWorkout(workoutId: String, note: String) = throw UnsupportedOperationException()
        override suspend fun cancelWorkout(workoutId: String) = Unit
        override fun observePendingSuggestions(): Flow<List<CoachSuggestion>> = flowOf(emptyList())
        override suspend fun getSuggestion(id: String): CoachSuggestion? = null
        override suspend fun saveSuggestions(suggestions: List<CoachSuggestion>) = Unit
        override suspend fun decideSuggestion(id: String, accept: Boolean, reason: String) = Unit

        private fun setsFor(exerciseSessionId: String): List<SetLog> =
            if (exerciseSessionId == current.first.id) current.second
            else history.firstOrNull { it.first.id == exerciseSessionId }?.second.orEmpty()
    }

    private class FakePlanRepository(private val planExercise: PlanExercise) : PlanRepository {
        override fun observePlans(): Flow<List<WorkoutPlan>> = flowOf(emptyList())
        override suspend fun getPlan(planId: String): WorkoutPlan? = null
        override suspend fun getPlanExercise(planExerciseId: String) = planExercise
        override suspend fun createPlan(plan: WorkoutPlan) = Unit
        override suspend fun updatePlan(plan: WorkoutPlan) = Unit
        override suspend fun deletePlan(planId: String) = Unit
        override suspend fun applySuggestion(suggestion: CoachSuggestion) = Unit
        override suspend fun importPlanFromJson(json: String) = throw UnsupportedOperationException()
        override suspend fun importExerciseLibraryFromJson(json: String) = Unit
        override suspend fun findSwapCandidates(exerciseId: String, availableEquipment: Set<String>, limit: Int) = emptyList<Exercise>()
        override fun observeExerciseLibrary(): Flow<List<Exercise>> = flowOf(emptyList())
        override suspend fun getExercise(exerciseId: String): Exercise? = null
        override suspend fun upsertExercise(exercise: Exercise) = Unit
    }

    private class FakeHealthRepository(private val days: List<HealthDay> = emptyList()) : HealthRepository {
        override fun observeDailyHealth(limit: Int): Flow<List<HealthDay>> = flowOf(days)
        override suspend fun getRecentHealth(days: Int): List<HealthDay> = this.days
        override suspend fun upsertDailyHealth(days: List<HealthDay>) = Unit
    }

    @Test
    fun `missing RIR needs one prior qualifying session before increase fires`() = runTest {
        val current = session("cur", listOf(workingSet(10), workingSet(10)))
        val priorSuccess = session("prior", listOf(workingSet(10), workingSet(10)))

        val withoutHistory = CoachEngine(FakePlanRepository(plan), FakeWorkoutRepository(current, emptyList()), FakeHealthRepository())
        val withHistory = CoachEngine(FakePlanRepository(plan), FakeWorkoutRepository(current, listOf(priorSuccess)), FakeHealthRepository())

        val resultWithout = withoutHistory.generateSuggestions("wk")
        val resultWith = withHistory.generateSuggestions("wk")

        assertTrue(resultWithout.none { it.type == SuggestionType.LOAD })
        assertEquals(1, resultWith.count { it.type == SuggestionType.LOAD })
        assertEquals("102.5", resultWith.first { it.type == SuggestionType.LOAD }.after)
    }

    @Test
    fun `two consecutive misses in history produce a decrease suggestion`() = runTest {
        val current = session("cur", listOf(workingSet(5), workingSet(5)))
        val priorMiss = session("prior", listOf(workingSet(5), workingSet(5)))

        val engine = CoachEngine(FakePlanRepository(plan), FakeWorkoutRepository(current, listOf(priorMiss)), FakeHealthRepository())
        val result = engine.generateSuggestions("wk")

        val loadSuggestion = result.first { it.type == SuggestionType.LOAD }
        assertEquals("97.5", loadSuggestion.after)
    }

    @Test
    fun `pain in the completed session yields a PENDING hold suggestion, never auto-applied`() = runTest {
        val painSet = workingSet(10).copy(pain = true)
        val current = session("cur", listOf(painSet, workingSet(10)))

        val engine = CoachEngine(FakePlanRepository(plan), FakeWorkoutRepository(current, emptyList()), FakeHealthRepository())
        val result = engine.generateSuggestions("wk")

        assertEquals(1, result.size)
        assertEquals(SuggestionType.HOLD, result.first().type)
        assertEquals(com.fittrack.core.model.SuggestionStatus.PENDING, result.first().status)
        assertTrue(result.first().signals.contains("pain"))
    }
}
