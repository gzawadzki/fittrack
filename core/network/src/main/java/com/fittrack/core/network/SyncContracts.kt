package com.fittrack.core.network

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SyncOperation(
    val operationId: String,
    val entityType: String,
    val entityId: String,
    val op: OperationType,
    val baseVersion: Int?,
    val dataJson: String,
) {
    init {
        requireUuid(operationId, "operationId")
        require(entityType.isNotBlank()) { "entityType must not be blank" }
        requireUuid(entityId, "entityId")
        when (op) {
            OperationType.CREATE -> require(baseVersion == null) { "CREATE requires a null baseVersion" }
            OperationType.UPDATE,
            OperationType.DELETE,
            -> require(baseVersion != null && baseVersion > 0) {
                "UPDATE and DELETE require a positive baseVersion"
            }
        }
        requireJsonObject(dataJson, "dataJson")
    }
}

enum class OperationType { CREATE, UPDATE, DELETE }

data class RemoteChange(
    val entityType: String,
    val id: String,
    val version: Int,
    val updatedAtUtc: String,
    val deletedAtUtc: String?,
    val dataJson: String,
) {
    init {
        require(entityType.isNotBlank()) { "entityType must not be blank" }
        requireUuid(id, "id")
        require(version > 0) { "version must be positive" }
        require(updatedAtUtc.isNotBlank()) { "updatedAtUtc must not be blank" }
        requireJsonObject(dataJson, "dataJson")
    }
}

data class SyncBatchRequest(
    val batchId: String,
    val cursor: Long,
    val operations: List<SyncOperation>,
) {
    init {
        requireUuid(batchId, "batchId")
        require(cursor >= 0) { "cursor must be non-negative" }
        require(operations.size <= MAX_OPERATIONS_PER_BATCH) {
            "operations exceeds max batch size of $MAX_OPERATIONS_PER_BATCH"
        }
    }
}

data class SyncBatchResponse(
    val ackedOperationIds: List<String>,
    val changes: List<RemoteChange>,
    val nextCursor: Long,
) {
    init {
        require(nextCursor >= 0) { "nextCursor must be non-negative" }
    }
}

sealed interface SyncResult {
    data class Success(val response: SyncBatchResponse) : SyncResult
    data class Failure(val error: NetworkError) : SyncResult
}

interface SyncClient {
    suspend fun sync(request: SyncBatchRequest): SyncResult
}

/**
 * Database adapter implemented by core/database; network deliberately knows no Room entities.
 *
 * [prepareBatch] must return the same persisted batch id and contents until that batch is
 * completed. [completeBatch] must acknowledge operations, apply remote changes, advance the
 * cursor, and remove the prepared batch in one local database transaction. This makes a retry
 * after a timeout reuse the server's idempotency cache instead of creating a new batch id.
 */
interface SyncOutboxStore {
    suspend fun prepareBatch(limit: Int): SyncBatchRequest
    suspend fun completeBatch(batchId: String, response: SyncBatchResponse)
    suspend fun recordAttempt(batchId: String, attemptedAtEpochMillis: Long)
    /** Quarantines stale-base operations and releases the active batch so the
     * next request is a pull-only batch. A pulled row re-bases the quarantined
     * mutation before it can be sent again. */
    suspend fun deferBatchForConflict(batchId: String, attemptedAtEpochMillis: Long)
    suspend fun cleanupRawHeartRate(beforeEpochMillis: Long): Int
}

internal fun SyncBatchRequest.toJson(): String = JSONObject()
    .put("batchId", batchId)
    .put("cursor", cursor)
    .put("operations", JSONArray().apply { operations.forEach { put(it.toJson()) } })
    .toString()

private fun SyncOperation.toJson() = JSONObject()
    .put("operationId", operationId)
    .put("entityType", entityType)
    .put("entityId", entityId)
    .put("op", op.name)
    .put("baseVersion", baseVersion ?: JSONObject.NULL)
    .put("data", JSONObject(dataJson))

internal fun parseSyncResponse(body: String): SyncBatchResponse {
    val root = JSONObject(body)
    val acked = root.getJSONArray("ackedOperationIds").strings()
    val changes = root.getJSONArray("changes").objects().map { change ->
        RemoteChange(
            entityType = change.getString("entityType"),
            id = change.getString("id"),
            version = change.getInt("version"),
            updatedAtUtc = change.getString("updatedAt"),
            deletedAtUtc = change.nullableString("deletedAt"),
            dataJson = change.getJSONObject("data").toString(),
        )
    }
    return SyncBatchResponse(acked, changes, root.getLong("nextCursor"))
}

private fun JSONObject.nullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else getString(name)

private fun JSONArray.strings() = (0 until length()).map(::getString)
private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)

private fun requireUuid(value: String, name: String) {
    require(runCatching { UUID.fromString(value) }.isSuccess) { "$name must be a UUID" }
}

private fun requireJsonObject(value: String, name: String) {
    require(runCatching { JSONObject(value) }.isSuccess) { "$name must be a JSON object" }
}

private const val MAX_OPERATIONS_PER_BATCH = 500
