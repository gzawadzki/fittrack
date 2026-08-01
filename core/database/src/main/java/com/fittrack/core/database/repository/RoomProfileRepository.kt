package com.fittrack.core.database.repository

import androidx.room.withTransaction
import com.fittrack.core.database.FitTrackDatabase
import com.fittrack.core.database.FitTrackDao
import com.fittrack.core.database.mapper.toDomain
import com.fittrack.core.database.mapper.toEntity
import com.fittrack.core.model.UserProfile
import com.fittrack.core.model.repository.ProfileRepository
import com.fittrack.core.database.sync.SyncEntityType
import com.fittrack.core.database.sync.SyncOutboxWriter
import com.fittrack.core.database.sync.syncData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class RoomProfileRepository(private val database: FitTrackDatabase) : ProfileRepository {
    private val dao: FitTrackDao = database.dao()
    private val outbox = SyncOutboxWriter(dao)
    override fun observeProfile(): Flow<UserProfile?> = dao.observeProfile().map { it?.toDomain() }

    override suspend fun getProfile(): UserProfile? = dao.getProfile()?.toDomain()

    override suspend fun saveProfile(profile: UserProfile) {
        val now = Instant.now().toEpochMilli()
        database.withTransaction {
            val existing = dao.getProfileById(profile.id)
            val entity = profile.toEntity(updatedAt = now).copy(serverVersion = existing?.serverVersion)
            dao.upsertProfile(entity)
            outbox.upsert(SyncEntityType.USER_PROFILES, entity.id, entity.serverVersion, entity.syncData(), now)
        }
    }
}
