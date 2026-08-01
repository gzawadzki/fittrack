package com.fittrack.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
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
import com.fittrack.core.database.entity.SyncStateEntity
import com.fittrack.core.database.entity.UserProfileEntity
import com.fittrack.core.database.entity.WorkoutPlanEntity
import com.fittrack.core.database.entity.WorkoutSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FitTrackDao {
    // ---- profile ----------------------------------------------------
    @Query("SELECT * FROM user_profile WHERE deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    fun observeProfile(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getProfile(): UserProfileEntity?

    @Query("SELECT * FROM user_profile WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: String): UserProfileEntity?

    @Query("SELECT * FROM user_profile WHERE serverVersion IS NULL AND deletedAt IS NULL")
    suspend fun getUnsyncedProfiles(): List<UserProfileEntity>

    @Upsert
    suspend fun upsertProfile(profile: UserProfileEntity)

    @Query("UPDATE user_profile SET serverVersion = :version WHERE id = :id")
    suspend fun updateProfileServerVersion(id: String, version: Int)

    // ---- plans --------------------------------------------------------
    @Query("SELECT * FROM workout_plans WHERE active = 1 AND deletedAt IS NULL ORDER BY name")
    fun observePlans(): Flow<List<WorkoutPlanEntity>>

    @Query("SELECT * FROM workout_plans WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getPlan(id: String): WorkoutPlanEntity?

    @Query("SELECT * FROM workout_plans WHERE id = :id LIMIT 1")
    suspend fun getPlanIncludingDeleted(id: String): WorkoutPlanEntity?

    @Upsert
    suspend fun upsertPlans(items: List<WorkoutPlanEntity>)

    @Query("UPDATE workout_plans SET deletedAt = :now, active = 0, updatedAt = :now WHERE id = :id")
    suspend fun tombstonePlan(id: String, now: Long)

    @Upsert
    suspend fun upsertExercises(items: List<ExerciseEntity>)

    @Query("UPDATE exercises SET serverVersion = :version WHERE id = :id")
    suspend fun updateExerciseServerVersion(id: String, version: Int)

    @Query("UPDATE workout_plans SET serverVersion = :version WHERE id = :id")
    suspend fun updatePlanServerVersion(id: String, version: Int)

    @Upsert
    suspend fun upsertPlanExercises(items: List<PlanExerciseEntity>)

    @Query("UPDATE plan_exercises SET serverVersion = :version WHERE id = :id")
    suspend fun updatePlanExerciseServerVersion(id: String, version: Int)

    @Query("DELETE FROM plan_exercises WHERE planId = :planId")
    suspend fun deletePlanExercisesForPlan(planId: String)

    @Query("SELECT * FROM exercises WHERE deletedAt IS NULL ORDER BY name")
    fun observeExerciseLibrary(): Flow<List<ExerciseEntity>>

    @Query("SELECT COUNT(*) FROM workout_plans WHERE deletedAt IS NULL")
    suspend fun planCount(): Int

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun exerciseCount(): Int

    @Query("SELECT * FROM plan_exercises WHERE planId = :planId AND deletedAt IS NULL ORDER BY position")
    suspend fun getPlanExercises(planId: String): List<PlanExerciseEntity>

    @Query("SELECT * FROM plan_exercises WHERE planId = :planId ORDER BY position")
    suspend fun getAllPlanExercises(planId: String): List<PlanExerciseEntity>

    @Query("SELECT * FROM plan_exercises WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getPlanExercise(id: String): PlanExerciseEntity?

    @Query("SELECT * FROM plan_exercises WHERE id = :id LIMIT 1")
    suspend fun getPlanExerciseIncludingDeleted(id: String): PlanExerciseEntity?

    @Update suspend fun updatePlanExercise(item: PlanExerciseEntity)

    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    suspend fun getExercises(ids: List<String>): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE id = :id LIMIT 1")
    suspend fun getExercise(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE serverVersion IS NULL AND deletedAt IS NULL")
    suspend fun getUnsyncedExercises(): List<ExerciseEntity>

    @Query("SELECT * FROM workout_plans WHERE serverVersion IS NULL AND deletedAt IS NULL")
    suspend fun getUnsyncedPlans(): List<WorkoutPlanEntity>

    @Query("SELECT * FROM plan_exercises WHERE serverVersion IS NULL AND deletedAt IS NULL")
    suspend fun getUnsyncedPlanExercises(): List<PlanExerciseEntity>

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

    @Query("SELECT * FROM workout_sessions WHERE status = 'ACTIVE' AND deletedAt IS NULL ORDER BY startedAt DESC, id DESC")
    suspend fun getActiveWorkouts(): List<WorkoutSessionEntity>

    @Query("SELECT * FROM workout_sessions WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getWorkout(id: String): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE id = :id LIMIT 1")
    suspend fun getWorkoutIncludingDeleted(id: String): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE serverVersion IS NULL AND deletedAt IS NULL")
    suspend fun getUnsyncedWorkouts(): List<WorkoutSessionEntity>

    @Query("SELECT * FROM workout_sessions WHERE deletedAt IS NULL ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecentWorkouts(limit: Int): Flow<List<WorkoutSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkout(item: WorkoutSessionEntity): Long

    @Upsert
    suspend fun upsertWorkout(item: WorkoutSessionEntity)

    @Query("UPDATE workout_sessions SET serverVersion = :version WHERE id = :id")
    suspend fun updateWorkoutServerVersion(id: String, version: Int)

    @Update suspend fun updateWorkout(item: WorkoutSessionEntity)

    @Query("UPDATE workout_sessions SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun tombstoneWorkout(id: String, now: Long)

    @Query("SELECT * FROM exercise_sessions WHERE workoutSessionId = :workoutId AND deletedAt IS NULL ORDER BY position")
    fun observeExerciseSessions(workoutId: String): Flow<List<ExerciseSessionEntity>>

    @Query("SELECT * FROM exercise_sessions WHERE workoutSessionId = :workoutId AND deletedAt IS NULL ORDER BY position")
    suspend fun getExerciseSessions(workoutId: String): List<ExerciseSessionEntity>

    @Query("SELECT * FROM exercise_sessions WHERE id = :id LIMIT 1")
    suspend fun getExerciseSession(id: String): ExerciseSessionEntity?

    @Query("SELECT * FROM exercise_sessions WHERE serverVersion IS NULL AND deletedAt IS NULL")
    suspend fun getUnsyncedExerciseSessions(): List<ExerciseSessionEntity>

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

    @Upsert
    suspend fun upsertExerciseSession(item: ExerciseSessionEntity)

    @Query("UPDATE exercise_sessions SET serverVersion = :version WHERE id = :id")
    suspend fun updateExerciseSessionServerVersion(id: String, version: Int)

    @Update suspend fun updateExerciseSession(item: ExerciseSessionEntity)

    @Query("SELECT * FROM set_logs WHERE exerciseSessionId = :exerciseSessionId AND deletedAt IS NULL ORDER BY position")
    fun observeSetLogs(exerciseSessionId: String): Flow<List<SetLogEntity>>

    @Query("SELECT * FROM set_logs WHERE exerciseSessionId = :exerciseSessionId AND deletedAt IS NULL ORDER BY position")
    suspend fun getSetLogs(exerciseSessionId: String): List<SetLogEntity>

    @Query("SELECT * FROM set_logs WHERE id = :id LIMIT 1")
    suspend fun getSetLog(id: String): SetLogEntity?

    @Query("SELECT * FROM set_logs WHERE serverVersion IS NULL AND deletedAt IS NULL")
    suspend fun getUnsyncedSetLogs(): List<SetLogEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSetLogs(items: List<SetLogEntity>): List<Long>

    @Upsert
    suspend fun upsertSetLog(item: SetLogEntity)

    @Query("UPDATE set_logs SET serverVersion = :version WHERE id = :id")
    suspend fun updateSetLogServerVersion(id: String, version: Int)

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

    @Query("SELECT * FROM cardio_activities ORDER BY startedAt DESC")
    fun observeCardioActivities(): Flow<List<CardioActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHeartRateSamples(items: List<HeartRateSampleEntity>)

    @Query(
        "SELECT * FROM heart_rate_samples WHERE (:workoutSessionId IS NULL OR workoutSessionId = :workoutSessionId) " +
            "ORDER BY sampledAt",
    )
    fun observeHeartRateSamples(workoutSessionId: String?): Flow<List<HeartRateSampleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExerciseHrSummary(item: ExerciseHrSummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExerciseHrSummaries(items: List<ExerciseHrSummaryEntity>)

    @Query(
        "SELECT hrs.* FROM exercise_hr_summary hrs " +
            "JOIN exercise_sessions es ON es.id = hrs.exerciseSessionId " +
            "WHERE (:workoutSessionId IS NULL OR es.workoutSessionId = :workoutSessionId) " +
            "AND es.deletedAt IS NULL ORDER BY es.position",
    )
    fun observeExerciseHrSummaries(workoutSessionId: String?): Flow<List<ExerciseHrSummaryEntity>>

    @Query(
        "UPDATE workout_sessions SET fitbitSyncStatus = :status, updatedAt = :updatedAt " +
            "WHERE id = :workoutSessionId AND deletedAt IS NULL",
    )
    suspend fun updateWorkoutFitbitSyncStatus(workoutSessionId: String, status: String, updatedAt: Long): Int

    @Query("DELETE FROM heart_rate_samples WHERE sampledAt < :cutoff")
    suspend fun deleteHeartRateBefore(cutoff: Long): Int

    // ---- coach suggestions ------------------------------------------------
    @Query("SELECT * FROM coach_suggestions WHERE status = 'PENDING' AND deletedAt IS NULL ORDER BY createdAt DESC")
    fun observePendingSuggestions(): Flow<List<CoachSuggestionEntity>>

    @Query("SELECT * FROM coach_suggestions WHERE id = :id LIMIT 1")
    suspend fun getSuggestion(id: String): CoachSuggestionEntity?

    @Upsert
    suspend fun upsertSuggestion(item: CoachSuggestionEntity)

    @Upsert
    suspend fun upsertSuggestions(items: List<CoachSuggestionEntity>)

    @Update suspend fun updateSuggestion(item: CoachSuggestionEntity)

    @Query("UPDATE coach_suggestions SET serverVersion = :version WHERE id = :id")
    suspend fun updateSuggestionServerVersion(id: String, version: Int)

    // ---- outbox -----------------------------------------------------------
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueSync(item: SyncOutboxEntity)

    @Update
    suspend fun updateSync(item: SyncOutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun deleteSync(id: String)

    @Query("SELECT * FROM sync_outbox WHERE entityType = :entityType AND entityId = :entityId AND status IN ('PENDING', 'WAITING_REBASE') AND activeBatchId IS NULL ORDER BY createdAt LIMIT 1")
    suspend fun getMutablePendingSync(entityType: String, entityId: String): SyncOutboxEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE entityType = :entityType AND entityId = :entityId AND status = 'PENDING' AND activeBatchId IS NOT NULL)")
    suspend fun hasActiveSync(entityType: String, entityId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE entityType = :entityType AND entityId = :entityId AND status = 'PENDING')")
    suspend fun hasPendingSync(entityType: String, entityId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE entityType = :entityType AND entityId = :entityId)")
    suspend fun hasAnySync(entityType: String, entityId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE entityType = :entityType AND entityId = :entityId AND status = 'SYNCED')")
    suspend fun hasSyncedSync(entityType: String, entityId: String): Boolean

    @Query(
        "SELECT * FROM sync_outbox WHERE status = 'PENDING' AND activeBatchId IS NULL " +
            "AND (operation = 'CREATE' OR baseVersion IS NOT NULL) ORDER BY " +
            "CASE WHEN operation = 'DELETE' THEN 1 ELSE 0 END, " +
            "CASE entityType " +
            "WHEN 'user_profiles' THEN 0 WHEN 'exercises' THEN 10 WHEN 'workout_plans' THEN 20 " +
            "WHEN 'plan_exercises' THEN 30 WHEN 'workout_sessions' THEN 40 " +
            "WHEN 'exercise_sessions' THEN 50 WHEN 'set_logs' THEN 60 ELSE 70 END * " +
            "CASE WHEN operation = 'DELETE' THEN -1 ELSE 1 END, createdAt, id LIMIT :limit",
    )
    suspend fun getPendingSync(limit: Int = 100): List<SyncOutboxEntity>

    @Query(
        "SELECT * FROM sync_outbox WHERE activeBatchId = :batchId ORDER BY " +
            "CASE WHEN operation = 'DELETE' THEN 1 ELSE 0 END, " +
            "CASE entityType " +
            "WHEN 'user_profiles' THEN 0 WHEN 'exercises' THEN 10 WHEN 'workout_plans' THEN 20 " +
            "WHEN 'plan_exercises' THEN 30 WHEN 'workout_sessions' THEN 40 " +
            "WHEN 'exercise_sessions' THEN 50 WHEN 'set_logs' THEN 60 ELSE 70 END * " +
            "CASE WHEN operation = 'DELETE' THEN -1 ELSE 1 END, createdAt, id",
    )
    suspend fun getSyncForBatch(batchId: String): List<SyncOutboxEntity>

    @Query("UPDATE sync_outbox SET activeBatchId = :batchId WHERE id IN (:ids)")
    suspend fun assignSyncBatch(ids: List<String>, batchId: String)

    @Query("UPDATE sync_outbox SET status = 'SYNCED', activeBatchId = NULL, lastAttemptAt = :now WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, now: Long)

    @Query("UPDATE sync_outbox SET activeBatchId = NULL WHERE activeBatchId = :batchId AND id NOT IN (:ackedIds)")
    suspend fun releaseUnacked(batchId: String, ackedIds: List<String>)

    @Query("UPDATE sync_outbox SET activeBatchId = NULL WHERE activeBatchId = :batchId")
    suspend fun releaseBatch(batchId: String)

    @Query("UPDATE sync_outbox SET attemptCount = attemptCount + 1, lastAttemptAt = :now WHERE activeBatchId = :batchId")
    suspend fun recordBatchAttempt(batchId: String, now: Long)

    @Query("UPDATE sync_outbox SET status = 'WAITING_REBASE', activeBatchId = NULL, attemptCount = attemptCount + 1, lastAttemptAt = :now WHERE activeBatchId = :batchId")
    suspend fun deferBatchForConflict(batchId: String, now: Long)

    @Query("SELECT * FROM sync_outbox WHERE entityType = :entityType AND entityId = :entityId AND status IN ('PENDING', 'WAITING_REBASE') AND activeBatchId IS NULL ORDER BY createdAt")
    suspend fun getPendingSyncForEntity(entityType: String, entityId: String): List<SyncOutboxEntity>

    @Query("UPDATE sync_outbox SET operation = CASE WHEN operation = 'DELETE' THEN 'DELETE' ELSE 'UPDATE' END, baseVersion = :serverVersion, status = 'PENDING' WHERE entityType = :entityType AND entityId = :entityId AND status IN ('PENDING', 'WAITING_REBASE') AND activeBatchId IS NULL")
    suspend fun rebasePendingSync(entityType: String, entityId: String, serverVersion: Int)

    @Query("UPDATE sync_outbox SET status = 'PENDING' WHERE status = 'WAITING_REBASE' AND activeBatchId IS NULL AND (operation = 'CREATE' OR baseVersion IS NOT NULL)")
    suspend fun reactivateRetryableWaitingSync()

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE status = 'WAITING_REBASE'")
    suspend fun waitingForRebaseCount(): Int

    @Query("SELECT * FROM sync_state WHERE singletonId = 1")
    suspend fun getSyncState(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: SyncStateEntity)
}
