package com.fittrack.core.training

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

class ProgressionEngineTest {
    private fun input(
        sets: List<SetPerformance>,
        targetRir: Int? = 2,
        successful: Int = 0,
        missed: Int = 0,
        readiness: ReadinessLevel = ReadinessLevel.GREEN,
    ) = ProgressionInput(100.0, 2.5, 8, 10, targetRir, sets, successful, missed, readiness)

    // ---- increase ----------------------------------------------------

    @Test fun `increases by exactly one increment at top range and target RIR`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 2), SetPerformance(10, 3))))
        assertEquals(ProgressionAction.INCREASE_LOAD, result.action)
        assertEquals(102.5, result.proposedWeightKg, 0.0)
    }

    @Test fun `does not increase when RIR is below target even at top reps`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 0), SetPerformance(10, 1))))
        assertEquals(ProgressionAction.HOLD_LOAD, result.action)
    }

    @Test fun `requires two consecutive successful sessions when RIR is absent`() {
        val first = ProgressionEngine.recommend(input(listOf(SetPerformance(10), SetPerformance(10)), targetRir = null))
        val second = ProgressionEngine.recommend(input(listOf(SetPerformance(10), SetPerformance(10)), targetRir = null, successful = 1))
        assertEquals(ProgressionAction.HOLD_LOAD, first.action)
        assertEquals(ProgressionAction.INCREASE_LOAD, second.action)
        assertEquals(102.5, second.proposedWeightKg, 0.0)
    }

    @Test fun `one prior successful session alone is not enough without RIR at session zero`() {
        // consecutiveSuccessfulSessions counts PRIOR sessions only; this session's own
        // performance still has to qualify as a success for the increase to fire.
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(9)), targetRir = null, successful = 3))
        assertEquals(ProgressionAction.HOLD_LOAD, result.action)
    }

    // ---- hold / decrease on missed reps -------------------------------

    @Test fun `holds on first miss below minimum reps`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(7, 1))))
        assertEquals(ProgressionAction.HOLD_LOAD, result.action)
        assertEquals("FIRST_MISS", result.reasonCode)
        assertEquals(100.0, result.proposedWeightKg, 0.0)
    }

    @Test fun `decreases by one increment after second consecutive miss`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(7, 1)), missed = 1))
        assertEquals(ProgressionAction.DECREASE_LOAD, result.action)
        assertEquals(97.5, result.proposedWeightKg, 0.0)
    }

    @Test fun `decrease never goes below zero`() {
        val result = ProgressionEngine.recommend(
            ProgressionInput(1.0, 2.5, 8, 10, 2, listOf(SetPerformance(5, 1)), consecutiveMissedSessions = 1)
        )
        assertEquals(ProgressionAction.DECREASE_LOAD, result.action)
        assertEquals(0.0, result.proposedWeightKg, 0.0)
    }

    // ---- safety: pain / concerning symptom / technique -----------------

    @Test fun `pain blocks progression and never diagnoses`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 3, pain = true))))
        assertEquals(ProgressionAction.STOP_AND_REVIEW, result.action)
        assertEquals("PAIN_OR_SYMPTOM", result.reasonCode)
    }

    @Test fun `concerning symptom blocks progression even with perfect reps`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 3, concerningSymptom = true))))
        assertEquals(ProgressionAction.STOP_AND_REVIEW, result.action)
    }

    @Test fun `pain blocks progression even after two prior misses`() {
        // Safety must win over the mechanical decrease-on-second-miss rule.
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(7, 1, pain = true)), missed = 1))
        assertEquals(ProgressionAction.STOP_AND_REVIEW, result.action)
    }

    @Test fun `technique issue holds instead of increasing`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 3, techniqueIssue = true))))
        assertEquals(ProgressionAction.HOLD_LOAD, result.action)
        assertEquals("TECHNIQUE_ISSUE", result.reasonCode)
    }

    @Test fun `technique issue takes priority over a rep-range miss`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(6, 1, techniqueIssue = true))))
        assertEquals(ProgressionAction.HOLD_LOAD, result.action)
        assertEquals("TECHNIQUE_ISSUE", result.reasonCode)
    }

    // ---- readiness gating on an otherwise-earned increase ---------------

    @Test fun `yellow readiness blocks an otherwise-earned increase`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 3)), readiness = ReadinessLevel.YELLOW))
        assertEquals(ProgressionAction.HOLD_LOAD, result.action)
        assertEquals("YELLOW_READINESS", result.reasonCode)
    }

    @Test fun `red readiness proposes one fewer working set instead of increasing`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 3)), readiness = ReadinessLevel.RED))
        assertEquals(ProgressionAction.REDUCE_VOLUME, result.action)
        assertEquals(-1, result.proposedSetDelta)
        assertEquals(100.0, result.proposedWeightKg, 0.0)
    }

    @Test fun `unknown readiness does not block an earned increase`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 3)), readiness = ReadinessLevel.UNKNOWN))
        assertEquals(ProgressionAction.INCREASE_LOAD, result.action)
    }

    // ---- mid-range progress: neither top nor below minimum -------------

    @Test fun `holds while still climbing through the rep range`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(9, 2), SetPerformance(9, 2))))
        assertEquals(ProgressionAction.HOLD_LOAD, result.action)
        assertEquals("PROGRESS_REPS", result.reasonCode)
    }

    @Test fun `no completed working sets holds`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 2, isWarmup = true))))
        assertEquals(ProgressionAction.HOLD_LOAD, result.action)
        assertEquals("NO_WORKING_SETS", result.reasonCode)
    }

    // ---- table-driven sweep over the action matrix ----------------------

    data class Case(
        val label: String,
        val sets: List<SetPerformance>,
        val targetRir: Int?,
        val successful: Int,
        val missed: Int,
        val readiness: ReadinessLevel,
        val expected: ProgressionAction,
    )

    @RunWith(Parameterized::class)
    class Matrix(private val case: Case) {
        companion object {
            @JvmStatic
            @Parameters(name = "{0}")
            fun cases(): List<Case> = listOf(
                Case("increase: top+RIR", listOf(SetPerformance(10, 2)), 2, 0, 0, ReadinessLevel.GREEN, ProgressionAction.INCREASE_LOAD),
                Case("hold: no RIR history yet", listOf(SetPerformance(10)), null, 0, 0, ReadinessLevel.GREEN, ProgressionAction.HOLD_LOAD),
                Case("increase: no RIR, one prior success", listOf(SetPerformance(10)), null, 1, 0, ReadinessLevel.GREEN, ProgressionAction.INCREASE_LOAD),
                Case("hold: first miss", listOf(SetPerformance(5, 1)), 2, 0, 0, ReadinessLevel.GREEN, ProgressionAction.HOLD_LOAD),
                Case("decrease: second miss", listOf(SetPerformance(5, 1)), 2, 0, 1, ReadinessLevel.GREEN, ProgressionAction.DECREASE_LOAD),
                Case("stop: pain", listOf(SetPerformance(10, 2, pain = true)), 2, 0, 0, ReadinessLevel.GREEN, ProgressionAction.STOP_AND_REVIEW),
                Case("stop: concerning symptom", listOf(SetPerformance(10, 2, concerningSymptom = true)), 2, 0, 0, ReadinessLevel.GREEN, ProgressionAction.STOP_AND_REVIEW),
                Case("hold: technique issue", listOf(SetPerformance(10, 2, techniqueIssue = true)), 2, 0, 0, ReadinessLevel.GREEN, ProgressionAction.HOLD_LOAD),
                Case("hold: yellow readiness blocks increase", listOf(SetPerformance(10, 2)), 2, 0, 0, ReadinessLevel.YELLOW, ProgressionAction.HOLD_LOAD),
                Case("reduce volume: red readiness", listOf(SetPerformance(10, 2)), 2, 0, 0, ReadinessLevel.RED, ProgressionAction.REDUCE_VOLUME),
                Case("hold: mid-range reps", listOf(SetPerformance(9, 2)), 2, 0, 0, ReadinessLevel.GREEN, ProgressionAction.HOLD_LOAD),
            )
        }

        @Test
        fun `matches expected action`() {
            val result = ProgressionEngine.recommend(
                ProgressionInput(100.0, 2.5, 8, 10, case.targetRir, case.sets, case.successful, case.missed, case.readiness)
            )
            assertEquals(case.label, case.expected, result.action)
        }
    }
}
