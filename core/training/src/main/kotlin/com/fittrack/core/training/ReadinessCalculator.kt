package com.fittrack.core.training

/**
 * Readiness rule (section 5, frozen):
 *  - Requires a minimum of 7 valid days of history (sleep AND resting HR
 *    present) before it can say anything other than UNKNOWN.
 *  - sleep < 80% of the 14-day median OR resting HR >= baseline + 5 -> YELLOW.
 *  - Both conditions true -> RED.
 *  - Step count is never an input here — context only, never a standalone
 *    reason to change the plan.
 */

data class RecoveryDay(
    val sleepMinutes: Int?,
    val restingHeartRate: Int?,
)

object ReadinessCalculator {
    private const val MIN_VALID_DAYS = 7
    private const val LOOKBACK_DAYS = 14

    fun calculate(today: RecoveryDay, history: List<RecoveryDay>): ReadinessLevel {
        val validSleep = history.mapNotNull { it.sleepMinutes }.takeLast(LOOKBACK_DAYS)
        val validRhr = history.mapNotNull { it.restingHeartRate }.takeLast(LOOKBACK_DAYS)
        if (validSleep.size < MIN_VALID_DAYS || validRhr.size < MIN_VALID_DAYS ||
            today.sleepMinutes == null || today.restingHeartRate == null
        ) {
            return ReadinessLevel.UNKNOWN
        }

        val sleepBaseline = median(validSleep)
        val rhrBaseline = median(validRhr)
        val lowSleep = today.sleepMinutes < sleepBaseline * 0.8
        val elevatedRhr = today.restingHeartRate >= rhrBaseline + 5
        return when {
            lowSleep && elevatedRhr -> ReadinessLevel.RED
            lowSleep || elevatedRhr -> ReadinessLevel.YELLOW
            else -> ReadinessLevel.GREEN
        }
    }

    private fun median(values: List<Int>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle].toDouble()
    }
}
