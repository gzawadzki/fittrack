package com.fittrack.feature.plans

import com.fittrack.core.model.Exercise
import com.fittrack.core.model.PlanExercise
import com.fittrack.core.model.WorkoutPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlanUiModelsTest {
    @Test
    fun `validation rejects reversed rep range`() {
        val draft = validDraft().copy(exercises = listOf(validExercise().copy(repMin = 12, repMax = 8)))

        assertNotNull(draft.validationError())
    }

    @Test
    fun `validation accepts complete prescription`() {
        assertNull(validDraft().validationError())
    }

    @Test
    fun `validation rejects non finite loads`() {
        assertNotNull(validDraft().copy(exercises = listOf(validExercise().copy(startingWeightKg = Double.NaN))).validationError())
        assertNotNull(validDraft().copy(exercises = listOf(validExercise().copy(weightIncrementKg = Double.POSITIVE_INFINITY))).validationError())
    }

    @Test
    fun `new exercises use 2_5 kg progression and reject smaller increments`() {
        assertEquals(2.5, ExerciseTemplate().weightIncrementKg, 0.0)
        assertNotNull(
            validDraft().copy(exercises = listOf(validExercise().copy(weightIncrementKg = 1.25))).validationError(),
        )
    }

    @Test
    fun `plan mapping preserves exercise library swap metadata`() {
        val exercise = Exercise("exercise", "Przysiad", "SQUAT", "QUADS", "BARBELL")
        val plan = WorkoutPlan(
            id = "plan",
            name = "Dół",
            dayLabel = "Poniedziałek",
            scheduledDayOfWeek = 1,
            scheduledMinuteOfDay = 18 * 60,
            version = 3,
            active = true,
            exercises = listOf(
                PlanExercise("prescription", exercise.id, exercise.name, 0, 2, 3, 6, 8, 2, 80.0, 180, 2.5),
            ),
        )

        val draft = plan.toDraft(mapOf(exercise.id to exercise))

        assertEquals("SQUAT", draft.exercises.single().movementPattern)
        assertEquals("QUADS", draft.exercises.single().muscleRole)
        assertEquals("BARBELL", draft.exercises.single().equipment)
    }

    private fun validDraft() = WorkoutPlanDraft(name = "Plan A", exercises = listOf(validExercise()))

    private fun validExercise() = ExerciseTemplate(
        name = "Przysiad",
        movementPattern = "SQUAT",
        muscleRole = "QUADS",
        equipment = "BARBELL",
        warmupSets = 2,
        workingSets = 3,
        repMin = 6,
        repMax = 8,
        targetRir = 2,
        startingWeightKg = 80.0,
        restSeconds = 180,
        weightIncrementKg = 2.5,
    )
}
