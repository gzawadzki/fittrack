package com.fittrack.core.database.repository

import androidx.room.withTransaction
import com.fittrack.core.database.FitTrackDatabase
import com.fittrack.core.database.entity.ExerciseSessionEntity
import com.fittrack.core.database.entity.SetLogEntity
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
import com.fittrack.core.database.sync.SyncEntityType
import com.fittrack.core.database.sync.SyncOutboxWriter
import com.fittrack.core.database.sync.syncData
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
    private val outbox = SyncOutboxWriter(dao)

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

        val allSetEntities = mutableListOf<SetLogEntity>()
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
            allSetEntities += setEntities
        }

        val workoutEntity = requireNotNull(dao.getWorkoutIncludingDeleted(workoutId))
        outbox.upsert(SyncEntityType.WORKOUT_SESSIONS, workoutId, null, workoutEntity.syncData(), now)
        exerciseSessionEntities.forEach { entity ->
            outbox.upsert(SyncEntityType.EXERCISE_SESSIONS, entity.id, null, entity.syncData(), now)
        }
        allSetEntities.forEach { entity ->
            outbox.upsert(SyncEntityType.SET_LOGS, entity.id, null, entity.syncData(), now)
        }
        session
    }

    override suspend fun startExercise(exerciseSessionId: String) = database.withTransaction {
        val workout = requireNotNull(dao.getActiveWorkout()) { "No active workout" }
        val exercise = requireNotNull(dao.getExerciseSession(exerciseSessionId)) { "Exercise session does not exist" }
        require(exercise.workoutSessionId == workout.id) { "Exercise does not belong to the active workout" }
        require(exercise.status == "PENDING") { "Only a pending exercise can be started" }
        val sessions = dao.getExerciseSessions(workout.id)
        require(sessions.none { it.status == "ACTIVE" }) { "Finish or skip the active exercise first" }
        require(sessions.none { it.position < exercise.position && it.status == "PENDING" }) { "Exercises must be started in plan order" }
        val now = now()
        val updated = exercise.copy(status = "ACTIVE", startedAt = now, updatedAt = now)
        dao.updateExerciseSession(updated)
        outbox.upsert(SyncEntityType.EXERCISE_SESSIONS, updated.id, updated.serverVersion, updated.syncData(), now)
    }

    override suspend fun completeSet(
        setId: String,
        reps: Int,
        weightKg: Double,
        rir: Int?,
        pain: Boolean,
        techniqueIssue: Boolean,
        concerningSymptom: Boolean,
    ): SetLog = database.withTransaction {
        require(reps in 1..100) { "Invalid rep count: $reps" }
        require(weightKg.isFinite() && weightKg in 0.0..1000.0) { "Invalid weight: $weightKg" }
        require(rir == null || rir in 0..10) { "RIR must be 0..10" }
        val existing = requireNotNull(dao.getSetLog(setId)) { "Set $setId does not exist" }
        val exercise = requireNotNull(dao.getExerciseSession(existing.exerciseSessionId)) { "Exercise session does not exist" }
        require(exercise.status == "ACTIVE") { "Sets can only be logged for the active exercise" }
        val workout = requireNotNull(dao.getWorkout(exercise.workoutSessionId)) { "Workout does not exist" }
        require(workout.status == "ACTIVE") { "Sets can only be logged in the active workout" }
        if (existing.completedAt != null) {
            val identicalRetry = existing.actualReps == reps && existing.actualWeightKg == weightKg &&
                existing.actualRir == rir && existing.pain == pain && existing.techniqueIssue == techniqueIssue &&
                existing.concerningSymptom == concerningSymptom
            require(identicalRetry) { "Completed set cannot be overwritten" }
            return@withTransaction existing.toDomain()
        }
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
        outbox.upsert(SyncEntityType.SET_LOGS, updated.id, updated.serverVersion, updated.syncData(), now)
        updated.toDomain()
    }

    override suspend fun finishExercise(exerciseSessionId: String, note: String) = database.withTransaction {
        val current = requireNotNull(dao.getExerciseSession(exerciseSessionId))
        require(current.status == "ACTIVE") { "Only the active exercise can be finished" }
        require(dao.getWorkout(current.workoutSessionId)?.status == "ACTIVE") { "Workout is not active" }
        require(dao.getSetLogs(exerciseSessionId).all { it.completedAt != null }) { "Complete every set before finishing the exercise" }
        val now = now()
        val updated = current.copy(status = "COMPLETED", endedAt = now, note = note.trim(), updatedAt = now)
        dao.updateExerciseSession(updated)
        outbox.upsert(SyncEntityType.EXERCISE_SESSIONS, updated.id, updated.serverVersion, updated.syncData(), now)
    }

    override suspend fun skipExercise(exerciseSessionId: String, reason: String) = database.withTransaction {
        val current = requireNotNull(dao.getExerciseSession(exerciseSessionId))
        require(current.status == "ACTIVE" || current.status == "PENDING") { "Only an open exercise can be skipped" }
        require(dao.getWorkout(current.workoutSessionId)?.status == "ACTIVE") { "Workout is not active" }
        val now = now()
        val updated = current.copy(status = "SKIPPED", skippedAt = now, endedAt = now, skipReason = reason.trim(), updatedAt = now)
        dao.updateExerciseSession(updated)
        outbox.upsert(SyncEntityType.EXERCISE_SESSIONS, updated.id, updated.serverVersion, updated.syncData(), now)
    }

    override suspend fun swapExercise(exerciseSessionId: String, replacementExerciseId: String) = database.withTransaction {
        val session = requireNotNull(dao.getExerciseSession(exerciseSessionId))
        require(session.status == "ACTIVE" || session.status == "PENDING") { "Only an open exercise can be swapped" }
        require(dao.getWorkout(session.workoutSessionId)?.status == "ACTIVE") { "Workout is not active" }
        require(dao.getSetLogs(exerciseSessionId).none { it.completedAt != null }) { "Exercise cannot be swapped after its first completed set" }
        val replacement = requireNotNull(dao.getExercise(replacementExerciseId))
        val now = now()
        // Only this session's live exercise pointer changes — the plan template
        // (plan_exercises) is never touched, matching "swap by default does not
        // change the template".
        val updated = session.copy(exerciseId = replacement.id, exerciseNameSnapshot = replacement.name, updatedAt = now)
        dao.updateExerciseSession(updated)
        outbox.upsert(SyncEntityType.EXERCISE_SESSIONS, updated.id, updated.serverVersion, updated.syncData(), now)
    }

    override suspend fun finishWorkout(workoutId: String, note: String): WorkoutSession = database.withTransaction {
        val workout = requireNotNull(dao.getActiveWorkout()) { "No active workout" }
        require(workout.id == workoutId) { "workoutId does not match the active workout" }
        val now = now()
        val sessions = dao.getExerciseSessions(workoutId)
        sessions.filter { it.status == "ACTIVE" || it.status == "PENDING" }.forEach {
            val skipped = it.copy(status = "SKIPPED", skippedAt = now, endedAt = now, skipReason = "Workout finished", updatedAt = now)
            dao.updateExerciseSession(skipped)
            outbox.upsert(SyncEntityType.EXERCISE_SESSIONS, skipped.id, skipped.serverVersion, skipped.syncData(), now)
        }
        val updated = workout.copy(status = "COMPLETED", endedAt = now, note = note.trim(), fitbitSyncStatus = "PENDING", updatedAt = now)
        dao.updateWorkout(updated)
        outbox.upsert(SyncEntityType.WORKOUT_SESSIONS, updated.id, updated.serverVersion, updated.syncData(), now)
        updated.toDomain()
    }

    override suspend fun cancelWorkout(workoutId: String) = database.withTransaction {
        val workout = requireNotNull(dao.getWorkout(workoutId))
        require(workout.status == "ACTIVE") { "Only an active workout can be cancelled" }
        val now = now()
        dao.getExerciseSessions(workoutId).filter { it.status == "ACTIVE" || it.status == "PENDING" }.forEach {
            val skipped = it.copy(status = "SKIPPED", skippedAt = now, endedAt = now, skipReason = "Workout cancelled", updatedAt = now)
            dao.updateExerciseSession(skipped)
            outbox.upsert(SyncEntityType.EXERCISE_SESSIONS, skipped.id, skipped.serverVersion, skipped.syncData(), now)
        }
        val cancelled = workout.copy(status = "CANCELLED", endedAt = now, updatedAt = now)
        dao.updateWorkout(cancelled)
        outbox.upsert(SyncEntityType.WORKOUT_SESSIONS, cancelled.id, cancelled.serverVersion, cancelled.syncData(), now)
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
            suggestion.copy(
                status = if (accept) "ACCEPTED" else "REJECTED",
                decisionReason = reason,
                decidedAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun now(): Long = Instant.now().toEpochMilli()
}
