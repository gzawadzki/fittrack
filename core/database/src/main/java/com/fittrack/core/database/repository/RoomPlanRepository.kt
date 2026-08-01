package com.fittrack.core.database.repository

import androidx.room.withTransaction
import com.fittrack.core.database.FitTrackDatabase
import com.fittrack.core.database.PlanJsonImporter
import com.fittrack.core.database.entity.ExerciseEntity
import com.fittrack.core.database.entity.WorkoutPlanEntity
import com.fittrack.core.database.mapper.toDomain
import com.fittrack.core.database.mapper.toEntity
import com.fittrack.core.model.CoachSuggestion
import com.fittrack.core.model.Exercise
import com.fittrack.core.model.PlanExercise
import com.fittrack.core.model.SuggestionType
import com.fittrack.core.model.WorkoutPlan
import com.fittrack.core.model.repository.PlanRepository
import com.fittrack.core.database.sync.SyncEntityType
import com.fittrack.core.database.sync.SyncOutboxWriter
import com.fittrack.core.database.sync.syncData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

class RoomPlanRepository(private val database: FitTrackDatabase) : PlanRepository {
    private val dao = database.dao()
    private val outbox = SyncOutboxWriter(dao)

    override fun observePlans(): Flow<List<WorkoutPlan>> =
        dao.observePlans().map { plans -> plans.map { buildPlan(it) } }

    override suspend fun getPlan(planId: String): WorkoutPlan? = dao.getPlan(planId)?.let { buildPlan(it) }

    override suspend fun getPlanExercise(planExerciseId: String): PlanExercise? {
        val entity = dao.getPlanExercise(planExerciseId) ?: return null
        val exerciseName = dao.getExercise(entity.exerciseId)?.name ?: ""
        return entity.toDomain(exerciseName)
    }

    override suspend fun createPlan(plan: WorkoutPlan) {
        require(plan.name.isNotBlank()) { "Plan name is required" }
        require(plan.exercises.isNotEmpty()) { "Plan needs at least one exercise" }
        val now = now()
        database.withTransaction {
            val planEntity = plan.toEntity(updatedAt = now)
            val planExerciseEntities = plan.exercises.map { it.toEntity(planId = plan.id, updatedAt = now) }
            dao.upsertPlans(listOf(planEntity))
            dao.upsertPlanExercises(planExerciseEntities)
            outbox.upsert(SyncEntityType.WORKOUT_PLANS, planEntity.id, null, planEntity.syncData(), now)
            planExerciseEntities.forEach { entity ->
                outbox.upsert(SyncEntityType.PLAN_EXERCISES, entity.id, null, entity.syncData(), now)
            }
        }
    }

    override suspend fun updatePlan(plan: WorkoutPlan) {
        val now = now()
        database.withTransaction {
            val existing = requireNotNull(dao.getPlan(plan.id)) { "Plan ${plan.id} does not exist" }
            require(existing.version == plan.version) { "Plan ${plan.id} was updated by another writer" }
            val nextVersion = existing.version + 1
            val updatedPlan = plan.toEntity(updatedAt = now).copy(
                version = nextVersion,
                serverVersion = existing.serverVersion,
            )
            val previousExercises = dao.getAllPlanExercises(plan.id).associateBy { it.id }
            val updatedExercises = plan.exercises.map { item ->
                val previous = previousExercises[item.id]
                item.toEntity(planId = plan.id, updatedAt = now).copy(
                    serverVersion = previous?.serverVersion,
                    deletedAt = null,
                )
            }
            dao.upsertPlans(listOf(updatedPlan))
            dao.upsertPlanExercises(updatedExercises)
            previousExercises.values
                .filter { previous -> updatedExercises.none { it.id == previous.id } && previous.deletedAt == null }
                .forEach { removed ->
                    dao.updatePlanExercise(removed.copy(deletedAt = now, updatedAt = now))
                    outbox.delete(SyncEntityType.PLAN_EXERCISES, removed.id, removed.serverVersion, now)
                }
            outbox.upsert(SyncEntityType.WORKOUT_PLANS, updatedPlan.id, updatedPlan.serverVersion, updatedPlan.syncData(), now)
            updatedExercises.forEach { entity ->
                outbox.upsert(SyncEntityType.PLAN_EXERCISES, entity.id, entity.serverVersion, entity.syncData(), now)
            }
        }
    }

    override suspend fun deletePlan(planId: String) {
        val now = now()
        database.withTransaction {
            val existing = requireNotNull(dao.getPlan(planId)) { "Plan $planId does not exist" }
            dao.tombstonePlan(planId, now)
            outbox.delete(SyncEntityType.WORKOUT_PLANS, planId, existing.serverVersion, now)
        }
    }

    override suspend fun applySuggestion(suggestion: CoachSuggestion) {
        database.withTransaction {
            val planExercise = dao.getPlanExercise(suggestion.targetId) ?: return@withTransaction
            val now = now()
            val updated = when (suggestion.type) {
                SuggestionType.LOAD -> planExercise.copy(startingWeightKg = suggestion.after.toDouble(), updatedAt = now)
                SuggestionType.VOLUME -> planExercise.copy(workingSets = suggestion.after.toInt().coerceAtLeast(1), updatedAt = now)
                else -> return@withTransaction
            }
            dao.updatePlanExercise(updated)
            outbox.upsert(SyncEntityType.PLAN_EXERCISES, updated.id, updated.serverVersion, updated.syncData(), now)
        }
    }

