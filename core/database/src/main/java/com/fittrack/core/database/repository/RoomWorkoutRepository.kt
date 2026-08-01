package com.fittrack.core.database.repository

import androidx.room.withTransaction
import com.fittrack.core.database.FitTrackDatabase
import com.fittrack.core.database.entity.ExerciseSessionEntity
import com.fittrack.core.database.entity.SetLogEntity
import com.fittrack.core.database.entity.SyncOutboxEntity
import com.fittrack.core.database.mapper.toDomain
import com.fittrack.core.database.mapper.toEntity
import com.fittrack.core.model.CoachSuggestion
import com.fittrack.core.model.ExerciseSession
import com.fittrack.core.model.FitbitSyncStatus
import com.fittrack.core.model.PlanExerciseSnapshot
import com.fittrack.core.model.PlannedSet
import com.fittrack.core.model.SetLog
import com.fittrack.core.model.SetType
import com.fittrack.core.model.WorkoutPlanSnapshot
import com.fittrack.core.model.WorkoutSession
import com.fittrack.core.model.WorkoutStatus
import com.fittrack.core.model.repository.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/**
 * Room-backed [WorkoutRepository]. Transactional guarantees:
 *  - [startWorkout] checks for an existing ACTIVE workout first and returns
 *    it unchanged rather than starting a second one — only one active
 *    workout at a time.
 *  - The plan snapshot is captured (as JSON, see [com.fittrack.core.database.mapper.PlanSnapshotJson])
 *    inside the same transaction that creates the session, so a later plan
 *    edit can never retroactively change a past workout.
 *  - Every set/exercise/workout row is written with a client-generated UUID
 *    id and persisted immediately on each call — nothing is buffered in
 *    memory only, so killing the process mid-workout loses at most the
 *    in-flight UI state; [observeActiveWorkout]/[observeExerciseSessions]
 *    reconstruct exactly where the user left off on next launch.
 *  - Deletions are tombstones ([cancelWorkout] and plan deletion use status/
 *    deletedAt columns), never hard deletes, so sync can propagate them later.
 */
class RoomWorkoutRepository(private val database: FitTrackDatabase) : WorkoutRepository {
    private val dao = database.dao()

    override fun observeActiveWorkout(): Flow<WorkoutSession?> = dao.observeActiveWorkout().map { it?.toDomain() }

    override suspend fun getActiveWorkout(): WorkoutSession? = dao.getActiveWorkout()?.toDomain()

