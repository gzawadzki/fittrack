package com.fittrack.core.model.repository

import com.fittrack.core.model.CoachSuggestion
import com.fittrack.core.model.Exercise
import com.fittrack.core.model.ExerciseSession
import com.fittrack.core.model.HealthDay
import com.fittrack.core.model.SetLog
import com.fittrack.core.model.UserProfile
import com.fittrack.core.model.WorkoutPlan
import com.fittrack.core.model.WorkoutSession
import kotlinx.coroutines.flow.Flow

/**
 * Pure Kotlin repository contracts. No Room/Android types may appear here or
 * in any implementation's public surface — feature modules (M2/M3) code
 * against these interfaces using fakes; core/database provides the real
 * Room-backed implementations.
 */

interface ProfileRepository {
    fun observeProfile(): Flow<UserProfile?>
    suspend fun getProfile(): UserProfile?

    /** Upserts the single local profile. Callers must set [UserProfile.approved]
     * explicitly — this repository never infers consent. */
    suspend fun saveProfile(profile: UserProfile)
}

interface PlanRepository {
    fun observePlans(): Flow<List<WorkoutPlan>>
    suspend fun getPlan(planId: String): WorkoutPlan?
    suspend fun getPlanExercise(planExerciseId: String): com.fittrack.core.model.PlanExercise?

    /** Creates a new plan (version 1). IDs are pre-populated by the caller
     * (client-generated UUIDs) and are honored as-is, making this idempotent
     * for retried calls with the same id. */
    suspend fun createPlan(plan: WorkoutPlan)

    /** Persists edits to an existing plan and bumps [WorkoutPlan.version]. */
    suspend fun updatePlan(plan: WorkoutPlan)

    /** Soft-deletes (tombstones) a plan; it stops appearing in [observePlans]
     * but is retained locally for sync purposes. */
    suspend fun deletePlan(planId: String)

    /** Applies an accepted [CoachSuggestion] (LOAD/VOLUME) onto the target
     * plan exercise. No-op if the suggestion's targetId does not resolve. */
    suspend fun applySuggestion(suggestion: CoachSuggestion)

    /** Parses a versioned plan JSON document (see docs/plan-schema in this
     * module's HANDOFF) and persists it as a new plan. Exercises referenced
     * by name that don't already exist in the library are created. */
    suspend fun importPlanFromJson(json: String): WorkoutPlan

    /** Seeds the built-in exercise library from JSON if the library is empty. */
    suspend fun importExerciseLibraryFromJson(json: String)

    suspend fun findSwapCandidates(exerciseId: String, availableEquipment: Set<String>, limit: Int = 3): List<Exercise>

    fun observeExerciseLibrary(): Flow<List<Exercise>>
    suspend fun getExercise(exerciseId: String): Exercise?

    /** Upserts a single exercise library entry — used by plan creation UIs to
     * register a brand-new custom exercise before referencing its id in a
     * [com.fittrack.core.model.PlanExercise]. */
    suspend fun upsertExercise(exercise: Exercise)
}

interface WorkoutRepository {
    fun observeActiveWorkout(): Flow<WorkoutSession?>
    suspend fun getActiveWorkout(): WorkoutSession?
    fun observeHistory(limit: Int = 30): Flow<List<WorkoutSession>>
    suspend fun getWorkout(workoutId: String): WorkoutSession?

    fun observeExerciseSessions(workoutId: String): Flow<List<ExerciseSession>>
    suspend fun getExerciseSessions(workoutId: String): List<ExerciseSession>
    fun observeSetLogs(exerciseSessionId: String): Flow<List<SetLog>>
    suspend fun getSetLogs(exerciseSessionId: String): List<SetLog>

    /** Most recent COMPLETED exercise sessions for [planExerciseId], most
     * recent first, excluding [excludeWorkoutId]. Used by core/training to
     * compute consecutive-success/consecutive-miss streaks. */
    suspend fun getExerciseSessionHistory(planExerciseId: String, excludeWorkoutId: String? = null, limit: Int = 10): List<ExerciseSession>

    /**
     * Starts a new workout from [planId]: captures an immutable plan
     * snapshot, seeds warmup/working set rows, and activates the first
     * exercise. Transactional and idempotent — if a workout is already
     * ACTIVE it is returned unchanged rather than starting a second one
     * (only one active workout at a time).
     */
    suspend fun startWorkout(planId: String): WorkoutSession

    suspend fun completeSet(
        setId: String,
        reps: Int,
        weightKg: Double,
        rir: Int?,
        pain: Boolean,
        techniqueIssue: Boolean,
        concerningSymptom: Boolean,
    ): SetLog

    suspend fun finishExercise(exerciseSessionId: String, note: String)
    suspend fun skipExercise(exerciseSessionId: String, reason: String)
    suspend fun swapExercise(exerciseSessionId: String, replacementExerciseId: String)

    /** Marks the workout COMPLETED, closes any still-open exercises as
     * SKIPPED, and queues it for Fitbit sync. Does not itself generate coach
     * suggestions — see core/training's CoachEngine, which reads history
     * through this repository and PlanRepository. */
    suspend fun finishWorkout(workoutId: String, note: String): WorkoutSession

    suspend fun cancelWorkout(workoutId: String)

    fun observePendingSuggestions(): Flow<List<CoachSuggestion>>
    suspend fun getSuggestion(id: String): CoachSuggestion?
    suspend fun saveSuggestions(suggestions: List<CoachSuggestion>)
    suspend fun decideSuggestion(id: String, accept: Boolean, reason: String = "")
}

interface HealthRepository {
    fun observeDailyHealth(limit: Int = 30): Flow<List<HealthDay>>

    /** Most recent [days] entries, most recent first (index 0 = today, if present). */
    suspend fun getRecentHealth(days: Int = 14): List<HealthDay>

    suspend fun upsertDailyHealth(days: List<HealthDay>)
}
