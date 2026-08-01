package com.fittrack.core.database.repository

import androidx.room.withTransaction
import com.fittrack.core.database.FitTrackDatabase
import com.fittrack.core.database.PlanJsonImporter
import com.fittrack.core.database.entity.ExerciseEntity
import com.fittrack.core.database.entity.SyncOutboxEntity
import com.fittrack.core.database.entity.WorkoutPlanEntity
import com.fittrack.core.database.mapper.toDomain
import com.fittrack.core.database.mapper.toEntity
import com.fittrack.core.model.CoachSuggestion
import com.fittrack.core.model.Exercise
import com.fittrack.core.model.PlanExercise
import com.fittrack.core.model.SuggestionType
import com.fittrack.core.model.WorkoutPlan
import com.fittrack.core.model.repository.PlanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

class RoomPlanRepository(private val database: FitTrackDatabase) : PlanRepository {
    private val dao = database.dao()

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
            dao.upsertPlans(listOf(plan.toEntity(updatedAt = now)))
            dao.upsertPlanExercises(plan.exercises.map { it.toEntity(planId = plan.id, updatedAt = now) })
            enqueue("workout_plan", plan.id, "UPSERT", now)
        }
    }

    override suspend fun updatePlan(plan: WorkoutPlan) {
        val now = now()
        database.withTransaction {
            val existing = dao.getPlan(plan.id)
            val nextVersion = ((existing?.version ?: 0) + 1).coerceAtLeast(plan.version)
            dao.upsertPlans(listOf(plan.toEntity(updatedAt = now).copy(version = nextVersion)))
            dao.deletePlanExercisesForPlan(plan.id)
            dao.upsertPlanExercises(plan.exercises.map { it.toEntity(planId = plan.id, updatedAt = now) })
            enqueue("workout_plan", plan.id, "UPSERT", now)
        }
    }

    override suspend fun deletePlan(planId: String) {
        val now = now()
        database.withTransaction {
            dao.tombstonePlan(planId, now)
            enqueue("workout_plan", planId, "DELETE", now)
        }
    }

    override suspend fun applySuggestion(suggestion: CoachSuggestion) {
        database.withTransaction {
            val planExercise = dao.getPlanExercise(suggestion.targetId) ?: return@withTransaction
            val now = now()
            when (suggestion.type) {
                SuggestionType.LOAD -> dao.updatePlanExercise(
                    planExercise.copy(startingWeightKg = suggestion.after.toDouble(), updatedAt = now),
                )
                SuggestionType.VOLUME -> dao.updatePlanExercise(
                    planExercise.copy(workingSets = suggestion.after.toInt().coerceAtLeast(1), updatedAt = now),
                )
                else -> Unit // SWAP/RESCHEDULE/HOLD are not plan-exercise field mutations local to this repository.
            }
            enqueue("plan_exercise", planExercise.id, "UPSERT", now)
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
            dao.upsertPlans(listOf(plan.toEntity(updatedAt = now)))
            dao.upsertPlanExercises(planExercises.map { it.toEntity(planId = planId, updatedAt = now) })
            enqueue("workout_plan", planId, "UPSERT", now)
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
        dao.upsertExercises(entities)
    }

    override suspend fun findSwapCandidates(exerciseId: String, availableEquipment: Set<String>, limit: Int): List<Exercise> {
        val exercise = dao.getExercise(exerciseId) ?: return emptyList()
        return dao.findSwapCandidates(exercise.movementPattern, availableEquipment.toList(), exercise.id, limit).map { it.toDomain() }
    }

    override fun observeExerciseLibrary(): Flow<List<Exercise>> = dao.observeExerciseLibrary().map { list -> list.map { it.toDomain() } }

    override suspend fun getExercise(exerciseId: String): Exercise? = dao.getExercise(exerciseId)?.toDomain()

    override suspend fun upsertExercise(exercise: Exercise) {
        dao.upsertExercises(listOf(exercise.toEntity(updatedAt = now())))
    }

    private suspend fun resolveOrCreateExercise(name: String, movementPattern: String, muscleRole: String, equipment: String, now: Long): String {
        dao.findExerciseByName(name)?.let { return it.id }
        val id = UUID.randomUUID().toString()
        dao.upsertExercises(
            listOf(
                ExerciseEntity(
                    id = id,
                    name = name,
                    movementPattern = movementPattern,
                    muscleRole = muscleRole,
                    equipment = equipment,
                    updatedAt = now,
                ),
            ),
        )
        return id
    }

    private suspend fun buildPlan(entity: WorkoutPlanEntity): WorkoutPlan {
        val plannedExercises = dao.getPlanExercises(entity.id)
        val exerciseNames = dao.getExercises(plannedExercises.map { it.exerciseId }).associateBy { it.id }
        val exercises = plannedExercises.map { pe -> pe.toDomain(exerciseNames[pe.exerciseId]?.name ?: "") }
        return entity.toDomain(exercises)
    }

    private suspend fun enqueue(entityType: String, entityId: String, operation: String, timestamp: Long) {
        dao.enqueueSync(
            SyncOutboxEntity(
                id = UUID.randomUUID().toString(),
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payloadJson = "{\"id\":\"$entityId\"}",
                version = 1,
                createdAt = timestamp,
            ),
        )
    }

    private fun now(): Long = Instant.now().toEpochMilli()
}