    override fun observeHistory(limit: Int): Flow<List<WorkoutSession>> =
        dao.observeRecentWorkouts(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun getWorkout(workoutId: String): WorkoutSession? = dao.getWorkout(workoutId)?.toDomain()

    override fun observeExerciseSessions(workoutId: String): Flow<List<ExerciseSession>> =
        dao.observeExerciseSessions(workoutId).map { list -> list.map { it.toDomain() } }

    override suspend fun getExerciseSessions(workoutId: String): List<ExerciseSession> =
        dao.getExerciseSessions(workoutId).map { it.toDomain() }

    override fun observeSetLogs(exerciseSessionId: String): Flow<List<SetLog>> =
        dao.observeSetLogs(exerciseSessionId).map { list -> list.map { it.toDomain() } }

    override suspend fun getSetLogs(exerciseSessionId: String): List<SetLog> =
        dao.getSetLogs(exerciseSessionId).map { it.toDomain() }

    override suspend fun getExerciseSessionHistory(planExerciseId: String, excludeWorkoutId: String?, limit: Int): List<ExerciseSession> =
        dao.getExerciseSessionHistory(planExerciseId, excludeWorkoutId, limit).map { it.toDomain() }

    override suspend fun startWorkout(planId: String): WorkoutSession = database.withTransaction {
        dao.getActiveWorkout()?.let { return@withTransaction it.toDomain() }

        val planEntity = requireNotNull(dao.getPlan(planId)) { "Plan $planId does not exist" }
        val plannedExercises = dao.getPlanExercises(planId).sortedBy { it.position }
        require(plannedExercises.isNotEmpty()) { "Plan $planId has no exercises" }
        val exerciseEntities = dao.getExercises(plannedExercises.map { it.exerciseId }).associateBy { it.id }
        val now = now()
        val workoutId = UUID.randomUUID().toString()
        val minPosition = plannedExercises.minOf { it.position }

        val snapshotExercises = plannedExercises.map { pe ->
            val exercise = requireNotNull(exerciseEntities[pe.exerciseId]) { "Exercise ${pe.exerciseId} missing from library" }
            val sets = buildList {
                repeat(pe.warmupSets) { add(PlannedSet(SetType.WARMUP, pe.repMin, pe.repMax, pe.targetRir, pe.startingWeightKg * 0.5)) }
                repeat(pe.workingSets) { add(PlannedSet(SetType.WORKING, pe.repMin, pe.repMax, pe.targetRir, pe.startingWeightKg)) }
            }
            PlanExerciseSnapshot(pe.id, exercise.id, exercise.name, pe.position, pe.restSeconds, sets)
        }

        val snapshot = WorkoutPlanSnapshot(planEntity.id, planEntity.name, planEntity.version, planEntity.dayLabel, snapshotExercises)
        val session = WorkoutSession(
            id = workoutId,
            planSnapshot = snapshot,
            status = WorkoutStatus.ACTIVE,
            startedAt = Instant.ofEpochMilli(now),
            endedAt = null,
            note = "",
            fitbitSyncStatus = FitbitSyncStatus.NOT_REQUESTED,
        )
        dao.insertWorkout(session.toEntity(planId = planId, updatedAt = now))

        val exerciseSessionEntities = snapshotExercises.map { ex ->
            ExerciseSessionEntity(
                id = UUID.randomUUID().toString(),
                workoutSessionId = workoutId,
                planExerciseId = ex.planExerciseId,
                exerciseId = ex.exerciseId,
                originalExerciseId = ex.exerciseId,
                exerciseNameSnapshot = ex.exerciseName,
                position = ex.position,
                restSecondsSnapshot = ex.restSeconds,
                status = if (ex.position == minPosition) "ACTIVE" else "PENDING",
                startedAt = if (ex.position == minPosition) now else null,
                updatedAt = now,
            )
        }
        dao.insertExerciseSessions(exerciseSessionEntities)

        exerciseSessionEntities.forEachIndexed { index, sessionEntity ->
            val plannedSets = snapshotExercises[index].plannedSets
            val setEntities = plannedSets.mapIndexed { position, set ->
                SetLogEntity(
                    id = UUID.randomUUID().toString(),
                    exerciseSessionId = sessionEntity.id,
                    position = position,
                    setType = set.type.name,
                    targetRepMin = set.repMin,
                    targetRepMax = set.repMax,
                    targetRir = set.targetRir,
                    targetWeightKg = set.targetWeightKg,
                    updatedAt = now,
                )
            }
            dao.insertSetLogs(setEntities)
        }

        enqueue("workout_session", workoutId, "UPSERT", now)
        session
    }

    override suspend fun completeSet(
        setId: String,
        reps: Int,
        weightKg: Double,
        rir: Int?,
        pain: Boolean,
        techniqueIssue: Boolean,
        concerningSymptom: Boolean,
    ): SetLog {
        require(reps in 0..100) { "Invalid rep count: $reps" }
        require(weightKg in 0.0..1000.0) { "Invalid weight: $weightKg" }
        require(rir == null || rir in 0..10) { "RIR must be 0..10" }
        val existing = requireNotNull(dao.getSetLog(setId)) { "Set $setId does not exist" }
        val now = now()
        val updated = existing.copy(
            actualReps = reps,
            actualWeightKg = weightKg,
            actualRir = rir,
            pain = pain,
            techniqueIssue = techniqueIssue,
            concerningSymptom = concerningSymptom,
            completedAt = now,
            updatedAt = now,
        )
        dao.updateSetLog(updated)
        enqueue("set_log", setId, "UPSERT", now)
        return updated.toDomain()
    }

    override suspend fun finishExercise(exerciseSessionId: String, note: String) = database.withTransaction {
        val current = requireNotNull(dao.getExerciseSession(exerciseSessionId))
        val now = now()
        dao.updateExerciseSession(current.copy(status = "COMPLETED", endedAt = now, note = note.trim(), updatedAt = now))
        activateNext(current.workoutSessionId, current.position, now)
        enqueue("exercise_session", exerciseSessionId, "UPSERT", now)
    }

    override suspend fun skipExercise(exerciseSessionId: String, reason: String) = database.withTransaction {
        val current = requireNotNull(dao.getExerciseSession(exerciseSessionId))
        val now = now()
        dao.updateExerciseSession(
            current.copy(status = "SKIPPED", skippedAt = now, endedAt = now, skipReason = reason.trim(), updatedAt = now),
        )
        activateNext(current.workoutSessionId, current.position, now)
        enqueue("exercise_session", exerciseSessionId, "UPSERT", now)
    }

    override suspend fun swapExercise(exerciseSessionId: String, replacementExerciseId: String) {
        val session = requireNotNull(dao.getExerciseSession(exerciseSessionId))
        val replacement = requireNotNull(dao.getExercise(replacementExerciseId))
        val now = now()
        // Only this session's live exercise pointer changes — the plan template
        // (plan_exercises) is never touched, matching "swap by default does not
        // change the template".
        dao.updateExerciseSession(session.copy(exerciseId = replacement.id, exerciseNameSnapshot = replacement.name, updatedAt = now))
        enqueue("exercise_session", exerciseSessionId, "UPSERT", now)
    }

    override suspend fun finishWorkout(workoutId: String, note: String): WorkoutSession = database.withTransaction {
        val workout = requireNotNull(dao.getActiveWorkout()) { "No active workout" }
        require(workout.id == workoutId) { "workoutId does not match the active workout" }
        val now = now()
        val sessions = dao.getExerciseSessions(workoutId)
        sessions.filter { it.status == "ACTIVE" || it.status == "PENDING" }.forEach {
            dao.updateExerciseSession(it.copy(status = "SKIPPED", skippedAt = now, endedAt = now, skipReason = "Workout finished", updatedAt = now))
        }
        val updated = workout.copy(status = "COMPLETED", endedAt = now, note = note.trim(), fitbitSyncStatus = "PENDING", updatedAt = now)
        dao.updateWorkout(updated)
        enqueue("workout_session", workoutId, "UPSERT", now)
        updated.toDomain()
    }

    override suspend fun cancelWorkout(workoutId: String) = database.withTransaction {
        val workout = requireNotNull(dao.getWorkout(workoutId))
        val now = now()
        dao.updateWorkout(workout.copy(status = "CANCELLED", endedAt = now, updatedAt = now))
        enqueue("workout_session", workoutId, "UPSERT", now)
    }

    override fun observePendingSuggestions(): Flow<List<CoachSuggestion>> =
        dao.observePendingSuggestions().map { list -> list.map { it.toDomain() } }

    override suspend fun getSuggestion(id: String): CoachSuggestion? = dao.getSuggestion(id)?.toDomain()

    override suspend fun saveSuggestions(suggestions: List<CoachSuggestion>) {
        val now = now()
        dao.upsertSuggestions(suggestions.map { it.toEntity(createdAt = now) })
    }

    override suspend fun decideSuggestion(id: String, accept: Boolean, reason: String) = database.withTransaction {
        val suggestion = requireNotNull(dao.getSuggestion(id))
        require(suggestion.status == "PENDING") { "Suggestion $id was already decided" }
        val now = now()
        dao.updateSuggestion(
            suggestion.copy(status = if (accept) "ACCEPTED" else "REJECTED", decisionReason = reason, decidedAt = now),
        )
        enqueue("coach_suggestion", id, "UPSERT", now)
    }

    private suspend fun activateNext(workoutId: String, currentPosition: Int, now: Long) {
        dao.getExerciseSessions(workoutId)
            .filter { it.position > currentPosition && it.status == "PENDING" }
            .minByOrNull { it.position }
            ?.let { dao.updateExerciseSession(it.copy(status = "ACTIVE", startedAt = now, updatedAt = now)) }
    }

    private suspend fun enqueue(entityType: String, entityId: String, operation: String, timestamp: Long) {
        dao.enqueueSync(
            SyncOutboxEntity(
                id = UUID.randomUUID().toString(),
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payloadJson = "{\"id\":\"$entityId\"}",
                version = 1,
                createdAt = timestamp,
            ),
        )
    }

    private fun now(): Long = Instant.now().toEpochMilli()
}
