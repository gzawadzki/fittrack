package com.fittrack.core.database.mapper

import com.fittrack.core.database.entity.CoachSuggestionEntity
import com.fittrack.core.database.entity.CardioActivityEntity
import com.fittrack.core.database.entity.ExerciseEntity
import com.fittrack.core.database.entity.ExerciseSessionEntity
import com.fittrack.core.database.entity.ExerciseHrSummaryEntity
import com.fittrack.core.database.entity.HealthDailyEntity
import com.fittrack.core.database.entity.HeartRateSampleEntity
import com.fittrack.core.database.entity.PlanExerciseEntity
import com.fittrack.core.database.entity.SetLogEntity
import com.fittrack.core.database.entity.UserProfileEntity
import com.fittrack.core.database.entity.WorkoutPlanEntity
import com.fittrack.core.database.entity.WorkoutSessionEntity
import com.fittrack.core.model.Citation
import com.fittrack.core.model.CardioActivity
import com.fittrack.core.model.CoachSuggestion
import com.fittrack.core.model.Confidence
import com.fittrack.core.model.Exercise
import com.fittrack.core.model.ExerciseSession
import com.fittrack.core.model.ExerciseHeartRateSummary
import com.fittrack.core.model.ExerciseSessionStatus
import com.fittrack.core.model.FitbitSyncStatus
import com.fittrack.core.model.HealthDay
import com.fittrack.core.model.HeartRateSample
import com.fittrack.core.model.PlanExercise
import com.fittrack.core.model.PlanExerciseSnapshot
import com.fittrack.core.model.PlannedSet
import com.fittrack.core.model.SetLog
import com.fittrack.core.model.SetType
import com.fittrack.core.model.SuggestionStatus
import com.fittrack.core.model.SuggestionType
import com.fittrack.core.model.UserProfile
import com.fittrack.core.model.WorkoutPlan
import com.fittrack.core.model.WorkoutPlanSnapshot
import com.fittrack.core.model.WorkoutSession
import com.fittrack.core.model.WorkoutStatus
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

// ---- profile ---------------------------------------------------------

fun UserProfileEntity.toDomain(): UserProfile = UserProfile(
    id = id,
    goal = goal,
    experience = experience,
    equipment = equipmentCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    schedule = schedule,
    constraints = constraints,
    preferences = preferences,
    approved = approved,
    version = version,
)

fun UserProfile.toEntity(updatedAt: Long): UserProfileEntity = UserProfileEntity(
    id = id,
    goal = goal,
    experience = experience,
    equipmentCsv = equipment.joinToString(","),
    schedule = schedule,
    constraints = constraints,
    preferences = preferences,
    approved = approved,
    version = version,
    updatedAt = updatedAt,
)

// ---- exercises / plans -------------------------------------------------

fun ExerciseEntity.toDomain(): Exercise = Exercise(
    id = id,
    name = name,
    movementPattern = movementPattern,
    muscleRole = muscleRole,
    equipment = equipment,
    active = active,
)

fun Exercise.toEntity(updatedAt: Long): ExerciseEntity = ExerciseEntity(
    id = id,
    name = name,
    movementPattern = movementPattern,
    muscleRole = muscleRole,
    equipment = equipment,
    active = active,
    updatedAt = updatedAt,
)

fun PlanExerciseEntity.toDomain(exerciseName: String): PlanExercise = PlanExercise(
    id = id,
    exerciseId = exerciseId,
    exerciseName = exerciseName,
    position = position,
    warmupSets = warmupSets,
    workingSets = workingSets,
    repMin = repMin,
    repMax = repMax,
    targetRir = targetRir,
    startingWeightKg = startingWeightKg,
    restSeconds = restSeconds,
    weightIncrementKg = weightIncrementKg,
    notes = notes,
)

fun PlanExercise.toEntity(planId: String, updatedAt: Long): PlanExerciseEntity = PlanExerciseEntity(
    id = id,
    planId = planId,
    exerciseId = exerciseId,
    position = position,
    warmupSets = warmupSets,
    workingSets = workingSets,
    repMin = repMin,
    repMax = repMax,
    targetRir = targetRir,
    startingWeightKg = startingWeightKg,
    restSeconds = restSeconds,
    weightIncrementKg = weightIncrementKg,
    notes = notes,
    updatedAt = updatedAt,
)

