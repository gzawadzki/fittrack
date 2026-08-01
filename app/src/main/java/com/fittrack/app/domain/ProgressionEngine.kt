package com.fittrack.app.domain

import kotlin.math.max

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
    val consecutiveSuccessfulSessions: Int = 0,
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

data class RecoveryDay(
    val sleepMinutes: Int?,
    val restingHeartRate: Int?,
)

object ReadinessCalculator {
    fun calculate(today: RecoveryDay, history: List<RecoveryDay>): ReadinessLevel {
        val validSleep = history.mapNotNull { it.sleepMinutes }.takeLast(14)
        val validRhr = history.mapNotNull { it.restingHeartRate }.takeLast(14)
        if (validSleep.size < 7 || validRhr.size < 7 || today.sleepMinutes == null || today.restingHeartRate == null) {
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

object ProgressionEngine {
    fun recommend(input: ProgressionInput): ProgressionRecommendation {
        val workingSets = input.sets.filterNot { it.isWarmup }
        if (workingSets.isEmpty()) return hold(input, "NO_WORKING_SETS", "Brak ukończonych serii roboczych.")

        if (workingSets.any { it.concerningSymptom || it.pain }) {
            return ProgressionRecommendation(
                ProgressionAction.STOP_AND_REVIEW,
                input.currentWeightKg,
                reasonCode = "PAIN_OR_SYMPTOM",
                explanation = "Ból lub niepokojący objaw blokuje progresję. Przerwij i oceń sytuację; trener nie stawia diagnozy.",
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
                    explanation = "Cel nie został osiągnięty w dwóch kolejnych sesjach; proponowany jest jeden krok mniej.",
                )
            } else hold(input, "FIRST_MISS", "Pierwszy wynik poniżej zakresu — utrzymaj ciężar i obserwuj kolejną sesję.")
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
                    explanation = "Sen i tętno spoczynkowe odbiegają od normy; zaproponowano jedną serię mniej zamiast progresji.",
                )
                ReadinessLevel.YELLOW -> hold(input, "YELLOW_READINESS", "Jeden sygnał regeneracji odbiega od normy; wstrzymaj wzrost ciężaru.")
                else -> ProgressionRecommendation(
                    ProgressionAction.INCREASE_LOAD,
                    input.currentWeightKg + input.incrementKg,
                    reasonCode = "TOP_RANGE_REACHED",
                    explanation = "Wszystkie serie osiągnęły górny zakres z wymaganym zapasem; proponowany jest jeden krok więcej.",
                )
            }
        }

        return hold(input, "PROGRESS_REPS", "Utrzymaj ciężar i zwiększaj powtórzenia w zadanym zakresie.")
    }

    private fun hold(input: ProgressionInput, code: String, message: String) = ProgressionRecommendation(
        action = ProgressionAction.HOLD_LOAD,
        proposedWeightKg = input.currentWeightKg,
        reasonCode = code,
        explanation = message,
    )
}

