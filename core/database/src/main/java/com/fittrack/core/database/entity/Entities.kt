package com.fittrack.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities. These never leak outside core/database — mappers in
 * [com.fittrack.core.database.mapper] translate to/from the pure Kotlin
 * types in core/model.
 *
 * Tombstone convention: entities that a user can delete carry a nullable
 * `deletedAt` column instead of being hard-deleted, so sync can later
 * propagate the deletion. Queries filter `deletedAt IS NULL` by default.
 */

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: String = "me",
    val goal: String,
    val experience: String,
    val equipmentCsv: String,
    val schedule: String,
    val constraints: String,
    val preferences: String,
    val approved: Boolean,
    val version: Int = 1,
    val updatedAt: Long,
)

@Entity(tableName = "exercises", indices = [Index("movementPattern"), Index("equipment")])
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val movementPattern: String,
    val muscleRole: String,
    val equipment: String,
    val active: Boolean = true,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(tableName = "workout_plans", indices = [Index("active")])
data class WorkoutPlanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val dayLabel: String,
    val scheduledDayOfWeek: Int?,
    val scheduledMinuteOfDay: Int?,
    val version: Int = 1,
    val active: Boolean = true,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "plan_exercises",
    foreignKeys = [
        ForeignKey(WorkoutPlanEntity::class, ["id"], ["planId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exerciseId"]),
    ],
    indices = [Index("planId"), Index("exerciseId")],
)
data class PlanExerciseEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val exerciseId: String,
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
    val updatedAt: Long,
)

@Entity(tableName = "workout_sessions", indices = [Index("planId"), Index("status")])
data class WorkoutSessionEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val planNameSnapshot: String,
    val planVersionSnapshot: Int,
    val dayLabelSnapshot: String,
    /** JSON-serialized List<PlanExerciseSnapshot> captured at start; see
     * [com.fittrack.core.database.mapper.PlanSnapshotJson]. */
    val planSnapshotJson: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val note: String = "",
    val fitbitSyncStatus: String = "NOT_REQUESTED",
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "exercise_sessions",
    foreignKeys = [ForeignKey(WorkoutSessionEntity::class, ["id"], ["workoutSessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workoutSessionId"), Index("exerciseId"), Index("planExerciseId")],
)
data class ExerciseSessionEntity(
    @PrimaryKey val id: String,
    val workoutSessionId: String,
    val planExerciseId: String,
    val exerciseId: String,
    val originalExerciseId: String,
    val exerciseNameSnapshot: String,
    val position: Int,
    val restSecondsSnapshot: Int,
    val status: String = "PENDING",
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val skippedAt: Long? = null,
    val skipReason: String = "",
    val note: String = "",
    val updatedAt: Long,
)

@Entity(
    tableName = "set_logs",
    foreignKeys = [ForeignKey(ExerciseSessionEntity::class, ["id"], ["exerciseSessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("exerciseSessionId")],
)
data class SetLogEntity(
    @PrimaryKey val id: String,
    val exerciseSessionId: String,
    val position: Int,
    val setType: String,
    val targetRepMin: Int,
    val targetRepMax: Int,
    val targetRir: Int?,
    val targetWeightKg: Double,
    val actualReps: Int? = null,
    val actualWeightKg: Double? = null,
    val actualRir: Int? = null,
    val pain: Boolean = false,
    val techniqueIssue: Boolean = false,
    val concerningSymptom: Boolean = false,
    val completedAt: Long? = null,
    val updatedAt: Long,
)

@Entity(tableName = "health_daily")
data class HealthDailyEntity(
    @PrimaryKey val date: String,
    val sleepMinutes: Int?,
    val restingHeartRate: Int?,
    val steps: Int?,
    val source: String = "FITBIT",
    val syncedAt: Long,
)

@Entity(tableName = "coach_suggestions", indices = [Index("status"), Index("targetId")])
data class CoachSuggestionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val targetId: String,
    val beforeValue: String,
    val afterValue: String,
    val reasonCode: String,
    val explanation: String,
    val signalsJson: String = "[]",
    val citationsJson: String = "[]",
    val confidence: String,
    val status: String = "PENDING",
    val decisionReason: String = "",
    val createdAt: Long,
    val decidedAt: Long? = null,
)

@Entity(tableName = "cardio_activities", indices = [Index("startedAt")])
data class CardioActivityEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val kind: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationSeconds: Int,
    val distanceMeters: Double?,
    val steps: Int?,
    val averageHeartRate: Int?,
    val syncedAt: Long,
)

@Entity(
    tableName = "heart_rate_samples",
    primaryKeys = ["workoutSessionId", "sampledAt"],
    indices = [Index("sampledAt")],
)
data class HeartRateSampleEntity(
    val workoutSessionId: String,
    val sampledAt: Long,
    val bpm: Int,
)

@Entity(tableName = "exercise_hr_summary", primaryKeys = ["exerciseSessionId"])
data class ExerciseHrSummaryEntity(
    val exerciseSessionId: String,
    val averageBpm: Int,
    val maximumBpm: Int,
    val recoveryDeltaBpm: Int?,
    val sampleCount: Int,
    val computedAt: Long,
)

@Entity(tableName = "sync_outbox", indices = [Index("status"), Index(value = ["entityType", "entityId"])])
data class SyncOutboxEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payloadJson: String,
    val version: Int,
    val status: String = "PENDING",
    val attemptCount: Int = 0,
    val createdAt: Long,
    val lastAttemptAt: Long? = null,
)
