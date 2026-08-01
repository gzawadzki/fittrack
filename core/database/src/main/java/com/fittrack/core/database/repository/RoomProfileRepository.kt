package com.fittrack.core.database.repository

import com.fittrack.core.database.FitTrackDao
import com.fittrack.core.database.mapper.toDomain
import com.fittrack.core.database.mapper.toEntity
import com.fittrack.core.model.UserProfile
import com.fittrack.core.model.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class RoomProfileRepository(private val dao: FitTrackDao) : ProfileRepository {
    override fun observeProfile(): Flow<UserProfile?> = dao.observeProfile().map { it?.toDomain() }

    override suspend fun getProfile(): UserProfile? = dao.getProfile()?.toDomain()

    override suspend fun saveProfile(profile: UserProfile) {
        dao.upsertProfile(profile.toEntity(updatedAt = Instant.now().toEpochMilli()))
    }
}