fun WorkoutPlanEntity.toDomain(exercises: List<PlanExercise>): WorkoutPlan = WorkoutPlan(
    id = id,
    name = name,
    dayLabel = dayLabel,
    scheduledDayOfWeek = scheduledDayOfWeek,
    scheduledMinuteOfDay = scheduledMinuteOfDay,
    version = version,
    active = active,
    exercises = exercises.sortedBy { it.position },
)

fun WorkoutPlan.toEntity(updatedAt: Long): WorkoutPlanEntity = WorkoutPlanEntity(
    id = id,
    name = name,
    dayLabel = dayLabel,
    scheduledDayOfWeek = scheduledDayOfWeek,
    scheduledMinuteOfDay = scheduledMinuteOfDay,
    version = version,
    active = active,
    updatedAt = updatedAt,
)

// ---- workout execution ---------------------------------------------------

fun ExerciseSessionEntity.toDomain(): ExerciseSession = ExerciseSession(
    id = id,
    workoutSessionId = workoutSessionId,
    planExerciseId = planExerciseId,
    exerciseId = exerciseId,
    originalExerciseId = originalExerciseId,
    exerciseName = exerciseNameSnapshot,
    position = position,
    restSeconds = restSecondsSnapshot,
    status = ExerciseSessionStatus.valueOf(status),
    startedAt = startedAt?.let(Instant::ofEpochMilli),
    endedAt = endedAt?.let(Instant::ofEpochMilli),
    skippedAt = skippedAt?.let(Instant::ofEpochMilli),
    skipReason = skipReason,
    note = note,
)

fun ExerciseSession.toEntity(updatedAt: Long): ExerciseSessionEntity = ExerciseSessionEntity(
    id = id,
    workoutSessionId = workoutSessionId,
    planExerciseId = planExerciseId,
    exerciseId = exerciseId,
    originalExerciseId = originalExerciseId,
    exerciseNameSnapshot = exerciseName,
    position = position,
    restSecondsSnapshot = restSeconds,
    status = status.name,
    startedAt = startedAt?.toEpochMilli(),
    endedAt = endedAt?.toEpochMilli(),
    skippedAt = skippedAt?.toEpochMilli(),
    skipReason = skipReason,
    note = note,
    updatedAt = updatedAt,
)

fun SetLogEntity.toDomain(): SetLog = SetLog(
    id = id,
    exerciseSessionId = exerciseSessionId,
    position = position,
    planned = PlannedSet(
        type = SetType.valueOf(setType),
        repMin = targetRepMin,
        repMax = targetRepMax,
        targetRir = targetRir,
        targetWeightKg = targetWeightKg,
    ),
    actualReps = actualReps,
    actualWeightKg = actualWeightKg,
    actualRir = actualRir,
    pain = pain,
    techniqueIssue = techniqueIssue,
    concerningSymptom = concerningSymptom,
    completedAt = completedAt?.let(Instant::ofEpochMilli),
)

fun SetLog.toEntity(updatedAt: Long): SetLogEntity = SetLogEntity(
    id = id,
    exerciseSessionId = exerciseSessionId,
    position = position,
    setType = planned.type.name,
    targetRepMin = planned.repMin,
    targetRepMax = planned.repMax,
    targetRir = planned.targetRir,
    targetWeightKg = planned.targetWeightKg,
    actualReps = actualReps,
    actualWeightKg = actualWeightKg,
    actualRir = actualRir,
    pain = pain,
    techniqueIssue = techniqueIssue,
    concerningSymptom = concerningSymptom,
    completedAt = completedAt?.toEpochMilli(),
    updatedAt = updatedAt,
)

// ---- workout session + plan snapshot -------------------------------------

fun WorkoutSessionEntity.toDomain(): WorkoutSession = WorkoutSession(
    id = id,
    planSnapshot = PlanSnapshotJson.fromJson(planSnapshotJson),
    status = WorkoutStatus.valueOf(status),
    startedAt = Instant.ofEpochMilli(startedAt),
    endedAt = endedAt?.let(Instant::ofEpochMilli),
    note = note,
    fitbitSyncStatus = FitbitSyncStatus.valueOf(fitbitSyncStatus),
)

