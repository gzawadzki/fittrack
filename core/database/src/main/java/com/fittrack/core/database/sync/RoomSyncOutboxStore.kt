package com.fittrack.core.database.sync

import androidx.room.withTransaction
import com.fittrack.core.database.FitTrackDatabase
import com.fittrack.core.database.entity.CoachSuggestionEntity
import com.fittrack.core.database.entity.ExerciseEntity
import com.fittrack.core.database.entity.ExerciseSessionEntity
import com.fittrack.core.database.entity.PlanExerciseEntity
import com.fittrack.core.database.entity.SetLogEntity
import com.fittrack.core.database.entity.SyncOutboxEntity
import com.fittrack.core.database.entity.SyncStateEntity
import com.fittrack.core.database.entity.UserProfileEntity
import com.fittrack.core.database.entity.WorkoutPlanEntity
import com.fittrack.core.database.entity.WorkoutSessionEntity
import com.fittrack.core.database.mapper.PlanSnapshotJson
import com.fittrack.core.network.OperationType
import com.fittrack.core.network.RemoteChange
import com.fittrack.core.network.SyncBatchRequest
import com.fittrack.core.network.SyncBatchResponse
import com.fittrack.core.network.SyncOperation
import com.fittrack.core.network.SyncOutboxStore
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class RoomSyncOutboxStore(private val database: FitTrackDatabase) : SyncOutboxStore {
    private val dao = database.dao()

    override suspend fun prepareBatch(limit: Int): SyncBatchRequest = database.withTransaction {
        require(limit in 1..500) { "Batch size must be in 1..500" }
        val state = dao.getSyncState() ?: SyncStateEntity().also { dao.upsertSyncState(it) }
        state.blockedReason?.let { throw SyncBlockedException(it) }
        state.activeBatchId?.let { activeId ->
            return@withTransaction SyncBatchRequest(
                batchId = activeId,
                cursor = state.cursor,
                operations = dao.getSyncForBatch(activeId).map(::toOperation),
            )
        }

        bootstrapUnsyncedRows()
        val rows = dao.getPendingSync(limit).map { hydrateLegacyPayload(it) }
        val batchId = UUID.randomUUID().toString()
        if (rows.isNotEmpty()) dao.assignSyncBatch(rows.map(SyncOutboxEntity::id), batchId)
        dao.upsertSyncState(state.copy(activeBatchId = batchId))
        SyncBatchRequest(
            batchId = batchId,
            cursor = state.cursor,
            operations = rows.map(::toOperation),
        )
    }

    override suspend fun completeBatch(batchId: String, response: SyncBatchResponse) {
        database.withTransaction {
            val state = requireNotNull(dao.getSyncState()) { "Sync state is missing" }
            require(state.activeBatchId == batchId) { "Response does not match the active sync batch" }
            require(response.nextCursor >= state.cursor) { "Sync cursor cannot move backwards" }

            val active = dao.getSyncForBatch(batchId)
            val activeIds = active.mapTo(mutableSetOf(), SyncOutboxEntity::id)
            require(response.ackedOperationIds.all(activeIds::contains)) {
                "Server acknowledged an operation outside the active batch"
            }

            if (response.ackedOperationIds.isNotEmpty()) {
                dao.markSynced(response.ackedOperationIds, Instant.now().toEpochMilli())
            }
            if (response.ackedOperationIds.isEmpty()) {
                dao.releaseBatch(batchId)
            } else {
                dao.releaseUnacked(batchId, response.ackedOperationIds)
            }

            // fitbit_daily_metrics/fitbit_hr_exercise_aggregates are pushed
            // straight to Supabase by HealthConnectSyncGateway, bypassing
            // this outbox entirely (see that class's own doc comment) - this
            // module has no local table shaped to receive them back as
            // pulled RemoteChanges. Skipping them here (rather than the
            // previous `require`, which crashed and rolled back the *whole*
            // pull transaction the moment cursor 0 first pulled any HC row)
            // is a deliberate, documented gap: those two entity types are
            // effectively push-only from this client's perspective today.
            // Reconciling a second device's HC pushes into local HC tables
            // is real future work, not something to improvise here.
            response.changes.filter { it.entityType in SyncEntityType.pullable }.sortedWith(
                compareBy<RemoteChange>({ remoteApplyPriority(it.entityType) }, { it.id }),
            ).forEach { change ->
                val hasNewerLocalMutation = dao.getPendingSyncForEntity(change.entityType, change.id).isNotEmpty()
                if (hasNewerLocalMutation) {
                    updateServerVersion(change.entityType, change.id, change.version)
                    dao.rebasePendingSync(change.entityType, change.id, change.version)
                } else {
                    applyRemote(change)
                }
            }

            // A VERSION_CONFLICT defers the whole batch, although normally only
            // one operation caused it. Once the pull cursor advances, every
            // unaffected operation is safe to retry; the conflicting entity was
            // rebased above. This also lets a locally cancelled duplicate ACTIVE
            // workout upload as a non-active CREATE after server reconciliation.
            if (response.nextCursor > state.cursor) {
                dao.reactivateRetryableWaitingSync()
            }

            val unresolvedConflict = dao.waitingForRebaseCount() > 0 && response.nextCursor == state.cursor
            dao.upsertSyncState(
                state.copy(
                    cursor = response.nextCursor,
                    activeBatchId = null,
                    blockedReason = if (unresolvedConflict) VERSION_CONFLICT_NO_REMOTE_CHANGE else null,
                ),
            )
        }
    }

    override suspend fun recordAttempt(batchId: String, attemptedAtEpochMillis: Long) {
        database.withTransaction {
            val state = requireNotNull(dao.getSyncState()) { "Sync state is missing" }
            require(state.activeBatchId == batchId) { "Attempt does not match the active sync batch" }
            dao.recordBatchAttempt(batchId, attemptedAtEpochMillis)
        }
    }

    override suspend fun deferBatchForConflict(batchId: String, attemptedAtEpochMillis: Long) {
        database.withTransaction {
            val state = requireNotNull(dao.getSyncState()) { "Sync state is missing" }
            require(state.activeBatchId == batchId) { "Conflict does not match the active sync batch" }
            dao.deferBatchForConflict(batchId, attemptedAtEpochMillis)
            dao.upsertSyncState(state.copy(activeBatchId = null))
        }
    }

    override suspend fun cleanupRawHeartRate(beforeEpochMillis: Long): Int =
        dao.deleteHeartRateBefore(beforeEpochMillis)

    private suspend fun bootstrapUnsyncedRows() {
        val writer = SyncOutboxWriter(dao)
        val now = Instant.now().toEpochMilli()
        suspend fun enqueueIfMissing(type: String, id: String, payload: String) {
            if (!dao.hasAnySync(type, id)) writer.upsert(type, id, null, payload, now)
        }
        dao.getUnsyncedProfiles().forEach { enqueueIfMissing(SyncEntityType.USER_PROFILES, it.id, it.syncData()) }
        dao.getUnsyncedExercises().forEach { enqueueIfMissing(SyncEntityType.EXERCISES, it.id, it.syncData()) }
        dao.getUnsyncedPlans().forEach { enqueueIfMissing(SyncEntityType.WORKOUT_PLANS, it.id, it.syncData()) }
        dao.getUnsyncedPlanExercises().forEach { enqueueIfMissing(SyncEntityType.PLAN_EXERCISES, it.id, it.syncData()) }
        dao.getUnsyncedWorkouts().forEach { enqueueIfMissing(SyncEntityType.WORKOUT_SESSIONS, it.id, it.syncData()) }
        dao.getUnsyncedExerciseSessions().forEach { enqueueIfMissing(SyncEntityType.EXERCISE_SESSIONS, it.id, it.syncData()) }
        dao.getUnsyncedSetLogs().forEach { enqueueIfMissing(SyncEntityType.SET_LOGS, it.id, it.syncData()) }
    }

    private suspend fun hydrateLegacyPayload(row: SyncOutboxEntity): SyncOutboxEntity {
        if (row.operation == "DELETE") return row.copy(payloadJson = "{}")
        val parsed = runCatching { JSONObject(row.payloadJson) }.getOrNull()
        val isLegacyIdOnly = parsed == null || parsed.length() == 0 ||
            (parsed.length() == 1 && parsed.has("id"))
        if (!isLegacyIdOnly) return row
        val hydrated = row.copy(payloadJson = currentPayload(row.entityType, row.entityId))
        dao.updateSync(hydrated)
        return hydrated
    }

    private suspend fun currentPayload(entityType: String, entityId: String): String = when (entityType) {
        SyncEntityType.USER_PROFILES -> requireNotNull(dao.getProfileById(entityId)).syncData()
        SyncEntityType.EXERCISES -> requireNotNull(dao.getExercise(entityId)).syncData()
        SyncEntityType.WORKOUT_PLANS -> requireNotNull(dao.getPlanIncludingDeleted(entityId)).syncData()
        SyncEntityType.PLAN_EXERCISES -> requireNotNull(dao.getPlanExerciseIncludingDeleted(entityId)).syncData()
        SyncEntityType.WORKOUT_SESSIONS -> requireNotNull(dao.getWorkoutIncludingDeleted(entityId)).syncData()
        SyncEntityType.EXERCISE_SESSIONS -> requireNotNull(dao.getExerciseSession(entityId)).syncData()
        SyncEntityType.SET_LOGS -> requireNotNull(dao.getSetLog(entityId)).syncData()
        else -> error("Entity type $entityType is not writable")
    }

    private fun toOperation(row: SyncOutboxEntity): SyncOperation = SyncOperation(
        operationId = row.id,
        entityType = row.entityType,
        entityId = row.entityId,
        op = OperationType.valueOf(row.operation),
        baseVersion = row.baseVersion,
        dataJson = if (row.operation == "DELETE") "{}" else row.payloadJson,
    )

    private suspend fun updateServerVersion(entityType: String, id: String, version: Int) {
        when (entityType) {
            SyncEntityType.USER_PROFILES -> dao.updateProfileServerVersion(id, version)
            SyncEntityType.EXERCISES -> dao.updateExerciseServerVersion(id, version)
            SyncEntityType.WORKOUT_PLANS -> dao.updatePlanServerVersion(id, version)
            SyncEntityType.PLAN_EXERCISES -> dao.updatePlanExerciseServerVersion(id, version)
            SyncEntityType.WORKOUT_SESSIONS -> dao.updateWorkoutServerVersion(id, version)
            SyncEntityType.EXERCISE_SESSIONS -> dao.updateExerciseSessionServerVersion(id, version)
            SyncEntityType.SET_LOGS -> dao.updateSetLogServerVersion(id, version)
            SyncEntityType.COACH_SUGGESTIONS -> dao.updateSuggestionServerVersion(id, version)
            else -> error("Unsupported remote entity type $entityType")
        }
    }

    private suspend fun applyRemote(change: RemoteChange) {
        val data = JSONObject(change.dataJson)
        val updatedAt = change.updatedAtUtc.isoMillis()
        val deletedAt = change.deletedAtUtc?.isoMillis()
        when (change.entityType) {
            SyncEntityType.USER_PROFILES -> dao.upsertProfile(
                UserProfileEntity(
                    id = change.id,
                    goal = data.requiredString("goal"),
                    experience = data.requiredString("experience"),
                    equipmentCsv = data.getJSONArray("equipment").stringList().joinToString(","),
                    schedule = data.getString("schedule"),
                    constraints = data.getString("constraints"),
                    preferences = data.getString("preferences"),
                    approved = data.getBoolean("approved"),
                    version = data.optInt("profile_version", 1).coerceAtLeast(1),
                    updatedAt = updatedAt,
                    serverVersion = change.version,
                    deletedAt = deletedAt,
                ),
            )
            SyncEntityType.EXERCISES -> dao.upsertExercises(
                listOf(
                    ExerciseEntity(
                        id = change.id,
                        name = data.requiredString("name"),
                        movementPattern = data.requiredString("movement_pattern"),
                        muscleRole = data.requiredString("muscle_role"),
                        equipment = data.requiredString("equipment"),
                        active = data.getBoolean("active"),
                        updatedAt = updatedAt,
                        serverVersion = change.version,
                        deletedAt = deletedAt,
                    ),
                ),
            )
            SyncEntityType.WORKOUT_PLANS -> dao.upsertPlans(
                listOf(
                    WorkoutPlanEntity(
                        id = change.id,
                        name = data.requiredString("name"),
                        dayLabel = data.getString("day_label"),
                        scheduledDayOfWeek = data.nullableInt("scheduled_day_of_week"),
                        scheduledMinuteOfDay = data.nullableInt("scheduled_minute_of_day"),
                        version = data.optInt("plan_version", 1).coerceAtLeast(1),
                        active = data.getBoolean("active"),
                        updatedAt = updatedAt,
                        serverVersion = change.version,
                        deletedAt = deletedAt,
                    ),
                ),
            )
            SyncEntityType.PLAN_EXERCISES -> dao.upsertPlanExercises(
                listOf(
                    PlanExerciseEntity(
                        id = change.id,
                        planId = data.requiredString("plan_id"),
                        exerciseId = data.requiredString("exercise_id"),
                        position = data.getInt("position"),
                        warmupSets = data.getInt("warmup_sets"),
                        workingSets = data.getInt("working_sets"),
                        repMin = data.getInt("rep_min"),
                        repMax = data.getInt("rep_max"),
                        targetRir = data.nullableInt("target_rir"),
                        startingWeightKg = data.getDouble("starting_weight_kg"),
                        restSeconds = data.getInt("rest_seconds"),
                        weightIncrementKg = data.getDouble("weight_increment_kg"),
                        notes = data.getString("notes"),
                        updatedAt = updatedAt,
                        serverVersion = change.version,
                        deletedAt = deletedAt,
                    ),
                ),
            )
            SyncEntityType.WORKOUT_SESSIONS -> {
                val snapshot = data.getJSONObject("plan_snapshot_json")
                PlanSnapshotJson.fromJson(snapshot.toString())
                val incoming = WorkoutSessionEntity(
                        id = change.id,
                        planId = data.requiredString("plan_id"),
                        planNameSnapshot = data.requiredString("plan_name_snapshot"),
                        planVersionSnapshot = data.getInt("plan_version_snapshot"),
                        dayLabelSnapshot = data.getString("day_label_snapshot"),
                        planSnapshotJson = snapshot.toString(),
                        status = data.requiredString("status"),
                        startedAt = data.requiredString("started_at").isoMillis(),
                        endedAt = data.nullableString("ended_at")?.isoMillis(),
                        note = data.getString("note"),
                        fitbitSyncStatus = data.requiredString("fitbit_sync_status"),
                        updatedAt = updatedAt,
                        serverVersion = change.version,
                        deletedAt = deletedAt,
                    )
                if (incoming.status == "ACTIVE" && incoming.deletedAt == null) {
                    reconcileActiveWorkout(incoming)
                }
                dao.upsertWorkout(incoming)
            }
            SyncEntityType.EXERCISE_SESSIONS -> {
                val incoming = ExerciseSessionEntity(
                    id = change.id,
                    workoutSessionId = data.requiredString("workout_session_id"),
                    planExerciseId = data.requiredString("plan_exercise_id"),
                    exerciseId = data.requiredString("exercise_id"),
                    originalExerciseId = data.requiredString("original_exercise_id"),
                    exerciseNameSnapshot = data.requiredString("exercise_name_snapshot"),
                    position = data.getInt("position"),
                    restSecondsSnapshot = data.getInt("rest_seconds_snapshot"),
                    status = data.requiredString("status"),
                    startedAt = data.nullableString("started_at")?.isoMillis(),
                    endedAt = data.nullableString("ended_at")?.isoMillis(),
                    skippedAt = data.nullableString("skipped_at")?.isoMillis(),
                    skipReason = data.getString("skip_reason"),
                    note = data.getString("note"),
                    updatedAt = updatedAt,
                    serverVersion = change.version,
                    deletedAt = deletedAt,
                )
                val parent = requireNotNull(dao.getWorkoutIncludingDeleted(incoming.workoutSessionId)) {
                    "Parent workout ${incoming.workoutSessionId} is missing"
                }
                val corrected = if (
                    (incoming.status == "ACTIVE" || incoming.status == "PENDING") &&
                    (parent.status != "ACTIVE" || parent.deletedAt != null)
                ) {
                    val reconciledAt = maxOf(incoming.updatedAt, incoming.startedAt ?: incoming.updatedAt)
                    incoming.copy(
                        status = "SKIPPED",
                        endedAt = incoming.endedAt ?: reconciledAt,
                        skippedAt = incoming.skippedAt ?: reconciledAt,
                        skipReason = incoming.skipReason.ifBlank { ACTIVE_RECONCILIATION_REASON },
                        updatedAt = reconciledAt,
                    )
                } else {
                    incoming
                }
                dao.upsertExerciseSession(corrected)
                if (corrected != incoming) {
                    SyncOutboxWriter(dao).upsert(
                        SyncEntityType.EXERCISE_SESSIONS,
                        corrected.id,
                        corrected.serverVersion,
                        corrected.syncData(),
                        corrected.updatedAt,
                    )
                }
            }
            SyncEntityType.SET_LOGS -> dao.upsertSetLog(
                SetLogEntity(
                    id = change.id,
                    exerciseSessionId = data.requiredString("exercise_session_id"),
                    position = data.getInt("position"),
                    setType = data.requiredString("set_type"),
                    targetRepMin = data.getInt("target_rep_min"),
                    targetRepMax = data.getInt("target_rep_max"),
                    targetRir = data.nullableInt("target_rir"),
                    targetWeightKg = data.getDouble("target_weight_kg"),
                    actualReps = data.nullableInt("actual_reps"),
                    actualWeightKg = data.nullableDouble("actual_weight_kg"),
                    actualRir = data.nullableInt("actual_rir"),
                    pain = data.getBoolean("pain"),
                    techniqueIssue = data.getBoolean("technique_issue"),
                    concerningSymptom = data.getBoolean("concerning_symptom"),
                    completedAt = data.nullableString("completed_at")?.isoMillis(),
                    skipped = data.optBoolean("skipped"),
                    updatedAt = updatedAt,
                    serverVersion = change.version,
                    deletedAt = deletedAt,
                ),
            )
            SyncEntityType.COACH_SUGGESTIONS -> dao.upsertSuggestion(
                CoachSuggestionEntity(
                    id = change.id,
                    type = data.requiredString("type"),
                    targetId = data.requiredString("target_id"),
                    beforeValue = data.getString("before_value"),
                    afterValue = data.getString("after_value"),
                    reasonCode = data.requiredString("reason_code"),
                    explanation = data.requiredString("explanation"),
                    signalsJson = data.getJSONArray("signals").toString(),
                    citationsJson = data.getJSONArray("citations").toString(),
                    confidence = data.requiredString("confidence"),
                    status = data.requiredString("status"),
                    decisionReason = data.getString("decision_reason"),
                    createdAt = data.requiredString("created_at").isoMillis(),
                    decidedAt = data.nullableString("decided_at")?.isoMillis(),
                    updatedAt = updatedAt,
                    serverVersion = change.version,
                    deletedAt = deletedAt,
                ),
            )
            else -> error("Unsupported remote entity type ${change.entityType}")
        }
    }

    /**
     * The incoming server row is authoritative. The server has a partial
     * unique index, so any other local ACTIVE row is necessarily an offline
     * loser that never reached the server (or legacy data being repaired).
     * Cancelling it and its open exercises keeps Room reconstructable and
     * queues complete, idempotent payloads for the next push.
     */
    private suspend fun reconcileActiveWorkout(winner: WorkoutSessionEntity) {
        val writer = SyncOutboxWriter(dao)
        dao.getActiveWorkouts().filter { it.id != winner.id }.forEach { loser ->
            val reconciledAt = maxOf(winner.updatedAt, loser.updatedAt, loser.startedAt)
            dao.getExerciseSessions(loser.id)
                .filter { it.status == "ACTIVE" || it.status == "PENDING" }
                .forEach { exercise ->
                    val skipped = exercise.copy(
                        status = "SKIPPED",
                        endedAt = exercise.endedAt ?: reconciledAt,
                        skippedAt = exercise.skippedAt ?: reconciledAt,
                        skipReason = exercise.skipReason.ifBlank { ACTIVE_RECONCILIATION_REASON },
                        updatedAt = reconciledAt,
                    )
                    dao.updateExerciseSession(skipped)
                    writer.upsert(
                        SyncEntityType.EXERCISE_SESSIONS,
                        skipped.id,
                        skipped.serverVersion,
                        skipped.syncData(),
                        reconciledAt,
                    )
                }

            val cancelled = loser.copy(
                status = "CANCELLED",
                endedAt = loser.endedAt ?: reconciledAt,
                updatedAt = reconciledAt,
            )
            dao.updateWorkout(cancelled)
            writer.upsert(
                SyncEntityType.WORKOUT_SESSIONS,
                cancelled.id,
                cancelled.serverVersion,
                cancelled.syncData(),
                reconciledAt,
            )
        }
    }
}

private fun remoteApplyPriority(entityType: String): Int = when (entityType) {
    SyncEntityType.USER_PROFILES -> 0
    SyncEntityType.EXERCISES -> 10
    SyncEntityType.WORKOUT_PLANS -> 20
    SyncEntityType.PLAN_EXERCISES -> 30
    SyncEntityType.WORKOUT_SESSIONS -> 40
    SyncEntityType.EXERCISE_SESSIONS -> 50
    SyncEntityType.SET_LOGS -> 60
    SyncEntityType.COACH_SUGGESTIONS -> 70
    else -> Int.MAX_VALUE
}

/**
 * Terminal local sync state. Retrying the same cursor cannot resolve the
 * version conflict because the server returned no newer remote row.
 */
class SyncBlockedException(reason: String) : IllegalStateException(reason)

private const val VERSION_CONFLICT_NO_REMOTE_CHANGE = "VERSION_CONFLICT_NO_REMOTE_CHANGE"
private const val ACTIVE_RECONCILIATION_REASON = "Another active workout was restored from sync"

private fun JSONArray.stringList(): List<String> =
    (0 until length()).map { getString(it) }
