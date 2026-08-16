package com.fittrack.core.network

import android.util.Log
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SyncOrchestrator(
    private val outbox: SyncOutboxStore,
    private val client: SyncClient,
    private val nowMillis: () -> Long = { Instant.now().toEpochMilli() },
) {
    private val syncMutex = Mutex()

    suspend fun syncOnce(batchSize: Int = DEFAULT_BATCH_SIZE): SyncResult = syncMutex.withLock {
        val request = try {
            outbox.prepareBatch(batchSize)
        } catch (e: Throwable) {
            Log.e("SyncOrchestrator", "prepareBatch failed", e)
            return@withLock SyncResult.Failure(NetworkError("PREPARE_BATCH_FAILED", "Nie udało się przygotować danych do synchronizacji.", retryable = true))
        }
        val result = try {
            client.sync(request)
        } catch (e: Throwable) {
            Log.e("SyncOrchestrator", "client.sync failed", e)
            return@withLock SyncResult.Failure(NetworkError("CLIENT_SYNC_FAILED", "Nie udało się połączyć z serwerem.", retryable = true))
        }
        when (result) {
            is SyncResult.Success -> outbox.completeBatch(request.batchId, result.response)
            is SyncResult.Failure -> {
                if (result.error.code == VERSION_CONFLICT) {
                    outbox.deferBatchForConflict(request.batchId, nowMillis())
                } else {
                    outbox.recordAttempt(request.batchId, nowMillis())
                }
            }
        }
        return result
    }

    suspend fun cleanupExpiredHeartRate(): Int = outbox.cleanupRawHeartRate(nowMillis() - RAW_HEART_RATE_RETENTION_MS)

    private companion object {
        const val DEFAULT_BATCH_SIZE = 100
        const val RAW_HEART_RATE_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
        const val VERSION_CONFLICT = "VERSION_CONFLICT"
    }
}
