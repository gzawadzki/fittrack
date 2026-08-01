package com.fittrack.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fittrack.core.database.entity.CardioActivityEntity
import com.fittrack.core.database.entity.CoachSuggestionEntity
import com.fittrack.core.database.entity.ExerciseEntity
import com.fittrack.core.database.entity.ExerciseHrSummaryEntity
import com.fittrack.core.database.entity.ExerciseSessionEntity
import com.fittrack.core.database.entity.HealthDailyEntity
import com.fittrack.core.database.entity.HeartRateSampleEntity
import com.fittrack.core.database.entity.PlanExerciseEntity
import com.fittrack.core.database.entity.SetLogEntity
import com.fittrack.core.database.entity.SyncOutboxEntity
import com.fittrack.core.database.entity.UserProfileEntity
import com.fittrack.core.database.entity.WorkoutPlanEntity
import com.fittrack.core.database.entity.WorkoutSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FitTrackDao {
    // ---- profile ----------------------------------------------------
    @Query("SELECT * FROM user_profile WHERE id = 'me' LIMIT 1")
    fun observeProfile(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = 'me' LIMIT 1")
    suspend fun getProfile(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: UserProfileEntity)

    // ---- plans --------------------------------------------------------
    @Query("SELECT * FROM workout_plans WHERE active = 1 AND deletedAt IS NULL ORDER BY name")
    fun observePlans(): Flow<List<WorkoutPlanEntity>>

    @Query("SELECT * FROM workout_plans WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getPlan(id: String): WorkoutPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlans(items: List<WorkoutPlanEntity>)

    @Query("UPDATE workout_plans SET deletedAt = :now, active = 0, updatedAt = :now WHERE id = :id")
    suspend fun tombstonePlan(id: String, now: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExercises(items: List<ExerciseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlanExercises(items: List<PlanExerciseEntity>)

    @Query("DELETE FROM plan_exercises WHERE planId = :planId")
    suspend fun deletePlanExercisesForPlan(planId: String)

    @Query("SELECT * FROM exercises WHERE deletedAt IS NULL ORDER BY name")
    fun observeExerciseLibrary(): Flow<List<ExerciseEntity>>

    @Query("SELECT COUNT(*) FROM workout_plans WHERE deletedAt IS NULL")
    suspend fun planCount(): Int

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun exerciseCount(): Int

    @Query("SELECT * FROM plan_exercises WHERE planId = :planId ORDER BY position")
    suspend fun getPlanExercises(planId: String): List<PlanExerciseEntity>

    @Query("SELECT * FROM plan_exercises WHERE id = :id LIMIT 1")
    suspend fun getPlanExercise(id: String): PlanExerciseEntity?

    @Update suspend fun updatePlanExercise(item: PlanExerciseEntity)

    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    suspend fun getExercises(ids: List<String>): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE id = :id LIMIT 1")
    suspend fun getExercise(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE name = :name AND deletedAt IS NULL LIMIT 1")
    suspend fun findExerciseByName(name: String): ExerciseEntity?

    @Query(
        "SELECT * FROM exercises WHERE movementPattern = :pattern AND equipment IN (:equipment) " +
            "AND id != :excludeId AND active = 1 AND deletedAt IS NULL LIMIT :limit",
    )
    suspend fun findSwapCandidates(pattern: String, equipment: List<String>, excludeId: String, limit: Int): List<ExerciseEntity>

    // ---- workouts -------------------------------------------------------
    @Query("SELECT * FROM workout_sessions WHERE status = 'ACTIVE' AND deletedAt IS NULL LIMIT 1")
    fun observeActiveWorkout(): Flow<WorkoutSessionEntity?>

    @Query("SELECT * FROM workout_sessions WHERE status = 'ACTIVE' AND deletedAt IS NULL LIMIT 1")
    suspend fun getActiveWorkout(): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getWorkout(id: String): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE deletedAt IS NULL ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentWorkouts(limit: Int): Flow<List<WorkoutSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkout(item: WorkoutSessionEntity): Long

    @Update suspend fun updateWorkout(item: WorkoutSessionEntity)

    @Query("UPDATE workout_sessions SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun tombstoneWorkout(id: String, now: Long)

    @Query("SELECT * FROM exercise_sessions WHERE workoutSessionId = :workoutId ORDER BY position")
    fun observeExerciseSessions(workoutId: String): Flow<List<ExerciseSessionEntity>>

    @Query("SELECT * FROM exercise_sessions WHERE workoutSessionId = :workoutId ORDER BY position")
    suspend fun getExerciseSessions(workoutId: String): List<ExerciseSessionEntity>

    @Query("SELECT * FROM exercise_sessions WHERE id = :id LIMIT 1")
    suspend fun getExerciseSession(id: String): ExerciseSessionEntity?

    @Query(
        "SELECT es.* FROM exercise_sessions es " +
            "JOIN workout_sessions ws ON ws.id = es.workoutSessionId " +
            "WHERE es.planExerciseId = :planExerciseId AND es.status = 'COMPLETED' " +
            "AND ws.deletedAt IS NULL AND (:excludeWorkoutId IS NULL OR es.workoutSessionId != :excludeWorkoutId) " +
            "ORDER BY ws.startedAt DESC LIMIT :limit",
    )
    suspend fun getExerciseSessionHistory(planExerciseId: String, excludeWorkoutId: String?, limit: Int): List<ExerciseSessionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExerciseSessions(items: List<ExerciseSessionEntity>): List<Long>

    @Update suspend fun updateExerciseSession(item: ExerciseSessionEntity)

    @Query("SELECT * FROM set_logs WHERE exerciseSessionId = :exerciseSessionId ORDER BY position")
    fun observeSetLogs(exerciseSessionId: String): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs WHERE exerciseSessionId = :exerciseSessionId ORDER BY position")
    suspend fun getSetLogs(exerciseSessionId: String): List<SetLogEntity>

    @Query("SELECT * FROM set_logs WHERE id = :id LIMIT 1")
    suspend fun getSetLog(id: String): SetLogEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSetLogs(items: List<SetLogEntity>): List<Long>

    @Update suspend fun updateSetLog(item: SetLogEntity)

    // ---- health ---------------------------------------------------------
    @Query("SELECT * FROM health_daily ORDER BY date DESC LIMIT :limit")
    fun observeHealth(limit: Int): Flow<List<HealthDailyEntity>>

    @Query("SELECT * FROM health_daily ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentHealth(limit: Int): List<HealthDailyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHealth(items: List<HealthDailyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCardioActivities(items: List<CardioActivityEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHeartRateSamples(items: List<HeartRateSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExerciseHrSummary(item: ExerciseHrSummaryEntity)

    @Query("DELETE FROM heart_rate_samples WHERE sampledAt < :cutoff")
    suspend fun deleteHeartRateBefore(cutoff: Long): Int

    // ---- coach suggestions ------------------------------------------------
    @Query("SELECT * FROM coach_suggestions WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun observePendingSuggestions(): Flow<List<CoachSuggestionEntity>>

    @Query("SELECT * FROM coach_suggestions WHERE id = :id LIMIT 1")
    suspend fun getSuggestion(id: String): CoachSuggestionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSuggestion(item: CoachSuggestionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSuggestions(items: List<CoachSuggestionEntity>)

    @Update suspend fun updateSuggestion(item: CoachSuggestionEntity)

    // ---- outbox -----------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueSync(item: SyncOutboxEntity)

    @Query("SELECT * FROM sync_outbox WHERE status = 'PENDING' ORDER BY createdAt LIMIT :limit")
    suspend fun getPendingSync(limit: Int = 100): List<SyncOutboxEntity>

    @Query("UPDATE sync_outbox SET status = 'SYNCED', lastAttemptAt = :now WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, now: Long)
}
