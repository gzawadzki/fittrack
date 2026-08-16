package com.fittrack.core.database.sync

import com.fittrack.core.database.entity.ExerciseEntity
import com.fittrack.core.database.entity.ExerciseSessionEntity
import com.fittrack.core.database.entity.PlanExerciseEntity
import com.fittrack.core.database.entity.SetLogEntity
import com.fittrack.core.database.entity.UserProfileEntity
import com.fittrack.core.database.entity.WorkoutPlanEntity
import com.fittrack.core.database.entity.WorkoutSessionEntity
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal object SyncEntityType {
    const val USER_PROFILES = "user_profiles"
    const val EXERCISES = "exercises"
    const val WORKOUT_PLANS = "workout_plans"
    const val PLAN_EXERCISES = "plan_exercises"
    const val WORKOUT_SESSIONS = "workout_sessions"
    const val EXERCISE_SESSIONS = "exercise_sessions"
    const val SET_LOGS = "set_logs"
    const val COACH_SUGGESTIONS = "coach_suggestions"

    val writable = setOf(
        USER_PROFILES,
        EXERCISES,
        WORKOUT_PLANS,
        PLAN_EXERCISES,
        WORKOUT_SESSIONS,
        EXERCISE_SESSIONS,
        SET_LOGS,
    )
    val pullable = writable + COACH_SUGGESTIONS
}

internal fun UserProfileEntity.syncData(): String = JSONObject()
    .put("goal", goal)
    .put("experience", experience)
    .put("equipment", JSONArray(equipmentCsv.split(',').map(String::trim).filter(String::isNotEmpty)))
    .put("schedule", schedule)
    .put("constraints", constraints)
    .put("preferences", preferences)
    .put("approved", approved)
    .put("profile_version", version)
    .toString()

internal fun ExerciseEntity.syncData(): String = JSONObject()
    .put("name", name)
    .put("movement_pattern", movementPattern)
    .put("muscle_role", muscleRole)
    .put("equipment", equipment)
    .put("active", active)
    .toString()

internal fun WorkoutPlanEntity.syncData(): String = JSONObject()
    .put("name", name)
    .put("day_label", dayLabel)
    .putNullable("scheduled_day_of_week", scheduledDayOfWeek)
    .putNullable("scheduled_minute_of_day", scheduledMinuteOfDay)
    .put("active", active)
    .put("plan_version", version)
    .toString()

internal fun PlanExerciseEntity.syncData(): String = JSONObject()
    .put("plan_id", planId)
    .put("exercise_id", exerciseId)
    .put("position", position)
    .put("warmup_sets", warmupSets)
    .put("working_sets", workingSets)
    .put("rep_min", repMin)
    .put("rep_max", repMax)
    .putNullable("target_rir", targetRir)
    .put("starting_weight_kg", startingWeightKg)
    .put("rest_seconds", restSeconds)
    .put("weight_increment_kg", weightIncrementKg)
    .put("notes", notes)
    .toString()

internal fun WorkoutSessionEntity.syncData(): String = JSONObject()
    .put("plan_id", planId)
    .put("plan_name_snapshot", planNameSnapshot)
    .put("plan_version_snapshot", planVersionSnapshot)
    .put("day_label_snapshot", dayLabelSnapshot)
    .put("plan_snapshot_json", JSONObject(planSnapshotJson))
    .put("status", status)
    .put("started_at", startedAt.iso())
    .putNullable("ended_at", endedAt?.iso())
    .put("note", note)
    .put("fitbit_sync_status", fitbitSyncStatus)
    .toString()

internal fun ExerciseSessionEntity.syncData(): String = JSONObject()
    .put("workout_session_id", workoutSessionId)
    .put("plan_exercise_id", planExerciseId)
    .put("exercise_id", exerciseId)
    .put("original_exercise_id", originalExerciseId)
    .put("exercise_name_snapshot", exerciseNameSnapshot)
    .put("position", position)
    .put("rest_seconds_snapshot", restSecondsSnapshot)
    .put("status", status)
    .putNullable("started_at", startedAt?.iso())
    .putNullable("ended_at", endedAt?.iso())
    .putNullable("skipped_at", skippedAt?.iso())
    .put("skip_reason", skipReason)
    .put("note", note)
    .toString()

internal fun SetLogEntity.syncData(): String = JSONObject()
    .put("exercise_session_id", exerciseSessionId)
    .put("position", position)
    .put("set_type", setType)
    .put("target_rep_min", targetRepMin)
    .put("target_rep_max", targetRepMax)
    .putNullable("target_rir", targetRir)
    .put("target_weight_kg", targetWeightKg)
    .putNullable("actual_reps", actualReps)
    .putNullable("actual_weight_kg", actualWeightKg)
    .putNullable("actual_rir", actualRir)
    .put("pain", pain)
    .put("technique_issue", techniqueIssue)
    .put("concerning_symptom", concerningSymptom)
    .putNullable("completed_at", completedAt?.iso())
    .put("skipped", skipped)
    .toString()

internal fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)

internal fun Long.iso(): String = Instant.ofEpochMilli(this).toString()

internal fun String.isoMillis(): Long = Instant.parse(this).toEpochMilli()

internal fun JSONObject.requiredString(name: String): String =
    getString(name).also { require(it.isNotBlank()) { "$name must not be blank" } }

internal fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)

internal fun JSONObject.nullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else getInt(name)

internal fun JSONObject.nullableDouble(name: String): Double? =
    if (!has(name) || isNull(name)) null else getDouble(name)