fun WorkoutSession.toEntity(planId: String, updatedAt: Long): WorkoutSessionEntity = WorkoutSessionEntity(
    id = id,
    planId = planId,
    planNameSnapshot = planSnapshot.planName,
    planVersionSnapshot = planSnapshot.planVersion,
    dayLabelSnapshot = planSnapshot.dayLabel,
    planSnapshotJson = PlanSnapshotJson.toJson(planSnapshot),
    status = status.name,
    startedAt = startedAt.toEpochMilli(),
    endedAt = endedAt?.toEpochMilli(),
    note = note,
    fitbitSyncStatus = fitbitSyncStatus.name,
    updatedAt = updatedAt,
)

/** JSON codec for [WorkoutPlanSnapshot], the immutable copy of a plan
 * captured at workout start. Stored inline on [WorkoutSessionEntity] so a
 * later edit to the live plan can never retroactively change history. */
object PlanSnapshotJson {
    fun toJson(snapshot: WorkoutPlanSnapshot): String {
        val root = JSONObject()
        root.put("planId", snapshot.planId)
        root.put("planName", snapshot.planName)
        root.put("planVersion", snapshot.planVersion)
        root.put("dayLabel", snapshot.dayLabel)
        val exercises = JSONArray()
        snapshot.exercises.forEach { ex ->
            val exJson = JSONObject()
            exJson.put("planExerciseId", ex.planExerciseId)
            exJson.put("exerciseId", ex.exerciseId)
            exJson.put("exerciseName", ex.exerciseName)
            exJson.put("position", ex.position)
            exJson.put("restSeconds", ex.restSeconds)
            val sets = JSONArray()
            ex.plannedSets.forEach { set ->
                val setJson = JSONObject()
                setJson.put("type", set.type.name)
                setJson.put("repMin", set.repMin)
                setJson.put("repMax", set.repMax)
                if (set.targetRir != null) setJson.put("targetRir", set.targetRir) else setJson.put("targetRir", JSONObject.NULL)
                setJson.put("targetWeightKg", set.targetWeightKg)
                sets.put(setJson)
            }
            exJson.put("plannedSets", sets)
            exercises.put(exJson)
        }
        root.put("exercises", exercises)
        return root.toString()
    }

    fun fromJson(json: String): WorkoutPlanSnapshot {
        val root = JSONObject(json)
        val exercisesJson = root.getJSONArray("exercises")
        val exercises = (0 until exercisesJson.length()).map { i ->
            val exJson = exercisesJson.getJSONObject(i)
            val setsJson = exJson.getJSONArray("plannedSets")
            val sets = (0 until setsJson.length()).map { j ->
                val setJson = setsJson.getJSONObject(j)
                PlannedSet(
                    type = SetType.valueOf(setJson.getString("type")),
                    repMin = setJson.getInt("repMin"),
                    repMax = setJson.getInt("repMax"),
                    targetRir = if (setJson.isNull("targetRir")) null else setJson.getInt("targetRir"),
                    targetWeightKg = setJson.getDouble("targetWeightKg"),
                )
            }
            PlanExerciseSnapshot(
                planExerciseId = exJson.getString("planExerciseId"),
                exerciseId = exJson.getString("exerciseId"),
                exerciseName = exJson.getString("exerciseName"),
                position = exJson.getInt("position"),
                restSeconds = exJson.getInt("restSeconds"),
                plannedSets = sets,
            )
        }
        return WorkoutPlanSnapshot(
            planId = root.getString("planId"),
            planName = root.getString("planName"),
            planVersion = root.getInt("planVersion"),
            dayLabel = root.getString("dayLabel"),
            exercises = exercises,
        )
    }
}

// ---- health -----------------------------------------------------------

fun HealthDailyEntity.toDomain(): HealthDay = HealthDay(
    date = date,
    sleepMinutes = sleepMinutes,
    restingHeartRate = restingHeartRate,
    steps = steps,
    source = source,
)

