package com.fittrack.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FitTrackDao {
    @Query("SELECT * FROM user_profile WHERE id = 'me' LIMIT 1")
    fun observeProfile(): Flow<UserProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: UserProfileEntity)

    @Query("SELECT * FROM workout_plans WHERE active = 1 ORDER BY name")
    fun observePlans(): Flow<List<WorkoutPlanEntity>>

    @Query("SELECT * FROM workout_plans WHERE id = :id LIMIT 1")
    suspend fun getPlan(id: String): WorkoutPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlans(items: List<WorkoutPlanEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExercises(items: List<ExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlanExercises(items: List<PlanExerciseEntity>)

    @Query("SELECT COUNT(*) FROM workout_plans")
    suspend fun planCount(): Int

    @Query("SELECT * FROM plan_exercises WHERE planId = :planId ORDER BY position")
    suspend fun getPlanExercises(planId: String): List<PlanExerciseEntity>

    @Query("SELECT * FROM plan_exercises WHERE id = :id LIMIT 1")
    suspend fun getPlanExercise(id: String): PlanExerciseEntity?

    @Update suspend fun updatePlanExercise(item: PlanExerciseEntity)

    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    suspend fun getExercises(ids: List<String>): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE movementPattern = :pattern AND equipment IN (:equipment) AND id != :excludeId AND active = 1 LIMIT 3")
    suspend fun findSwapCandidates(pattern: String, equipment: List<String>, excludeId: String): List<ExerciseEntity>

    @Query("SELECT * FROM workout_sessions WHERE status = 'ACTIVE' AND deletedAt IS NULL LIMIT 1")
    fun observeActiveWorkout(): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_sessions WHERE status = 'ACTIVE' AND deletedAt IS NULL LIMIT 1")
    suspend fun getActiveWorkout(): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE deletedAt IS NULL ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentWorkouts(limit: Int = 30): Flow<List<WorkoutSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkout(item: WorkoutSessionEntity)

    @Update suspend fun updateWorkout(item: WorkoutSessionEntity)

    @Query("SELECT * FROM exercise_sessions WHERE workoutSessionId = :workoutId ORDER BY position")
    fun observeExerciseSessions(workoutId: String): Flow<List<ExerciseSessionEntity>>

    @Query("SELECT * FROM exercise_sessions WHERE workoutSessionId = :workoutId ORDER BY position")
    suspend fun getExerciseSessions(workoutId: String): List<ExerciseSessionEntity>

    @Query("SELECT * FROM exercise_sessions WHERE id = :id LIMIT 1")
    suspend fun getExerciseSession(id: String): ExerciseSessionEntity?

    @Query("SELECT * FROM exercises WHERE id = :id LIMIT 1")
    suspend fun getExercise(id: String): ExerciseEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExerciseSessions(items: List<ExerciseSessionEntity>)

    @Update suspend fun updateExerciseSession(item: ExerciseSessionEntity)

    @Query("SELECT * FROM set_logs WHERE exerciseSessionId = :exerciseSessionId ORDER BY position")
    fun observeSetLogs(exerciseSessionId: String): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs WHERE exerciseSessionId = :exerciseSessionId ORDER BY position")
    suspend fun getSetLogs(exerciseSessionId: String): List<SetLogEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSetLogs(items: List<SetLogEntity>)

    @Update suspend fun updateSetLog(item: SetLogEntity)

    @Query("SELECT * FROM health_daily ORDER BY date DESC LIMIT :limit")
    fun observeHealth(limit: Int = 15): Flow<List<HealthDailyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHealth(items: List<HealthDailyEntity>)

    @Query("SELECT * FROM coach_suggestions WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun observePendingSuggestions(): Flow<List<CoachSuggestionEntity>>

    @Query("SELECT * FROM coach_suggestions WHERE id = :id LIMIT 1")
    suspend fun getSuggestion(id: String): CoachSuggestionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSuggestion(item: CoachSuggestionEntity)

    @Update suspend fun updateSuggestion(item: CoachSuggestionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueSync(item: SyncOutboxEntity)

    @Query("SELECT * FROM sync_outbox WHERE status = 'PENDING' ORDER BY createdAt LIMIT :limit")
    suspend fun getPendingSync(limit: Int = 100): List<SyncOutboxEntity>

    @Query("UPDATE sync_outbox SET status = 'SYNCED', lastAttemptAt = :now WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, now: Long)

    @Query("DELETE FROM heart_rate_samples WHERE sampledAt < :cutoff")
    suspend fun deleteHeartRateBefore(cutoff: Long): Int
}
