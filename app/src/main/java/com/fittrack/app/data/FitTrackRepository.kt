package com.fittrack.app.data

import android.content.Context
import androidx.room.withTransaction
import com.fittrack.app.data.local.*
import com.fittrack.app.domain.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.util.UUID

class FitTrackRepository(
    private val context: Context,
    private val database: FitTrackDatabase,
) {
    private val dao = database.dao()

    val profile: Flow<UserProfileEntity?> = dao.observeProfile()
    val plans: Flow<List<WorkoutPlanEntity>> = dao.observePlans()
    val activeWorkout: Flow<WorkoutSessionEntity?> = dao.observeActiveWorkout()
    val history: Flow<List<WorkoutSessionEntity>> = dao.observeRecentWorkouts()
    val health: Flow<List<HealthDailyEntity>> = dao.observeHealth()
    val suggestions: Flow<List<CoachSuggestionEntity>> = dao.observePendingSuggestions()

    fun exerciseSessions(workoutId: String) = dao.observeExerciseSessions(workoutId)
    fun setLogs(exerciseSessionId: String) = dao.observeSetLogs(exerciseSessionId)

    data class NewPlanExercise(
        val name: String,
        val movementPattern: String = "OTHER",
        val muscleRole: String = "OTHER",
        val equipment: String = "OTHER",
        val workingSets: Int,
        val repMin: Int,
        val repMax: Int,
        val targetRir: Int?,
        val startingWeightKg: Double,
        val restSeconds: Int,
        val weightIncrementKg: Double,
    )

    suspend fun createPlan(name: String, dayLabel: String, exercises: List<NewPlanExercise>) {
        require(name.isNotBlank()) { "Nazwa planu jest wymagana" }
        require(exercises.isNotEmpty()) { "Dodaj przynajmniej jedno ćwiczenie" }
        val timestamp = now()
        val planId = UUID.randomUUID().toString()
        val exerciseRows = exercises.map { item ->
            require(item.name.isNotBlank()) { "Nazwa ćwiczenia jest wymagana" }
            ExerciseEntity(
                id = UUID.randomUUID().toString(),
                name = item.name.trim(),
                movementPattern = item.movementPattern.trim().uppercase(),
                muscleRole = item.muscleRole.trim().uppercase(),
                equipment = item.equipment.trim().uppercase(),
                updatedAt = timestamp,
            )
        }
        val planned = exercises.zip(exerciseRows).mapIndexed { index, (item, exercise) ->
            PlanExerciseEntity(
                id = UUID.randomUUID().toString(),
                planId = planId,
                exerciseId = exercise.id,
                position = index,
                warmupSets = if (index < 2) 1 else 0,
                workingSets = item.workingSets.coerceIn(1, 10),
                repMin = item.repMin.coerceIn(1, 50),
                repMax = item.repMax.coerceAtLeast(item.repMin).coerceAtMost(100),
                targetRir = item.targetRir?.coerceIn(0, 10),
                startingWeightKg = item.startingWeightKg.coerceAtLeast(0.0),
                restSeconds = item.restSeconds.coerceIn(15, 600),
                weightIncrementKg = item.weightIncrementKg.coerceAtLeast(0.25),
                updatedAt = timestamp,
            )
        }
        database.withTransaction {
            dao.upsertPlans(listOf(WorkoutPlanEntity(planId, name.trim(), dayLabel.trim().ifBlank { name.trim() }, null, null, updatedAt = timestamp)))
            dao.upsertExercises(exerciseRows)
            dao.upsertPlanExercises(planned)
            enqueue("workout_plan", planId, "UPSERT", JSONObject().put("id", planId).put("name", name.trim()).toString())
        }
    }

    suspend fun saveProfile(
        goal: String,
        experience: String,
        equipment: String,
        schedule: String,
        constraints: String,
        preferences: String,
    ) {
        val now = now()
        val profile = UserProfileEntity(
            goal = goal.trim(),
            experience = experience.trim(),
            equipment = equipment.trim(),
            schedule = schedule.trim(),
            constraints = constraints.trim(),
            preferences = preferences.trim(),
            approved = true,
            updatedAt = now,
        )
        dao.upsertProfile(profile)
        enqueue("user_profile", profile.id, "UPSERT", JSONObject().put("id", profile.id).put("updatedAt", now).toString())
    }

    suspend fun seedDefaultPlanIfNeeded() {
        if (dao.planCount() > 0) return
        val source = context.assets.open("sample_plan.json").bufferedReader().use { it.readText() }
        val json = JSONObject(source)
        val planId = UUID.randomUUID().toString()
        val timestamp = now()
        val plan = WorkoutPlanEntity(
            id = planId,
            name = json.getString("name"),
            dayLabel = json.getString("dayLabel"),
            scheduledDayOfWeek = json.optInt("scheduledDayOfWeek").takeIf { it > 0 },
            scheduledMinuteOfDay = json.optInt("scheduledMinuteOfDay").takeIf { it >= 0 },
            updatedAt = timestamp,
        )
        val exercisesJson = json.getJSONArray("exercises")
        val exercises = mutableListOf<ExerciseEntity>()
        val planned = mutableListOf<PlanExerciseEntity>()
        for (i in 0 until exercisesJson.length()) {
            val item = exercisesJson.getJSONObject(i)
            val exerciseId = UUID.randomUUID().toString()
            exercises += ExerciseEntity(
                id = exerciseId,
                name = item.getString("name"),
                movementPattern = item.getString("movementPattern"),
                muscleRole = item.getString("muscleRole"),
                equipment = item.getString("equipment"),
                updatedAt = timestamp,
            )
            planned += PlanExerciseEntity(
                id = UUID.randomUUID().toString(),
                planId = planId,
                exerciseId = exerciseId,
                position = i,
                warmupSets = item.getInt("warmupSets"),
                workingSets = item.getInt("workingSets"),
                repMin = item.getInt("repMin"),
                repMax = item.getInt("repMax"),
                targetRir = item.optInt("targetRir").takeIf { item.has("targetRir") },
                startingWeightKg = item.getDouble("startingWeightKg"),
                restSeconds = item.getInt("restSeconds"),
                weightIncrementKg = item.getDouble("weightIncrementKg"),
                updatedAt = timestamp,
            )
        }
        database.withTransaction {
            dao.upsertPlans(listOf(plan))
            val librarySource = context.assets.open("exercise_library.json").bufferedReader().use { it.readText() }
            val libraryJson = JSONArray(librarySource)
            val library = (0 until libraryJson.length()).map { index ->
                val item = libraryJson.getJSONObject(index)
                ExerciseEntity(
                    id = UUID.randomUUID().toString(),
                    name = item.getString("name"),
                    movementPattern = item.getString("movementPattern"),
                    muscleRole = item.getString("muscleRole"),
                    equipment = item.getString("equipment"),
                    updatedAt = timestamp,
                )
            }
            dao.upsertExercises(exercises + library)
            dao.upsertPlanExercises(planned)
        }
    }

    suspend fun startWorkout(planId: String): String = database.withTransaction {
        dao.getActiveWorkout()?.let { return@withTransaction it.id }
        val plan = requireNotNull(dao.getPlan(planId)) { "Plan nie istnieje" }
        val planned = dao.getPlanExercises(planId)
        require(planned.isNotEmpty()) { "Plan nie ma ćwiczeń" }
        val exercises = dao.getExercises(planned.map { it.exerciseId }).associateBy { it.id }
        val timestamp = now()
        val workoutId = UUID.randomUUID().toString()
        dao.insertWorkout(
            WorkoutSessionEntity(
                id = workoutId,
                planId = plan.id,
                planNameSnapshot = plan.name,
                planVersionSnapshot = plan.version,
                status = "ACTIVE",
                startedAt = timestamp,
                updatedAt = timestamp,
            )
        )
        val sessions = planned.map { prescribed ->
            val exercise = requireNotNull(exercises[prescribed.exerciseId])
            ExerciseSessionEntity(
                id = UUID.randomUUID().toString(),
                workoutSessionId = workoutId,
                planExerciseId = prescribed.id,
                exerciseId = exercise.id,
                originalExerciseId = exercise.id,
                exerciseNameSnapshot = exercise.name,
                position = prescribed.position,
                restSecondsSnapshot = prescribed.restSeconds,
                status = if (prescribed.position == planned.minOf { it.position }) "ACTIVE" else "PENDING",
                startedAt = if (prescribed.position == planned.minOf { it.position }) timestamp else null,
                updatedAt = timestamp,
            )
        }
        dao.insertExerciseSessions(sessions)
        sessions.forEach { session ->
            val prescribed = planned.first { it.id == session.planExerciseId }
            val sets = buildList {
                repeat(prescribed.warmupSets) { index ->
                    add(newSet(session.id, index, "WARMUP", prescribed, prescribed.startingWeightKg * 0.5, timestamp))
                }
                repeat(prescribed.workingSets) { index ->
                    add(newSet(session.id, prescribed.warmupSets + index, "WORKING", prescribed, prescribed.startingWeightKg, timestamp))
                }
            }
            dao.insertSetLogs(sets)
        }
        enqueue("workout_session", workoutId, "UPSERT", JSONObject().put("id", workoutId).put("status", "ACTIVE").toString())
        workoutId
    }

    suspend fun completeSet(
        setId: String,
        exerciseSessionId: String,
        position: Int,
        setType: String,
        targetRepMin: Int,
        targetRepMax: Int,
        targetRir: Int?,
        targetWeightKg: Double,
        reps: Int,
        weightKg: Double,
        rir: Int?,
        pain: Boolean,
        techniqueIssue: Boolean,
    ) {
        require(reps in 0..100) { "Nieprawidłowa liczba powtórzeń" }
        require(weightKg in 0.0..1000.0) { "Nieprawidłowy ciężar" }
        require(rir == null || rir in 0..10) { "RIR musi być w zakresie 0–10" }
        val timestamp = now()
        dao.updateSetLog(
            SetLogEntity(
                id = setId,
                exerciseSessionId = exerciseSessionId,
                position = position,
                setType = setType,
                targetRepMin = targetRepMin,
                targetRepMax = targetRepMax,
                targetRir = targetRir,
                targetWeightKg = targetWeightKg,
                actualReps = reps,
                actualWeightKg = weightKg,
                actualRir = rir,
                pain = pain,
                techniqueIssue = techniqueIssue,
                completedAt = timestamp,
                updatedAt = timestamp,
            )
        )
        enqueue("set_log", setId, "UPSERT", JSONObject().put("id", setId).put("completedAt", timestamp).toString())
    }

    suspend fun finishExercise(exerciseSessionId: String, note: String) = database.withTransaction {
        val current = requireNotNull(dao.getExerciseSession(exerciseSessionId))
        val timestamp = now()
        dao.updateExerciseSession(current.copy(status = "COMPLETED", endedAt = timestamp, note = note.trim(), updatedAt = timestamp))
        activateNextExercise(current.workoutSessionId, current.position, timestamp)
    }

    suspend fun skipExercise(exerciseSessionId: String, reason: String) = database.withTransaction {
        val current = requireNotNull(dao.getExerciseSession(exerciseSessionId))
        val timestamp = now()
        dao.updateExerciseSession(
            current.copy(status = "SKIPPED", skippedAt = timestamp, endedAt = timestamp, skipReason = reason.trim(), updatedAt = timestamp)
        )
        activateNextExercise(current.workoutSessionId, current.position, timestamp)
    }

    suspend fun swapCandidates(exerciseSessionId: String, availableEquipment: List<String>): List<ExerciseEntity> {
        val session = requireNotNull(dao.getExerciseSession(exerciseSessionId))
        val exercise = requireNotNull(dao.getExercise(session.exerciseId))
        return dao.findSwapCandidates(exercise.movementPattern, availableEquipment, exercise.id)
    }

    suspend fun swapExercise(exerciseSessionId: String, replacementId: String) {
        val session = requireNotNull(dao.getExerciseSession(exerciseSessionId))
        val replacement = requireNotNull(dao.getExercise(replacementId))
        dao.updateExerciseSession(
            session.copy(
                exerciseId = replacement.id,
                exerciseNameSnapshot = replacement.name,
                updatedAt = now(),
            )
        )
    }

    suspend fun finishWorkout(workoutId: String, note: String, readiness: ReadinessLevel) = database.withTransaction {
        val workout = requireNotNull(dao.getActiveWorkout()).also { require(it.id == workoutId) }
        val sessions = dao.getExerciseSessions(workoutId)
        val timestamp = now()
        sessions.filter { it.status == "ACTIVE" || it.status == "PENDING" }.forEach {
            dao.updateExerciseSession(it.copy(status = "SKIPPED", skippedAt = timestamp, endedAt = timestamp, skipReason = "Zakończono trening", updatedAt = timestamp))
        }
        dao.updateWorkout(workout.copy(status = "COMPLETED", endedAt = timestamp, note = note.trim(), fitbitSyncStatus = "PENDING", updatedAt = timestamp))
        createProgressionSuggestions(sessions.filter { it.status == "COMPLETED" }, readiness, timestamp)
        enqueue("workout_session", workoutId, "UPSERT", JSONObject().put("id", workoutId).put("status", "COMPLETED").toString())
    }

    suspend fun decideSuggestion(id: String, accept: Boolean, reason: String = "") = database.withTransaction {
        val suggestion = requireNotNull(dao.getSuggestion(id))
        require(suggestion.status == "PENDING") { "Sugestia została już rozpatrzona" }
        val timestamp = now()
        if (accept) {
            val planned = dao.getPlanExercise(suggestion.targetId)
            if (planned != null) {
                when (suggestion.type) {
                    "LOAD" -> dao.updatePlanExercise(planned.copy(startingWeightKg = suggestion.afterValue.toDouble(), updatedAt = timestamp))
                    "VOLUME" -> dao.updatePlanExercise(planned.copy(workingSets = (planned.workingSets + suggestion.afterValue.toInt()).coerceAtLeast(1), updatedAt = timestamp))
                }
            }
        }
        dao.updateSuggestion(suggestion.copy(status = if (accept) "ACCEPTED" else "REJECTED", decisionReason = reason, decidedAt = timestamp))
        enqueue("coach_suggestion", id, "UPSERT", JSONObject().put("id", id).put("status", if (accept) "ACCEPTED" else "REJECTED").toString())
    }

    private suspend fun createProgressionSuggestions(sessions: List<ExerciseSessionEntity>, readiness: ReadinessLevel, timestamp: Long) {
        sessions.forEach { session ->
            val plan = dao.getPlanExercise(session.planExerciseId) ?: return@forEach
            val logs = dao.getSetLogs(session.id)
            val recommendation = ProgressionEngine.recommend(
                ProgressionInput(
                    currentWeightKg = plan.startingWeightKg,
                    incrementKg = plan.weightIncrementKg,
                    repMin = plan.repMin,
                    repMax = plan.repMax,
                    targetRir = plan.targetRir,
                    sets = logs.mapNotNull { log ->
                        log.actualReps?.let { reps ->
                            SetPerformance(reps, log.actualRir, log.setType == "WARMUP", log.pain, log.techniqueIssue, log.concerningSymptom)
                        }
                    },
                    readiness = readiness,
                )
            )
            if (recommendation.action == ProgressionAction.HOLD_LOAD) return@forEach
            val type = if (recommendation.action == ProgressionAction.REDUCE_VOLUME) "VOLUME" else "LOAD"
            val after = if (type == "VOLUME") recommendation.proposedSetDelta.toString() else recommendation.proposedWeightKg.toString()
            dao.upsertSuggestion(
                CoachSuggestionEntity(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    targetId = plan.id,
                    beforeValue = if (type == "VOLUME") "0" else plan.startingWeightKg.toString(),
                    afterValue = after,
                    reasonCode = recommendation.reasonCode,
                    explanation = recommendation.explanation,
                    confidence = "HIGH",
                    createdAt = timestamp,
                )
            )
        }
    }

    private suspend fun activateNextExercise(workoutId: String, currentPosition: Int, timestamp: Long) {
        dao.getExerciseSessions(workoutId)
            .firstOrNull { it.position > currentPosition && it.status == "PENDING" }
            ?.let { dao.updateExerciseSession(it.copy(status = "ACTIVE", startedAt = timestamp, updatedAt = timestamp)) }
    }

    private fun newSet(sessionId: String, position: Int, type: String, plan: PlanExerciseEntity, weight: Double, timestamp: Long) =
        SetLogEntity(
            id = UUID.randomUUID().toString(),
            exerciseSessionId = sessionId,
            position = position,
            setType = type,
            targetRepMin = plan.repMin,
            targetRepMax = plan.repMax,
            targetRir = plan.targetRir,
            targetWeightKg = weight,
            updatedAt = timestamp,
        )

    private suspend fun enqueue(entityType: String, entityId: String, operation: String, payload: String) {
        val timestamp = now()
        dao.enqueueSync(
            SyncOutboxEntity(
                id = UUID.randomUUID().toString(),
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payloadJson = payload,
                version = 1,
                createdAt = timestamp,
            )
        )
    }

    private fun now(): Long = Instant.now().toEpochMilli()
}
