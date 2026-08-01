package com.fittrack.core.database.sync

import com.fittrack.core.database.FitTrackDao
import com.fittrack.core.database.entity.SyncOutboxEntity
import java.util.UUID

/** Called inside the repository's existing Room transaction. */
internal class SyncOutboxWriter(private val dao: FitTrackDao) {

    suspend fun upsert(
        entityType: String,
        entityId: String,
        serverVersion: Int?,
        dataJson: String,
        now: Long,
    ) {
        require(entityType in SyncEntityType.writable)
        val pending = dao.getMutablePendingSync(entityType, entityId)
        val knownOnServer = serverVersion != null || dao.hasSyncedSync(entityType, entityId)
        val operation = if (pending?.operation == "CREATE" || !knownOnServer) "CREATE" else "UPDATE"
        val baseVersion = if (operation == "CREATE") null else pending?.baseVersion ?: serverVersion
        if (pending == null) {
            dao.enqueueSync(
                SyncOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    entityType = entityType,
                    entityId = entityId,
                    operation = operation,
                    payloadJson = dataJson,
                    baseVersion = baseVersion,
                    version = 1,
                    createdAt = now,
                ),
            )
        } else {
            dao.updateSync(
                pending.copy(
                    operation = operation,
                    payloadJson = dataJson,
                    baseVersion = baseVersion,
                ),
            )
        }
    }

    suspend fun delete(
        entityType: String,
        entityId: String,
        serverVersion: Int?,
        now: Long,
    ) {
        require(entityType in SyncEntityType.writable)
        val pending = dao.getMutablePendingSync(entityType, entityId)
        if (pending?.operation == "CREATE") {
            // A second CREATE can be queued while the first CREATE is already
            // in-flight. In that case deleting the queued row would lose the
            // user's deletion after the server acknowledges the active CREATE.
            if (dao.hasActiveSync(entityType, entityId) || dao.hasSyncedSync(entityType, entityId)) {
                dao.updateSync(
                    pending.copy(
                        operation = "DELETE",
                        payloadJson = "{}",
                        baseVersion = serverVersion,
                    ),
                )
            } else {
                dao.deleteSync(pending.id)
            }
            return
        }
        if (pending != null) {
            dao.updateSync(
                pending.copy(
                    operation = "DELETE",
                    payloadJson = "{}",
                    baseVersion = pending.baseVersion ?: serverVersion,
                ),
            )
            return
        }
        if (serverVersion != null || dao.hasActiveSync(entityType, entityId) || dao.hasSyncedSync(entityType, entityId)) {
            dao.enqueueSync(
                SyncOutboxEntity(
                    id = UUID.randomUUID().toString(),
                    entityType = entityType,
                    entityId = entityId,
                    operation = "DELETE",
                    payloadJson = "{}",
                    baseVersion = serverVersion,
                    version = 1,
                    createdAt = now,
                ),
            )
        }
    }
}
