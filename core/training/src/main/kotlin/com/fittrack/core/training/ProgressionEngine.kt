package com.fittrack.core.training

import kotlin.math.max

/**
 * Progression/safety rules. See IMPLEMENTATION_PLAN.md section 5 — these
 * behaviors must NOT change without an ADR:
 *
 *  - Increases weight by exactly one configured increment.
 *  - Without RIR data, requires two consecutive successful sessions before increasing.
 *  - First drop below the minimum rep target = hold; a second consecutive drop
 *    allows proposing one increment lower.
 *  - Pain, a concerning symptom, or a technique problem blocks progression;
 *    the app never self-diagnoses — it only surfaces the block.
 *  - Step count is never consulted here — it is context only, handled (if at
 *    all) outside progression decisions.
 */

enum class ProgressionAction { INCREASE_LOAD, HOLD_LOAD, DECREASE_LOAD, REDUCE_VOLUME, STOP_AND_REVIEW }

enum class ReadinessLevel { UNKNOWN, GREEN, YELLOW, RED }

data class SetPerformance(
    val reps: Int,
    val rir: Int? = null,
    val isWarmup: Boolean = false,
    val pain: Boolean = false,
    val techniqueIssue: Boolean = false,
    val concerningSymptom: Boolean = false,
)

data class ProgressionInput(
    val currentWeightKg: Double,
    val incrementKg: Double,
    val repMin: Int,
    val repMax: Int,
    val targetRir: Int?,
    val sets: List<SetPerformance>,
    /** Consecutive prior sessions (not counting this one) that already
     * qualified as a full success. Only consulted when [targetRir] is null. */
    val consecutiveSuccessfulSessions: Int = 0,
    /** Consecutive prior sessions (not counting this one) that already
     * missed the rep-minimum target. */
    val consecutiveMissedSessions: Int = 0,
    val readiness: ReadinessLevel = ReadinessLevel.UNKNOWN,
)

data class ProgressionRecommendation(
    val action: ProgressionAction,
    val proposedWeightKg: Double,
    val proposedSetDelta: Int = 0,
    val reasonCode: String,
    val explanation: String,
)

object ProgressionEngine {
    fun recommend(input: ProgressionInput): ProgressionRecommendation {
        val workingSets = input.sets.filterNot { it.isWarmup }
        if (workingSets.isEmpty()) return hold(input, "NO_WORKING_SETS", "No completed working sets.")

        // Safety first: pain/concerning symptoms always block progression outright.
        // The app never diagnoses the cause — it only stops and asks the user to review.
        if (workingSets.any { it.concerningSymptom || it.pain }) {
            return ProgressionRecommendation(
                ProgressionAction.STOP_AND_REVIEW,
                input.currentWeightKg,
                reasonCode = "PAIN_OR_SYMPTOM",
                explanation = "Pain or a concerning symptom blocks progression. Stop and review the situation; the app does not diagnose.",
            )
        }

        if (workingSets.any { it.techniqueIssue }) {
            return hold(input, "TECHNIQUE_ISSUE", "Stabilize technique at the current weight before progressing.")
        }

        val belowMinimum = workingSets.any { it.reps < input.repMin }
        if (belowMinimum) {
            return if (input.consecutiveMissedSessions >= 1) {
                ProgressionRecommendation(
                    ProgressionAction.DECREASE_LOAD,
                    max(0.0, input.currentWeightKg - input.incrementKg),
                    reasonCode = "TWO_CONSECUTIVE_MISSES",
                    explanation = "The target was missed in two consecutive sessions; one increment lower is proposed.",
                )
            } else {
                hold(input, "FIRST_MISS", "First result below range — hold weight and watch the next session.")
            }
        }

        val allAtTop = workingSets.all { it.reps >= input.repMax }
        val rirSupportsIncrease = input.targetRir?.let { target ->
            workingSets.all { set -> set.rir != null && set.rir >= target }
        } ?: (input.consecutiveSuccessfulSessions >= 1)

        if (allAtTop && rirSupportsIncrease) {
            return when (input.readiness) {
                ReadinessLevel.RED -> ProgressionRecommendation(
                    ProgressionAction.REDUCE_VOLUME,
                    input.currentWeightKg,
                    proposedSetDelta = -1,
                    reasonCode = "RED_READINESS",
                    explanation = "Sleep and resting heart rate both deviate from baseline; one fewer set is proposed instead of a load increase.",
                )
                ReadinessLevel.YELLOW -> hold(input, "YELLOW_READINESS", "One recovery signal deviates from baseline; hold the weight increase.")
                else -> ProgressionRecommendation(
                    ProgressionAction.INCREASE_LOAD,
                    input.currentWeightKg + input.incrementKg,
                    reasonCode = "TOP_RANGE_REACHED",
                    explanation = "All sets reached the top of the rep range with the required RIR buffer; one increment up is proposed.",
                )
            }
        }

        return hold(input, "PROGRESS_REPS", "Hold the weight and work up through the rep range first.")
    }

    private fun hold(input: ProgressionInput, code: String, message: String) = ProgressionRecommendation(
        action = ProgressionAction.HOLD_LOAD,
        proposedWeightKg = input.currentWeightKg,
        reasonCode = code,
        explanation = message,
    )
}
