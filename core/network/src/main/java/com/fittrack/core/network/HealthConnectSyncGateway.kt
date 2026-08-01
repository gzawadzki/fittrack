package com.fittrack.core.network

import com.fittrack.core.model.ExerciseHeartRateSummary
import com.fittrack.core.model.FitbitDataPayload
import com.fittrack.core.model.FitbitSyncStatus
import com.fittrack.core.model.HealthDay
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

/**
 * Maps [HealthConnectGateway] output onto the domain types already declared
 * in core/model (`HealthDay`, `FitbitDataPayload`) so callers can hand
 * results straight to the existing `HealthRepository`/`FitbitDataRepository`
 * interfaces (`core/model/repository/Repositories.kt`) — unchanged by this
 * module, they already fit.
 *
 * Also pushes the same data to Supabase through the existing `/sync-batch`
 * contract (`SyncClient`/`SyncBatchRequest`, same as every other entity
 * type), using the two entity types this module added to
 * `supabase/functions/_shared/entities.ts`: `fitbit_daily_metrics` and
 * `fitbit_hr_exercise_aggregates`.
 *
 * Known limitation (documented, not silently glossed over): this pushes
 * directly via [SyncClient] rather than through core/database's local
 * outbox (`core/database/sync/RoomSyncOutboxStore.kt`). A fully durable,
 * offline-safe push — survives app kill before the network call completes,
 * retried by the normal periodic `SyncWorker` — requires a local Room-backed
 * cache + outbox entry for these two entity types, which needs new Room
 * entities/DAO methods in `core/database/entity/Entities.kt` and
 * `core/database/FitTrackDao.kt`. Those files are large, monolithic, and
 * actively owned/edited by M1 in a separate worktree; changing them from
 * this module risked exactly the kind of cross-module collision the
 * multi-agent convention in IMPLEMENTATION_PLAN.md section 7 exists to
 * avoid ("Jeden agent jest właścicielem jednej ścieżki"). This class is the
 * documented interim: it uses the same wire contract and entity whitelist,
 * so once that follow-up lands, callers only need to swap where they call
 * [pushDailyMetrics]/[pushWorkoutAggregates] from (this class -> the local
 * outbox) without touching the Health Connect read side at all.
 */
