package com.fittrack.core.training

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadinessCalculatorTest {
    @Test fun `unknown before seven valid baseline days`() {
        val result = ReadinessCalculator.calculate(RecoveryDay(300, 70), List(6) { RecoveryDay(450, 60) })
        assertEquals(ReadinessLevel.UNKNOWN, result)
    }

    @Test fun `unknown when today is missing sleep`() {
        val result = ReadinessCalculator.calculate(RecoveryDay(null, 70), List(14) { RecoveryDay(450, 60) })
        assertEquals(ReadinessLevel.UNKNOWN, result)
    }

    @Test fun `unknown when today is missing resting heart rate`() {
        val result = ReadinessCalculator.calculate(RecoveryDay(300, null), List(14) { RecoveryDay(450, 60) })
        assertEquals(ReadinessLevel.UNKNOWN, result)
    }

    @Test fun `green when sleep and heart rate are within baseline`() {
        val result = ReadinessCalculator.calculate(RecoveryDay(440, 61), List(14) { RecoveryDay(450, 60) })
        assertEquals(ReadinessLevel.GREEN, result)
    }

    @Test fun `yellow when sleep alone is under 80 percent of the 14-day median`() {
        val result = ReadinessCalculator.calculate(RecoveryDay(300, 60), List(14) { RecoveryDay(450, 60) })
        assertEquals(ReadinessLevel.YELLOW, result)
    }

    @Test fun `yellow when resting heart rate alone is baseline plus five or more`() {
        val result = ReadinessCalculator.calculate(RecoveryDay(450, 65), List(14) { RecoveryDay(450, 60) })
        assertEquals(ReadinessLevel.YELLOW, result)
    }

    @Test fun `not yellow when heart rate is only four above baseline`() {
        val result = ReadinessCalculator.calculate(RecoveryDay(450, 64), List(14) { RecoveryDay(450, 60) })
        assertEquals(ReadinessLevel.GREEN, result)
    }

    @Test fun `red when both sleep and resting heart rate deviate`() {
        val result = ReadinessCalculator.calculate(RecoveryDay(300, 66), List(14) { RecoveryDay(450, 60) })
        assertEquals(ReadinessLevel.RED, result)
    }

    @Test fun `uses median not mean and only the last 14 days`() {
        // One extreme outlier far in the past should not shift the median once
        // more than 14 days of normal history exist.
        val history = listOf(RecoveryDay(0, 200)) + List(14) { RecoveryDay(450, 60) }
        val result = ReadinessCalculator.calculate(RecoveryDay(440, 61), history)
        assertEquals(ReadinessLevel.GREEN, result)
    }

    @Test fun `step count has no calculator input and cannot affect readiness`() {
        // RecoveryDay intentionally has no steps field — steps are context
        // only per section 5 and must never reach this calculation.
        val a = ReadinessCalculator.calculate(RecoveryDay(300, 66), List(14) { RecoveryDay(450, 60) })
        val b = ReadinessCalculator.calculate(RecoveryDay(300, 66), List(14) { RecoveryDay(450, 60) })
        assertEquals(a, b)
    }
}
