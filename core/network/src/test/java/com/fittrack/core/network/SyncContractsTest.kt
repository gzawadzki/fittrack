package com.fittrack.core.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncContractsTest {
    @Test
    fun `serializes the exact sync-batch request contract`() {
        val request = SyncBatchRequest(
            batchId = BATCH_ID,
            cursor = 0L,
            operations = listOf(
                SyncOperation(
                    operationId = OPERATION_ID,
                    entityType = "workout_sessions",
                    entityId = ENTITY_ID,
                    op = OperationType.CREATE,
                    baseVersion = null,
                    dataJson = "{\"status\":\"IN_PROGRESS\"}",
                ),
            ),
        )

        val json = JSONObject(request.toJson())
        val operation = json.getJSONArray("operations").getJSONObject(0)

        assertEquals(BATCH_ID, json.getString("batchId"))
        assertEquals(0L, json.getLong("cursor"))
        assertEquals(OPERATION_ID, operation.getString("operationId"))
        assertEquals("CREATE", operation.getString("op"))
        assertTrue(operation.isNull("baseVersion"))
        assertEquals("IN_PROGRESS", operation.getJSONObject("data").getString("status"))
        assertFalse(operation.has("id"))
        assertFalse(operation.has("operation"))
        assertFalse(operation.has("payload"))
        assertFalse(operation.has("version"))
    }

    @Test
    fun `parses the exact sync-batch response contract`() {
        val response = parseSyncResponse(
            """
            {
              "ackedOperationIds":["$OPERATION_ID"],
              "changes":[{
                "entityType":"workout_sessions",
                "id":"$ENTITY_ID",
                "version":4,
                "updatedAt":"2026-08-01T12:00:00.000Z",
                "deletedAt":null,
                "data":{"id":"$ENTITY_ID","status":"COMPLETED","version":4}
              }],
              "nextCursor":128
            }
            """.trimIndent(),
        )

        assertEquals(listOf(OPERATION_ID), response.ackedOperationIds)
        assertEquals(128L, response.nextCursor)
        with(response.changes.single()) {
            assertEquals("workout_sessions", entityType)
            assertEquals(ENTITY_ID, id)
            assertEquals(4, version)
            assertEquals("2026-08-01T12:00:00.000Z", updatedAtUtc)
            assertNull(deletedAtUtc)
            assertEquals("COMPLETED", JSONObject(dataJson).getString("status"))
        }
    }

    private companion object {
        const val BATCH_ID = "10000000-0000-0000-0000-000000000001"
        const val OPERATION_ID = "20000000-0000-0000-0000-000000000001"
        const val ENTITY_ID = "30000000-0000-0000-0000-000000000001"
    }
}
