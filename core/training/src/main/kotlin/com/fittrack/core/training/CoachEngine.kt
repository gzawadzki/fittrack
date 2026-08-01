package com.fittrack.core.training

import com.fittrack.core.model.Citation
import com.fittrack.core.model.CoachSuggestion
import com.fittrack.core.model.Confidence
import com.fittrack.core.model.ExerciseSessionStatus
import com.fittrack.core.model.SuggestionStatus
import com.fittrack.core.model.SuggestionType
import com.fittrack.core.model.repository.HealthRepository
import com.fittrack.core.model.repository.PlanRepository
import com.fittrack.core.model.repository.WorkoutRepository
import java.util.UUID

/**
 * Orchestrates progression + readiness rules across a completed workout.
 *
 * Deliberately lives in core/training (not core/database): the dependency
 * graph in IMPLEMENTATION_PLAN.md section 2 allows core/training -> core/model
 * but not core/database -> core/training, so wiring the rule engine to real
 * data happens here, against the repository *interfaces*, while core/database
 * stays a pure persistence layer.
 *
 * Callers (feature/app, later waves) are expected to:
 *   1. workoutRepository.finishWorkout(id, note)
 *   2. val suggestions = coachEngine.generateSuggestions(id)
 *   3. workoutRepository.saveSuggestions(suggestions)   // status = PENDING
 *   4. later: workoutRepository.decideSuggestion(id, accept, reason)
 *
 * No suggestion is ever auto-applied here; PlanRepository.applySuggestion is
 * only called after an explicit user ACCEPT decision, by that later caller.
 */
