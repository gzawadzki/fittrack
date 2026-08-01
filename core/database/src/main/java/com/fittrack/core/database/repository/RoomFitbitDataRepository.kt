package com.fittrack.core.database.repository

import androidx.room.withTransaction
import com.fittrack.core.database.FitTrackDatabase
import com.fittrack.core.database.mapper.toDomain
import com.fittrack.core.database.mapper.toEntity
import com.fittrack.core.model.CardioActivity
import com.fittrack.core.model.ExerciseHeartRateSummary
import com.fittrack.core.model.FitbitDataPayload
import com.fittrack.core.model.FitbitSyncStatus
import com.fittrack.core.model.HeartRateSample
import com.fittrack.core.model.repository.FitbitDataRepository
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomFitbitDataRepository(
    private val database: FitTrackDatabase,
) : FitbitDataRepository {
    private val dao = database.dao()

    override fun observeCardioActivities(): Flow<List<CardioActivity>> =
        dao.observeCardioActivities().map { rows -> rows.map { it.toDomain() } }

    override fun observeHeartRateSamples(workoutSessionId: String?): Flow<List<HeartRateSample>> =
        dao.observeHeartRateSamples(workoutSessionId).map { rows -> rows.map { it.toDomain() } }

    override fun observeExerciseHeartRateSummaries(
        workoutSessionId: String?,
    ): Flow<List<ExerciseHeartRateSummary>> =
        dao.observeExerciseHrSummaries(workoutSessionId).map { rows ->
            rows.map { it.toDomain() }.filter(ExerciseHeartRateSummary::hasData)
        }

    override suspend fun persist(payload: FitbitDataPayload) {
        validate(payload)
        val syncedAt = payload.syncedAt.toEpochMilli()
        database.withTransaction {
            payload.workoutSessionId?.let { workoutId ->
                requireNotNull(dao.getWorkout(workoutId)) { "Workout does not exist: $workoutId" }
            }

            if (payload.dailyHealth.isNotEmpty()) {
                dao.upsertHealth(payload.dailyHealth.map { it.toEntity(syncedAt) })
            }
            if (payload.cardioActivities.isNotEmpty()) {
                dao.upsertCardioActivities(
                    payload.cardioActivities.map { activity ->
                        activity.toEntity(stableCardioId(activity.sourceId), syncedAt)
                    },
                )
            }
            if (payload.heartRateSamples.isNotEmpty()) {
                dao.insertHeartRateSamples(payload.heartRateSamples.map { it.toEntity() })
            }

            val summariesWithData = payload.exerciseHeartRateSummaries.filter { it.hasData }
            if (summariesWithData.isNotEmpty()) {
                val workoutId = requireNotNull(payload.workoutSessionId)
                summariesWithData.forEach { summary ->
                    val exercise = requireNotNull(dao.getExerciseSession(summary.exerciseSessionId)) {
                        "Exercise session does not exist: ${summary.exerciseSessionId}"
                    }
                    require(exercise.workoutSessionId == workoutId) {
                        "Exercise session does not belong to workout $workoutId"
                    }
                }
                dao.upsertExerciseHrSummaries(summariesWithData.map { it.toEntity(syncedAt) })
            }

            payload.workoutSessionId?.let { workoutId ->
                val updated = dao.updateWorkoutFitbitSyncStatus(
                    workoutSessionId = workoutId,
                    status = requireNotNull(payload.workoutSyncStatus).name,
                    updatedAt = syncedAt,
                )
                check(updated == 1) { "Workout disappeared while persisting Fitbit data" }
            }
        }
    }

    private fun validate(payload: FitbitDataPayload) {
        require((payload.workoutSessionId == null) == (payload.workoutSyncStatus == null)) {
            "Workout id and Fitbit sync status must be supplied together"
        }
        require(
            (payload.heartRateSamples.isEmpty() && payload.exerciseHeartRateSummaries.isEmpty()) ||
                payload.workoutSessionId != null,
        ) { "Workout HR data requires a workout id and sync status" }
        require(payload.workoutSyncStatus != FitbitSyncStatus.NOT_REQUESTED) {
            "Imported Fitbit data cannot reset a workout to NOT_REQUESTED"
        }
        require(payload.cardioActivities.all { activity ->
            activity.sourceId.isNotBlank() && activity.durationSeconds >= 0 &&
                !activity.endedAt.isBefore(activity.startedAt)
        }) { "Invalid cardio activity" }
        require(payload.heartRateSamples.all { sample ->
            sample.bpm > 0 && (payload.workoutSessionId == null || sample.workoutSessionId == payload.workoutSessionId)
        }) { "Invalid heart-rate sample" }
    }

    private fun stableCardioId(sourceId: String): String = UUID.nameUUIDFromBytes(
        "fitbit-cardio:$sourceId".toByteArray(StandardCharsets.UTF_8),
    ).toString()
}
