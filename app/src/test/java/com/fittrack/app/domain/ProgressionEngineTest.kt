package com.fittrack.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressionEngineTest {
    private fun input(
        sets: List<SetPerformance>,
        targetRir: Int? = 2,
        successful: Int = 0,
        missed: Int = 0,
        readiness: ReadinessLevel = ReadinessLevel.GREEN,
    ) = ProgressionInput(100.0, 2.5, 8, 10, targetRir, sets, successful, missed, readiness)

    @Test fun `increases by one increment at top range and target RIR`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 2), SetPerformance(10, 3))))
        assertEquals(ProgressionAction.INCREASE_LOAD, result.action)
        assertEquals(102.5, result.proposedWeightKg, 0.0)
    }

    @Test fun `requires two successful sessions when RIR is absent`() {
        val first = ProgressionEngine.recommend(input(listOf(SetPerformance(10), SetPerformance(10)), targetRir = null))
        val second = ProgressionEngine.recommend(input(listOf(SetPerformance(10), SetPerformance(10)), targetRir = null, successful = 1))
        assertEquals(ProgressionAction.HOLD_LOAD, first.action)
        assertEquals(ProgressionAction.INCREASE_LOAD, second.action)
    }

    @Test fun `holds on first miss and decreases after second`() {
        val first = ProgressionEngine.recommend(input(listOf(SetPerformance(7, 1))))
        val second = ProgressionEngine.recommend(input(listOf(SetPerformance(7, 1)), missed = 1))
        assertEquals(ProgressionAction.HOLD_LOAD, first.action)
        assertEquals(ProgressionAction.DECREASE_LOAD, second.action)
        assertEquals(97.5, second.proposedWeightKg, 0.0)
    }

    @Test fun `pain blocks progression`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 3, pain = true))))
        assertEquals(ProgressionAction.STOP_AND_REVIEW, result.action)
    }

    @Test fun `yellow readiness blocks increase`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 3)), readiness = ReadinessLevel.YELLOW))
        assertEquals(ProgressionAction.HOLD_LOAD, result.action)
    }

    @Test fun `red readiness proposes one fewer set`() {
        val result = ProgressionEngine.recommend(input(listOf(SetPerformance(10, 3)), readiness = ReadinessLevel.RED))
        assertEquals(ProgressionAction.REDUCE_VOLUME, result.action)
        assertEquals(-1, result.proposedSetDelta)
    }

    @Test fun `readiness is unknown before seven valid baseline days`() {
        val result = ReadinessCalculator.calculate(RecoveryDay(300, 70), List(6) { RecoveryDay(450, 60) })
        assertEquals(ReadinessLevel.UNKNOWN, result)
    }

    @Test fun `readiness is red for short sleep and elevated RHR`() {
        val result = ReadinessCalculator.calculate(RecoveryDay(300, 66), List(14) { RecoveryDay(450, 60) })
        assertEquals(ReadinessLevel.RED, result)
    }
}