fun HealthDay.toEntity(syncedAt: Long): HealthDailyEntity = HealthDailyEntity(
    date = date,
    sleepMinutes = sleepMinutes,
    restingHeartRate = restingHeartRate,
    steps = steps,
    source = source,
    syncedAt = syncedAt,
)

fun CardioActivityEntity.toDomain(): CardioActivity = CardioActivity(
    id = id,
    sourceId = sourceId,
    kind = kind,
    startedAt = Instant.ofEpochMilli(startedAt),
    endedAt = Instant.ofEpochMilli(endedAt),
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    steps = steps,
    averageHeartRate = averageHeartRate,
)

fun CardioActivity.toEntity(id: String, syncedAt: Long): CardioActivityEntity = CardioActivityEntity(
    id = id,
    sourceId = sourceId,
    kind = kind,
    startedAt = startedAt.toEpochMilli(),
    endedAt = endedAt.toEpochMilli(),
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    steps = steps,
    averageHeartRate = averageHeartRate,
    syncedAt = syncedAt,
)

fun HeartRateSampleEntity.toDomain(): HeartRateSample = HeartRateSample(
    workoutSessionId = workoutSessionId,
    sampledAt = Instant.ofEpochMilli(sampledAt),
    bpm = bpm,
)

fun HeartRateSample.toEntity(): HeartRateSampleEntity = HeartRateSampleEntity(
    workoutSessionId = workoutSessionId,
    sampledAt = sampledAt.toEpochMilli(),
    bpm = bpm,
)

fun ExerciseHrSummaryEntity.toDomain(): ExerciseHeartRateSummary = ExerciseHeartRateSummary(
    exerciseSessionId = exerciseSessionId,
    averageBpm = averageBpm,
    maximumBpm = maximumBpm,
    recoveryDeltaBpm = recoveryDeltaBpm,
    sampleCount = sampleCount,
)

fun ExerciseHeartRateSummary.toEntity(computedAt: Long): ExerciseHrSummaryEntity {
    require(hasData) { "A no-data HR aggregate must not be persisted" }
    return ExerciseHrSummaryEntity(
        exerciseSessionId = exerciseSessionId,
        averageBpm = requireNotNull(averageBpm),
        maximumBpm = requireNotNull(maximumBpm),
        recoveryDeltaBpm = recoveryDeltaBpm,
        sampleCount = sampleCount,
        computedAt = computedAt,
    )
}

// ---- coach suggestions ---------------------------------------------------

fun CoachSuggestionEntity.toDomain(): CoachSuggestion = CoachSuggestion(
    id = id,
    type = SuggestionType.valueOf(type),
    targetId = targetId,
    before = beforeValue,
    after = afterValue,
    reasonCode = reasonCode,
    explanation = explanation,
    signals = jsonStringArray(signalsJson),
    citations = jsonCitationsArray(citationsJson),
    confidence = Confidence.valueOf(confidence),
    status = SuggestionStatus.valueOf(status),
)

fun CoachSuggestion.toEntity(createdAt: Long, decidedAt: Long? = null, decisionReason: String = ""): CoachSuggestionEntity = CoachSuggestionEntity(
    id = id,
    type = type.name,
    targetId = targetId,
    beforeValue = before,
    afterValue = after,
    reasonCode = reasonCode,
    explanation = explanation,
    signalsJson = JSONArray(signals).toString(),
    citationsJson = citationsToJson(citations),
    confidence = confidence.name,
    status = status.name,
    decisionReason = decisionReason,
    createdAt = createdAt,
    decidedAt = decidedAt,
)

private fun jsonStringArray(json: String): List<String> {
    val array = JSONArray(json)
    return (0 until array.length()).map { array.getString(it) }
}

private fun jsonCitationsArray(json: String): List<Citation> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        Citation(title = obj.getString("title"), url = obj.getString("url"), claim = obj.getString("claim"))
    }
}

private fun citationsToJson(citations: List<Citation>): String {
    val array = JSONArray()
    citations.forEach { citation ->
        val obj = JSONObject()
        obj.put("title", citation.title)
        obj.put("url", citation.url)
        obj.put("claim", citation.claim)
        array.put(obj)
    }
    return array.toString()
}