class CoachEngine(
    private val planRepository: PlanRepository,
    private val workoutRepository: WorkoutRepository,
    private val healthRepository: HealthRepository,
) {
    /** Successful session = all working sets reached the top of the rep range,
     * with no pain/technique/concerning-symptom flags. Used only to derive the
     * consecutive-session counters ProgressionEngine needs when RIR is absent
     * or to detect a second consecutive miss. */
    suspend fun generateSuggestions(workoutId: String): List<CoachSuggestion> {
        val readiness = computeReadiness()
        val completedExerciseSessions = workoutRepository.getExerciseSessions(workoutId)
            .filter { it.status == ExerciseSessionStatus.COMPLETED }

        val suggestions = mutableListOf<CoachSuggestion>()
        for (session in completedExerciseSessions) {
            val plan = planRepository.getPlanExercise(session.planExerciseId) ?: continue
            val logs = workoutRepository.getSetLogs(session.id)
            val performances = logs.mapNotNull { log ->
                log.actualReps?.let { reps ->
                    SetPerformance(
                        reps = reps,
                        rir = log.actualRir,
                        isWarmup = log.planned.type == com.fittrack.core.model.SetType.WARMUP,
                        pain = log.pain,
                        techniqueIssue = log.techniqueIssue,
                        concerningSymptom = log.concerningSymptom,
                    )
                }
            }
            if (performances.isEmpty()) continue

            val history = workoutRepository.getExerciseSessionHistory(session.planExerciseId, excludeWorkoutId = workoutId, limit = 8)
            val (consecutiveSuccess, consecutiveMiss) = consecutiveStreaks(history, plan.repMin, plan.repMax, plan.targetRir, workoutRepository)

            val recommendation = ProgressionEngine.recommend(
                ProgressionInput(
                    currentWeightKg = plan.startingWeightKg,
                    incrementKg = plan.weightIncrementKg,
                    repMin = plan.repMin,
                    repMax = plan.repMax,
                    targetRir = plan.targetRir,
                    sets = performances,
                    consecutiveSuccessfulSessions = consecutiveSuccess,
                    consecutiveMissedSessions = consecutiveMiss,
                    readiness = readiness,
                )
            )

            if (recommendation.action == ProgressionAction.HOLD_LOAD || recommendation.action == ProgressionAction.STOP_AND_REVIEW) {
                // STOP_AND_REVIEW never proposes a plan change (never self-diagnoses);
                // surface it as a HOLD-typed pending suggestion so it stays visible to the user.
                if (recommendation.action == ProgressionAction.STOP_AND_REVIEW) {
                    suggestions += CoachSuggestion(
                        id = UUID.randomUUID().toString(),
                        type = SuggestionType.HOLD,
                        targetId = plan.id,
                        before = plan.startingWeightKg.toString(),
                        after = plan.startingWeightKg.toString(),
                        reasonCode = recommendation.reasonCode,
                        explanation = recommendation.explanation,
                        signals = listOfNotNull(
                            "pain".takeIf { performances.any { p -> p.pain } },
                            "concerningSymptom".takeIf { performances.any { p -> p.concerningSymptom } },
                        ),
                        citations = emptyList(),
                        confidence = Confidence.HIGH,
                        status = SuggestionStatus.PENDING,
                    )
                }
                continue
            }

            val type = if (recommendation.action == ProgressionAction.REDUCE_VOLUME) SuggestionType.VOLUME else SuggestionType.LOAD
            val before = if (type == SuggestionType.VOLUME) plan.workingSets.toString() else plan.startingWeightKg.toString()
            val after = if (type == SuggestionType.VOLUME) {
                (plan.workingSets + recommendation.proposedSetDelta).coerceAtLeast(1).toString()
            } else {
                recommendation.proposedWeightKg.toString()
            }
            val signals = mutableListOf<String>()
            if (readiness != ReadinessLevel.UNKNOWN) signals += "readiness:${readiness.name}"

            suggestions += CoachSuggestion(
                id = UUID.randomUUID().toString(),
                type = type,
                targetId = plan.id,
                before = before,
                after = after,
                reasonCode = recommendation.reasonCode,
                explanation = recommendation.explanation,
                signals = signals,
                citations = emptyList<Citation>(),
                confidence = Confidence.HIGH,
                status = SuggestionStatus.PENDING,
            )
        }
        return suggestions
    }

    private suspend fun computeReadiness(): ReadinessLevel {
        val recent = healthRepository.getRecentHealth(days = 15)
        if (recent.isEmpty()) return ReadinessLevel.UNKNOWN
        val today = recent.first()
        val history = recent.drop(1)
        return ReadinessCalculator.calculate(
            RecoveryDay(today.sleepMinutes, today.restingHeartRate),
            history.map { RecoveryDay(it.sleepMinutes, it.restingHeartRate) },
        )
    }

    /** Walks prior COMPLETED sessions (most recent first) for the same plan
     * exercise, counting how many in a row already qualify as a full success
     * (all sets at repMax, no pain/technique/symptom) or a miss (any set below
     * repMin), stopping at the first non-matching session. */
    private suspend fun consecutiveStreaks(
        history: List<com.fittrack.core.model.ExerciseSession>,
        repMin: Int,
        repMax: Int,
        targetRir: Int?,
        workoutRepository: WorkoutRepository,
    ): Pair<Int, Int> {
        var success = 0
        var miss = 0
        var successStreakBroken = false
        var missStreakBroken = false
        for (past in history) {
            val logs = workoutRepository.getSetLogs(past.id)
            val working = logs.filter { it.planned.type == com.fittrack.core.model.SetType.WORKING && it.actualReps != null }
            if (working.isEmpty()) continue

            val hadBlocker = working.any { it.pain || it.techniqueIssue || it.concerningSymptom }
            val belowMin = working.any { (it.actualReps ?: 0) < repMin }
            val allAtTop = working.all { (it.actualReps ?: 0) >= repMax }
            val rirOk = targetRir?.let { target -> working.all { (it.actualRir ?: -1) >= target } } ?: true
            val wasSuccess = !hadBlocker && allAtTop && rirOk
            val wasMiss = !hadBlocker && belowMin

            if (!successStreakBroken) {
                if (wasSuccess) success++ else successStreakBroken = true
            }
            if (!missStreakBroken) {
                if (wasMiss) miss++ else missStreakBroken = true
            }
            if (successStreakBroken && missStreakBroken) break
        }
        return success to miss
    }
}