    override suspend fun importPlanFromJson(json: String): WorkoutPlan {
        val parsed = PlanJsonImporter.parsePlan(json)
        val now = now()
        val planId = UUID.randomUUID().toString()
        return database.withTransaction {
            val planExercises = parsed.exercises.mapIndexed { index, item ->
                val exerciseId = resolveOrCreateExercise(item.name, item.movementPattern, item.muscleRole, item.equipment, now)
                PlanExercise(
                    id = UUID.randomUUID().toString(),
                    exerciseId = exerciseId,
                    exerciseName = item.name,
                    position = index,
                    warmupSets = item.warmupSets,
                    workingSets = item.workingSets,
                    repMin = item.repMin,
                    repMax = item.repMax,
                    targetRir = item.targetRir,
                    startingWeightKg = item.startingWeightKg,
                    restSeconds = item.restSeconds,
                    weightIncrementKg = item.weightIncrementKg,
                )
            }
            val plan = WorkoutPlan(
                id = planId,
                name = parsed.name,
                dayLabel = parsed.dayLabel,
                scheduledDayOfWeek = parsed.scheduledDayOfWeek,
                scheduledMinuteOfDay = parsed.scheduledMinuteOfDay,
                version = 1,
                active = true,
                exercises = planExercises,
            )
            val planEntity = plan.toEntity(updatedAt = now)
            val planExerciseEntities = planExercises.map { it.toEntity(planId = planId, updatedAt = now) }
            dao.upsertPlans(listOf(planEntity))
            dao.upsertPlanExercises(planExerciseEntities)
            outbox.upsert(SyncEntityType.WORKOUT_PLANS, planId, null, planEntity.syncData(), now)
            planExerciseEntities.forEach { entity ->
                outbox.upsert(SyncEntityType.PLAN_EXERCISES, entity.id, null, entity.syncData(), now)
            }
            plan
        }
    }

    override suspend fun importExerciseLibraryFromJson(json: String) {
        if (dao.exerciseCount() > 0) return
        val parsed = PlanJsonImporter.parseLibrary(json)
        val now = now()
        val entities = parsed.map { item ->
            ExerciseEntity(
                id = UUID.randomUUID().toString(),
                name = item.name,
                movementPattern = item.movementPattern,
                muscleRole = item.muscleRole,
                equipment = item.equipment,
                updatedAt = now,
            )
        }
        database.withTransaction {
            dao.upsertExercises(entities)
            entities.forEach { entity ->
                outbox.upsert(SyncEntityType.EXERCISES, entity.id, null, entity.syncData(), now)
            }
        }
    }

    override suspend fun findSwapCandidates(exerciseId: String, availableEquipment: Set<String>, limit: Int): List<Exercise> {
        val exercise = dao.getExercise(exerciseId) ?: return emptyList()
        return dao.findSwapCandidates(exercise.movementPattern, availableEquipment.toList(), exercise.id, limit).map { it.toDomain() }
    }

    override fun observeExerciseLibrary(): Flow<List<Exercise>> = dao.observeExerciseLibrary().map { list -> list.map { it.toDomain() } }

    override suspend fun getExercise(exerciseId: String): Exercise? = dao.getExercise(exerciseId)?.toDomain()

    override suspend fun upsertExercise(exercise: Exercise) {
        val now = now()
        database.withTransaction {
            val existing = dao.getExercise(exercise.id)
            val entity = exercise.toEntity(updatedAt = now).copy(serverVersion = existing?.serverVersion, deletedAt = null)
            dao.upsertExercises(listOf(entity))
            outbox.upsert(SyncEntityType.EXERCISES, entity.id, entity.serverVersion, entity.syncData(), now)
        }
    }

    private suspend fun resolveOrCreateExercise(name: String, movementPattern: String, muscleRole: String, equipment: String, now: Long): String {
        dao.findExerciseByName(name)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        val entity = ExerciseEntity(
            id = id,
            name = name,
            movementPattern = movementPattern,
            muscleRole = muscleRole,
            equipment = equipment,
            updatedAt = now,
        )
        dao.upsertExercises(listOf(entity))
        outbox.upsert(SyncEntityType.EXERCISES, id, null, entity.syncData(), now)
        return id
    }

    private suspend fun buildPlan(entity: WorkoutPlanEntity): WorkoutPlan {
        val plannedExercises = dao.getPlanExercises(entity.id)
        val exerciseNames = dao.getExercises(plannedExercises.map { it.exerciseId }).associateBy { it.id }
        val exercises = plannedExercises.map { pe -> pe.toDomain(exerciseNames[pe.exerciseId]?.name ?: "") }
        return entity.toDomain(exercises)
    }

    private fun now(): Long = Instant.now().toEpochMilli()
}
