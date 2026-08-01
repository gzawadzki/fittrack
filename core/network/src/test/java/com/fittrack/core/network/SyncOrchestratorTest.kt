package com.fittrack.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncOrchestratorTest {
    @Test
    fun `completes the persisted batch atomically after success`() = runTest {
        val request = request(listOf(operation(OPERATION_ONE), operation(OPERATION_TWO)))
        val response = SyncBatchResponse(
            ackedOperationIds = listOf(OPERATION_ONE),
            changes = listOf(remoteChange()),
            nextCursor = 2L,
        )
        val store = FakeOutbox(request)
        val client = RecordingClient(SyncResult.Success(response))

        val result = SyncOrchestrator(store, client).syncOnce(batchSize = 37)

        assertEquals(SyncResult.Success(response), result)
        assertEquals(37, store.preparedLimit)
        assertEquals(request, client.receivedRequest)
        assertEquals(BATCH_ID, store.completedBatchId)
        assertEquals(response, store.completedResponse)
        assertNull(store.attemptedBatchId)
    }

    @Test
    fun `records a failed attempt against the persisted batch id`() = runTest {
        val request = request(listOf(operation(OPERATION_ONE)))
        val failure = SyncResult.Failure(NetworkError("OFFLINE", "Brak internetu", retryable = true))
        val store = FakeOutbox(request)

        val result = SyncOrchestrator(store, RecordingClient(failure), nowMillis = { 1234L }).syncOnce()

        assertEquals(failure, result)
        assertEquals(BATCH_ID, store.attemptedBatchId)
        assertEquals(1234L, store.attemptedAt)
        assertNull(store.completedBatchId)
    }

    @Test
    fun `version conflict defers stale operations for a pull-only rebase`() = runTest {
        val request = request(listOf(operation(OPERATION_ONE)))
        val failure = SyncResult.Failure(NetworkError("VERSION_CONFLICT", "Conflict", retryable = true))
        val store = FakeOutbox(request)

        SyncOrchestrator(store, RecordingClient(failure), nowMillis = { 9876L }).syncOnce()

        assertEquals(BATCH_ID, store.deferredBatchId)
        assertEquals(9876L, store.deferredAt)
        assertNull(store.attemptedBatchId)
    }

    private fun request(operations: List<SyncOperation>) = SyncBatchRequest(
        batchId = BATCH_ID,
        cursor = 1L,
        operations = operations,
    )

    private fun operation(id: String) = SyncOperation(
        operationId = id,
        entityType = "workout_sessions",
        entityId = ENTITY_ID,
        op = OperationType.UPDATE,
        baseVersion = 1,
        dataJson = "{\"status\":\"COMPLETED\"}",
    )

    private fun remoteChange() = RemoteChange(
        entityType = "workout_sessions",
        id = ENTITY_ID,
        version = 2,
        updatedAtUtc = "2026-08-01T12:00:00.000Z",
        deletedAtUtc = null,
        dataJson = "{\"id\":\"$ENTITY_ID\",\"version\":2}",
    )

    private class RecordingClient(private val result: SyncResult) : SyncClient {
        var receivedRequest: SyncBatchRequest? = null

        override suspend fun sync(request: SyncBatchRequest): SyncResult {
            receivedRequest = request
            return result
        }
    }

    private class FakeOutbox(private val request: SyncBatchRequest) : SyncOutboxStore {
        var preparedLimit: Int? = null
        var completedBatchId: String? = null
        var completedResponse: SyncBatchResponse? = null
        var attemptedBatchId: String? = null
        var attemptedAt: Long? = null
        var deferredBatchId: String? = null
        var deferredAt: Long? = null

        override suspend fun prepareBatch(limit: Int): SyncBatchRequest {
            preparedLimit = limit
            return request
        }

        override suspend fun completeBatch(batchId: String, response: SyncBatchResponse) {
            completedBatchId = batchId
            completedResponse = response
        }

        override suspend fun recordAttempt(batchId: String, attemptedAtEpochMillis: Long) {
            attemptedBatchId = batchId
            attemptedAt = attemptedAtEpochMillis
        }

        override suspend fun deferBatchForConflict(batchId: String, attemptedAtEpochMillis: Long) {
            deferredBatchId = batchId
            deferredAt = attemptedAtEpochMillis
        }

        override suspend fun cleanupRawHeartRate(beforeEpochMillis: Long) = 0
    }

    private companion object {
        const val BATCH_ID = "10000000-0000-0000-0000-000000000001"
        const val OPERATION_ONE = "20000000-0000-0000-0000-000000000001"
        const val OPERATION_TWO = "20000000-0000-0000-0000-000000000002"
        const val ENTITY_ID = "30000000-0000-0000-0000-000000000001"
    }
}
