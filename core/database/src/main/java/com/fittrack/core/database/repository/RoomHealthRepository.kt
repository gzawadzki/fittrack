package com.fittrack.core.database.repository

import com.fittrack.core.database.FitTrackDao
import com.fittrack.core.database.mapper.toDomain
import com.fittrack.core.database.mapper.toEntity
import com.fittrack.core.model.HealthDay
import com.fittrack.core.model.repository.HealthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class RoomHealthRepository(private val dao: FitTrackDao) : HealthRepository {
    override fun observeDailyHealth(limit: Int): Flow<List<HealthDay>> =
        dao.observeHealth(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun getRecentHealth(days: Int): List<HealthDay> =
        dao.getRecentHealth(days).map { it.toDomain() }

    override suspend fun upsertDailyHealth(days: List<HealthDay>) {
        val now = Instant.now().toEpochMilli()
        dao.upsertHealth(days.map { it.toEntity(syncedAt = now) })
    }
}
