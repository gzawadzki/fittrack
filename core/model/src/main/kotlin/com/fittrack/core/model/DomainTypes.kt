package com.fittrack.core.model

import java.time.Instant

/*
 * Domain model for FitTrack Coach.
 *
 * Types marked "FROZEN" come verbatim from IMPLEMENTATION_PLAN.md section 4
 * and must not be changed here without an ADR + coordinated update of every
 * consumer (feature modules, network DTOs, Supabase schema, contract tests).
 * All other types in this file are M1-owned supporting types, free to evolve
 * as long as the frozen shapes above are preserved.
 *
 * Boundary timestamps are UTC ISO-8601 (java.time.Instant here); local
 * storage may use epoch millis. Weight is always Double kilograms. All ids
 * are client-generated UUID strings (java.util.UUID.randomUUID().toString()).
 */

// ---- FROZEN --------------------------------------------------------------

data class UserProfile(
    val id: String,
    val goal: String,
    val experience: String,
    val equipment: Set<String>,
    val schedule: String,
    val constraints: String,
    val preferences: String,
    val approved: Boolean,
    val version: Int,
)

data class PlannedSet(
    val type: SetType,
    val repMin: Int,
    val repMax: Int,
    val targetRir: Int?,
    val targetWeightKg: Double,
)

data class WorkoutSession(
    val id: String,
    val planSnapshot: WorkoutPlanSnapshot,
    val status: WorkoutStatus,
    val startedAt: Instant,
    val endedAt: Instant?,
    val note: String,
    val fitbitSyncStatus: FitbitSyncStatus,
)

data class CoachSuggestion(
    val id: String,
    val type: SuggestionType,
    val targetId: String,
    val before: String,
    val after: String,
    val reasonCode: String,
    val explanation: String,
    val signals: List<String>,
    val citations: List<Citation>,
    val confidence: Confidence,
    val status: SuggestionStatus,
)

data class Citation(
    val title: String,
    val url: String,
    val claim: String,
)

// ---- Supporting types (not frozen, M1-owned) ------------------------------

/** A movement in the exercise library. Equipment/movementPattern/muscleRole are free-form
 * uppercase tags (e.g. "BARBELL", "SQUAT", "CHEST") shared with the plan JSON schema. */
data class Exercise(
    val id: String,
    val name: String,
    val movementPattern: String,
    val muscleRole: String,
    val equipment: String,
    val active: Boolean = true,
)

/** An exercise prescription inside a [WorkoutPlan] template — editable, versioned. */
data class PlanExercise(
    val id: String,
    val exerciseId: String,
    val exerciseName: String,
    val position: Int,
    val warmupSets: Int,
    val workingSets: Int,
    val repMin: Int,
    val repMax: Int,
    val targetRir: Int?,
    val startingWeightKg: Double,
    val restSeconds: Int,
    val weightIncrementKg: Double,
    val notes: String = "",
)

/** An editable workout template/plan owned by the user. */
data class WorkoutPlan(
    val id: String,
    val name: String,
    val dayLabel: String,
    val scheduledDayOfWeek: Int?,
    val scheduledMinuteOfDay: Int?,
    val version: Int,
    val active: Boolean,
    val exercises: List<PlanExercise>,
)

/** Immutable copy of a plan's exercise prescriptions, captured the instant a
 * workout starts. This is what [WorkoutSession.planSnapshot] points to — later
 * edits to the live [WorkoutPlan] must never retroactively change a past session. */
data class WorkoutPlanSnapshot(
    val planId: String,
    val planName: String,
    val planVersion: Int,
    val dayLabel: String,
    val exercises: List<PlanExerciseSnapshot>,
)

data class PlanExerciseSnapshot(
    val planExerciseId: String,
    val exerciseId: String,
    val exerciseName: String,
    val position: Int,
    val restSeconds: Int,
    val plannedSets: List<PlannedSet>,
)

/** Live execution state of one exercise within a [WorkoutSession]. Fetched
 * separately from the session via [com.fittrack.core.model.repository.WorkoutRepository]
 * because [WorkoutSession] itself only carries the frozen snapshot. */
data class ExerciseSession(
    val id: String,
    val workoutSessionId: String,
    val planExerciseId: String,
    val exerciseId: String,
    val originalExerciseId: String,
    val exerciseName: String,
    val position: Int,
    val restSeconds: Int,
    val status: ExerciseSessionStatus,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val skippedAt: Instant?,
    val skipReason: String,
    val note: String,
)

/** A single set as actually performed (or not yet performed) by the user. */
data class SetLog(
    val id: String,
    val exerciseSessionId: String,
    val position: Int,
    val planned: PlannedSet,
    val actualReps: Int?,
    val actualWeightKg: Double?,
    val actualRir: Int?,
    val pain: Boolean,
    val techniqueIssue: Boolean,
    val concerningSymptom: Boolean,
    val completedAt: Instant?,
)

/** One day of imported wearable health data. Steps are context only — see
 * business rule in section 5, enforced by core/training, never here. */
data class HealthDay(
    val date: String, // ISO local date, yyyy-MM-dd
    val sleepMinutes: Int?,
    val restingHeartRate: Int?,
    val steps: Int?,
    val source: String,
)

/** A normalized walk/run activity imported from Fitbit. [id] is replaced by
 * the database repository with a deterministic UUID derived from [sourceId],
 * so replaying the same remote activity cannot create a duplicate. */
data class CardioActivity(
    val id: String,
    val sourceId: String,
    val kind: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val durationSeconds: Int,
    val distanceMeters: Double?,
    val steps: Int?,
    val averageHeartRate: Int?,
)

data class HeartRateSample(
    val workoutSessionId: String,
    val sampledAt: Instant,
    val bpm: Int,
)

/** Nullable averages/maxima are accepted at the import boundary because
 * Fitbit can return a valid no-data aggregate. Repositories expose only
 * summaries where [hasData] is true. */
data class ExerciseHeartRateSummary(
    val exerciseSessionId: String,
    val averageBpm: Int?,
    val maximumBpm: Int?,
    val recoveryDeltaBpm: Int?,
    val sampleCount: Int,
) {
    val hasData: Boolean
        get() = sampleCount > 0 && averageBpm != null && maximumBpm != null
}

data class FitbitDataPayload(
    val workoutSessionId: String? = null,
    val workoutSyncStatus: FitbitSyncStatus? = null,
    val dailyHealth: List<HealthDay> = emptyList(),
    val cardioActivities: List<CardioActivity> = emptyList(),
    val heartRateSamples: List<HeartRateSample> = emptyList(),
    val exerciseHeartRateSummaries: List<ExerciseHeartRateSummary> = emptyList(),
    val syncedAt: Instant,
)