class HealthConnectSyncGateway(
    private val syncClient: SyncClient,
    private val newBatchId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Instant = Instant::now,
) {
    /** Pushes daily metrics as CREATE operations against `fitbit_daily_metrics`.
     * Ids are deterministic (derived from the date), so re-pushing the same
     * day is idempotent at the row-identity level even though this gateway
     * does not track server versions across calls (see class doc). */
    suspend fun pushDailyMetrics(metrics: List<HealthConnectDailyMetric>): SyncResult {
        val operations = metrics.map { metric ->
            SyncOperation(
                operationId = UUID.randomUUID().toString(),
                entityType = "fitbit_daily_metrics",
                entityId = stableId("health-connect-daily", metric.date),
                op = OperationType.CREATE,
                baseVersion = null,
                dataJson = JSONObject()
                    .put("metric_date", metric.date)
                    .putOrNull("sleep_minutes", metric.sleepMinutes)
                    .putOrNull("resting_heart_rate", metric.restingHeartRate)
                    .putOrNull("steps", metric.steps)
                    .put("raw", JSONObject().put("source", "HEALTH_CONNECT"))
                    .toString(),
            )
        }
        return push(operations)
    }

    /** Pushes per-exercise HR aggregates as CREATE operations against
     * `fitbit_hr_exercise_aggregates`. [workoutSessionId]/exercise session id
     * pair gives a deterministic id, same idempotency caveat as above. */
    suspend fun pushWorkoutAggregates(
        workoutSessionId: String,
        aggregates: List<ExerciseHrAggregate>,
    ): SyncResult {
        val operations = aggregates.map { aggregate ->
            SyncOperation(
                operationId = UUID.randomUUID().toString(),
                entityType = "fitbit_hr_exercise_aggregates",
                entityId = stableId("health-connect-hr-agg", "$workoutSessionId:${aggregate.exerciseId}"),
                op = OperationType.CREATE,
                baseVersion = null,
                dataJson = JSONObject()
                    .put("workout_session_id", workoutSessionId)
                    .put("exercise_id", aggregate.exerciseId)
                    .putOrNull("avg_bpm", aggregate.avgBpm)
                    .putOrNull("max_bpm", aggregate.maxBpm)
                    .putOrNull("recovery_delta_bpm", aggregate.recoveryDeltaBpm)
                    .put("sample_count", aggregate.sampleCount)
                    .toString(),
            )
        }
        return push(operations)
    }

    private suspend fun push(operations: List<SyncOperation>): SyncResult {
        if (operations.isEmpty()) {
            return SyncResult.Success(SyncBatchResponse(emptyList(), emptyList(), 0))
        }
        return syncClient.sync(
            SyncBatchRequest(
                batchId = newBatchId(),
                cursor = 0,
                operations = operations,
            ),
        )
    }

    private fun stableId(namespace: String, key: String): String {
        // Deterministic v3-style UUID (name-based) so re-pushing the same
        // logical row (same date, or same workout+exercise pair) always
        // targets the same entity id — mirrors
        // RoomFitbitDataRepository.stableCardioId's UUID.nameUUIDFromBytes
        // convention used elsewhere in this codebase for the same reason.
        val digest = MessageDigest.getInstance("MD5")
            .digest("$namespace:$key".toByteArray(Charsets.UTF_8))
        return UUID.nameUUIDFromBytes(digest).toString()
    }

    private fun JSONObject.putOrNull(name: String, value: Int?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.putOrNull(name: String, value: Double?): JSONObject =
        put(name, value ?: JSONObject.NULL)
}

/** Maps Health Connect daily metrics onto the frozen `HealthDay` domain type
 * (`core/model/DomainTypes.kt`) for local persistence via `HealthRepository`. */
fun List<HealthConnectDailyMetric>.toHealthDays(): List<HealthDay> = map { metric ->
    HealthDay(
        date = metric.date,
        sleepMinutes = metric.sleepMinutes,
        restingHeartRate = metric.restingHeartRate,
        steps = metric.steps,
        source = "HEALTH_CONNECT",
    )
}

/** Maps a workout's Health Connect HR read onto the frozen `FitbitDataPayload`
 * domain type for local persistence via `FitbitDataRepository.persist`. Field
 * name kept as `FitbitDataPayload`/`ExerciseHeartRateSummary` — these are
 * shared, source-agnostic domain contracts predating this module's Fitbit ->
 * Health Connect rename; renaming them is a cross-module contract change
 * outside this module's scope (would need the ADR process in
 * IMPLEMENTATION_PLAN.md section 6, touching core/model which M1 owns). */
fun WorkoutHeartRateReadResult.toFitbitDataPayload(
    workoutSessionId: String,
    workoutSyncStatus: FitbitSyncStatus,
    syncedAt: Instant,
): FitbitDataPayload = FitbitDataPayload(
    workoutSessionId = workoutSessionId,
    workoutSyncStatus = workoutSyncStatus,
    dailyHealth = emptyList(),
    cardioActivities = emptyList(),
    heartRateSamples = samples.map { sample ->
        com.fittrack.core.model.HeartRateSample(
            workoutSessionId = workoutSessionId,
            sampledAt = sample.sampledAt,
            bpm = sample.bpm,
        )
    },
    exerciseHeartRateSummaries = aggregates.map { aggregate ->
        ExerciseHeartRateSummary(
            exerciseSessionId = aggregate.exerciseId,
            averageBpm = aggregate.avgBpm?.let { Math.round(it).toInt() },
            maximumBpm = aggregate.maxBpm,
            recoveryDeltaBpm = aggregate.recoveryDeltaBpm,
            sampleCount = aggregate.sampleCount,
        )
    },
    syncedAt = syncedAt,
)
