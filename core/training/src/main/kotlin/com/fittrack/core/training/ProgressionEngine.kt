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
        if (workingSets.isEmpty()) return hold(input, "NO_WORKING_SETS", "Brak ukończonych serii roboczych.")

        // Safety first: pain/concerning symptoms always block progression outright.
        // The app never diagnoses the cause — it only stops and asks the user to review.
        if (workingSets.any { it.concerningSymptom || it.pain }) {
            return ProgressionRecommendation(
                ProgressionAction.STOP_AND_REVIEW,
                input.currentWeightKg,
                reasonCode = "PAIN_OR_SYMPTOM",
                explanation = "Ból lub niepokojący objaw blokuje progresję. Zatrzymaj się i oceń sytuację; aplikacja nie stawia diagnoz.",
            )
        }

        if (workingSets.any { it.techniqueIssue }) {
            return hold(input, "TECHNIQUE_ISSUE", "Najpierw ustabilizuj technikę przy obecnym ciężarze.")
        }

        val belowMinimum = workingSets.any { it.reps < input.repMin }
        if (belowMinimum) {
            return if (input.consecutiveMissedSessions >= 1) {
                ProgressionRecommendation(
                    ProgressionAction.DECREASE_LOAD,
                    max(0.0, input.currentWeightKg - input.incrementKg),
                    reasonCode = "TWO_CONSECUTIVE_MISSES",
                    explanation = "Cel nieosiągnięty w dwóch kolejnych sesjach; proponowany jeden krok w dół.",
                )
            } else {
                hold(input, "FIRST_MISS", "Pierwszy wynik poniżej zakresu — utrzymaj ciężar i obserwuj kolejną sesję.")
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
                    explanation = "Sen i tętno spoczynkowe odbiegają od wartości bazowych; zamiast zwiększać ciężar, proponowana jedna seria mniej.",
                )
                ReadinessLevel.YELLOW -> hold(input, "YELLOW_READINESS", "Jeden sygnał regeneracji odbiega od normy; wstrzymaj zwiększanie ciężaru.")
                else -> ProgressionRecommendation(
                    ProgressionAction.INCREASE_LOAD,
                    input.currentWeightKg + input.incrementKg,
                    reasonCode = "TOP_RANGE_REACHED",
                    explanation = "Wszystkie serie osiągnęły górny zakres powtórzeń z wymaganym zapasem RIR; proponowany jeden krok w górę.",
                )
            }
        }

        return hold(input, "PROGRESS_REPS", "Utrzymaj ciężar i najpierw zbuduj liczbę powtórzeń w docelowym zakresie.")
    }

    private fun hold(input: ProgressionInput, code: String, message: String) = ProgressionRecommendation(
        action = ProgressionAction.HOLD_LOAD,
        proposedWeightKg = input.currentWeightKg,
        reasonCode = code,
        explanation = message,
    )
}
