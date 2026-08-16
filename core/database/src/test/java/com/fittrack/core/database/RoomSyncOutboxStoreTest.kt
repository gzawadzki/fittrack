package com.fittrack.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.fittrack.core.database.repository.RoomPlanRepository
import com.fittrack.core.database.repository.RoomWorkoutRepository
import com.fittrack.core.database.sync.RoomSyncOutboxStore
import com.fittrack.core.model.Exercise
import com.fittrack.core.model.ExerciseSessionStatus
import com.fittrack.core.model.PlanExercise
import com.fittrack.core.model.WorkoutPlan
import com.fittrack.core.model.WorkoutStatus
import com.fittrack.core.network.OperationType
import com.fittrack.core.network.RemoteChange
import com.fittrack.core.network.SyncBatchResponse
import com.fittrack.core.network.SyncOperation
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomSyncOutboxStoreTest {

    @Test
    fun `migration 1 to 2 preserves legacy rows and rebuilds their payload`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sync-migration-${UUID.randomUUID()}.db"
        val profileId = UUID.randomUUID().toString()
        val operationId = UUID.randomUUID().toString()
        try {
            context.getDatabasePath(name).parentFile?.mkdirs()
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { raw ->
                createVersionOneSchema(raw)
                raw.execSQL("PRAGMA user_version = 1")
                raw.execSQL(
                    "INSERT INTO user_profile(id,goal,experience,equipmentCsv,schedule,constraints,preferences,approved,version,updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(profileId, "Siła", "średnie", "BARBELL", "wtorek", "", "", 1, 3, 1000L),
                )
                raw.execSQL(
                    "INSERT INTO sync_outbox(id,entityType,entityId,operation,payloadJson,version,status,attemptCount,createdAt,lastAttemptAt) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(operationId, "user_profile", profileId, "UPSERT", "{\"id\":\"$profileId\"}", 1, "PENDING", 0, 1000L, null),
                )
            }

            val migrated = Room.databaseBuilder(context, FitTrackDatabase::class.java, name)
                .allowMainThreadQueries()
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(FitTrackDatabase.MIGRATION_1_2, FitTrackDatabase.MIGRATION_2_3)
                .build()
            try {
                assertEquals("Siła", migrated.dao().getProfile()?.goal)
                val batch = RoomSyncOutboxStore(migrated).prepareBatch(10)
                val operation = batch.operations.single()
                assertEquals("user_profiles", operation.entityType)
                assertEquals(OperationType.CREATE, operation.op)
                assertEquals("Siła", JSONObject(operation.dataJson).getString("goal"))
                assertFalse(JSONObject(operation.dataJson).has("id"))
            } finally { migrated.close() }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `unresolvable version conflict becomes a persisted blocked state`() = runTest {
        val db = database()
        try {
            val plan = seedPlan(db)
            val store = RoomSyncOutboxStore(db)
            val create = store.prepareBatch(100)
            store.completeBatch(
                create.batchId,
                SyncBatchResponse(
                    create.operations.map(SyncOperation::operationId),
                    create.operations.mapIndexed { index, operation -> operation.toRemote(1, index + 1) },
                    create.operations.size.toLong(),
                ),
            )

            RoomPlanRepository(db).updatePlan(plan.copy(name = "Plan B"))
            val update = store.prepareBatch(100)
            store.deferBatchForConflict(update.batchId, 1234L)

            val pullOnly = store.prepareBatch(100)
            assertTrue(pullOnly.operations.isEmpty())
            store.completeBatch(
                pullOnly.batchId,
                SyncBatchResponse(emptyList(), emptyList(), pullOnly.cursor),
            )

            val blocked = runCatching { store.prepareBatch(100) }.exceptionOrNull()
            assertTrue(blocked is com.fittrack.core.database.sync.SyncBlockedException)
            assertEquals("VERSION_CONFLICT_NO_REMOTE_CHANGE", blocked?.message)
        } finally { db.close() }
    }

    @Test
    fun `prepared batch is stable and contains plural types with complete payloads`() = runTest {
        val db = database()
        try {
            val plan = seedPlan(db)
            RoomWorkoutRepository(db).startWorkout(plan.id)
            val store = RoomSyncOutboxStore(db)

            val first = store.prepareBatch(100)
            val retry = store.prepareBatch(100)

            assertEquals(first, retry)
            assertTrue(first.operations.isNotEmpty())
            assertTrue(first.operations.all { it.entityType.endsWith("s") })
            assertTrue(first.operations.all { it.op == OperationType.CREATE })
            assertTrue(first.operations.all { !JSONObject(it.dataJson).has("id") })
            assertTrue(first.operations.any { operation ->
                operation.entityType == "workout_sessions" &&
                    JSONObject(operation.dataJson).getJSONObject("plan_snapshot_json")
                        .getJSONArray("exercises").length() == 1
            })
        } finally { db.close() }
    }

    @Test
    fun `create batch uses deterministic foreign key order`() = runTest {
        val db = database()
        try {
            val plan = seedPlan(db)
            RoomWorkoutRepository(db).startWorkout(plan.id)

            val types = RoomSyncOutboxStore(db).prepareBatch(100).operations.map(SyncOperation::entityType)
            val priorities = mapOf(
                "user_profiles" to 0,
                "exercises" to 10,
                "workout_plans" to 20,
                "plan_exercises" to 30,
                "workout_sessions" to 40,
                "exercise_sessions" to 50,
                "set_logs" to 60,
            )
            assertEquals(types.map { priorities.getValue(it) }.sorted(), types.map { priorities.getValue(it) })
        } finally { db.close() }
    }

    @Test
    fun `remote active workout cancels offline active workout and requeues complete payloads`() = runTest {
        val db = database()
        try {
            val plan = seedPlan(db)
            val repository = RoomWorkoutRepository(db)
            val store = RoomSyncOutboxStore(db)

            // Put the shared plan on the server first. The conflict below is
            // then only between two workout sessions for that same plan.
            val seedBatch = store.prepareBatch(100)
            val seedChanges = seedBatch.operations.mapIndexed { index, operation ->
                operation.toRemote(version = 1, sequence = index + 1)
            }
            store.completeBatch(
                seedBatch.batchId,
                SyncBatchResponse(
                    seedBatch.operations.map(SyncOperation::operationId),
                    seedChanges,
                    seedChanges.size.toLong(),
                ),
            )

            val local = repository.startWorkout(plan.id)
            val conflicted = store.prepareBatch(100)
            val localWorkoutCreate = conflicted.operations.single {
                it.entityType == "workout_sessions" && it.entityId == local.id
            }
            store.deferBatchForConflict(conflicted.batchId, 2_000L)

            val pull = store.prepareBatch(100)
            assertTrue(pull.operations.isEmpty())
            val remoteId = UUID.randomUUID().toString()
            val remoteActive = localWorkoutCreate.copy(
                operationId = UUID.randomUUID().toString(),
                entityId = remoteId,
            ).toRemote(version = 1, sequence = pull.cursor.toInt() + 1)
            store.completeBatch(
                pull.batchId,
                SyncBatchResponse(
                    ackedOperationIds = emptyList(),
                    changes = listOf(remoteActive),
                    nextCursor = pull.cursor + 1,
                ),
            )

            assertEquals(remoteId, repository.getActiveWorkout()?.id)
            assertEquals(WorkoutStatus.CANCELLED, repository.getWorkout(local.id)?.status)
            assertTrue(repository.getExerciseSessions(local.id).all {
                it.status == ExerciseSessionStatus.SKIPPED && it.skippedAt != null
            })

            val repaired = store.prepareBatch(100).operations
            val repairedWorkout = repaired.single {
                it.entityType == "workout_sessions" && it.entityId == local.id
            }
            assertEquals(OperationType.CREATE, repairedWorkout.op)
            assertEquals("CANCELLED", JSONObject(repairedWorkout.dataJson).getString("status"))
            assertTrue(repaired.filter { it.entityType == "exercise_sessions" }.all {
                JSONObject(it.dataJson).getString("status") == "SKIPPED"
            })
        } finally { db.close() }
    }

    @Test
    fun `fresh device round trip restores immutable workout snapshot and is idempotent`() = runTest {
        val source = database()
        try {
            val plan = seedPlan(source)
            val workout = RoomWorkoutRepository(source).startWorkout(plan.id)
            val sourceStore = RoomSyncOutboxStore(source)
            val upload = sourceStore.prepareBatch(100)
            val uploadedChanges = upload.operations.mapIndexed { index, operation ->
                operation.toRemote(version = 1, sequence = index + 1)
            }
            sourceStore.completeBatch(
                upload.batchId,
                SyncBatchResponse(upload.operations.map(SyncOperation::operationId), uploadedChanges, uploadedChanges.size.toLong()),
            )

            val target = database()
            try {
                val targetStore = RoomSyncOutboxStore(target)
                val pull = targetStore.prepareBatch(100)
                targetStore.completeBatch(
                    pull.batchId,
                    SyncBatchResponse(emptyList(), uploadedChanges, uploadedChanges.size.toLong()),
                )

                val restoredPlan = RoomPlanRepository(target).getPlan(plan.id)
                val restoredWorkout = RoomWorkoutRepository(target).getWorkout(workout.id)
                assertNotNull(restoredPlan)
                assertNotNull(restoredWorkout)
                assertEquals(plan.version, restoredPlan!!.version)
                assertEquals(workout.planSnapshot, restoredWorkout!!.planSnapshot)

                val duplicatePull = targetStore.prepareBatch(100)
                targetStore.completeBatch(
                    duplicatePull.batchId,
                    SyncBatchResponse(emptyList(), uploadedChanges, uploadedChanges.size.toLong()),
                )
                assertEquals(1, RoomWorkoutRepository(target).getExerciseSessions(workout.id).size)
                assertEquals(3, RoomWorkoutRepository(target).getSetLogs(
                    RoomWorkoutRepository(target).getExerciseSessions(workout.id).single().id,
                ).size)
            } finally { target.close() }
        } finally { source.close() }
    }

    @Test
    fun `delete created while CREATE is active waits for server version then rebases`() = runTest {
        val db = database()
        try {
            val plan = seedPlan(db)
            val repository = RoomPlanRepository(db)
            val store = RoomSyncOutboxStore(db)
            val createBatch = store.prepareBatch(100)

            repository.deletePlan(plan.id)
            val planCreate = createBatch.operations.single {
                it.entityType == "workout_plans" && it.entityId == plan.id
            }
            store.completeBatch(
                createBatch.batchId,
                SyncBatchResponse(
                    ackedOperationIds = createBatch.operations.map(SyncOperation::operationId),
                    changes = createBatch.operations.mapIndexed { index, operation -> operation.toRemote(1, index + 1) },
                    nextCursor = createBatch.operations.size.toLong(),
                ),
            )

            val deleteBatch = store.prepareBatch(100)
            val delete = deleteBatch.operations.single {
                it.entityType == "workout_plans" && it.entityId == plan.id
            }
            assertEquals(OperationType.DELETE, delete.op)
            assertEquals(1, delete.baseVersion)
            assertEquals("{}", delete.dataJson)
            assertFalse(planCreate.operationId == delete.operationId)
        } finally { db.close() }
    }

    @Test
    fun `delete after update queued behind active CREATE is not lost`() = runTest {
        val db = database()
        try {
            val initial = seedPlan(db)
            val repository = RoomPlanRepository(db)
            val store = RoomSyncOutboxStore(db)
            val createBatch = store.prepareBatch(100)

            repository.updatePlan(initial.copy(name = "Edited while uploading"))
            repository.deletePlan(initial.id)
            store.completeBatch(
                createBatch.batchId,
                SyncBatchResponse(
                    ackedOperationIds = createBatch.operations.map(SyncOperation::operationId),
                    changes = createBatch.operations.mapIndexed { index, operation -> operation.toRemote(1, index + 1) },
                    nextCursor = createBatch.operations.size.toLong(),
                ),
            )

            val delete = store.prepareBatch(100).operations.single {
                it.entityType == "workout_plans" && it.entityId == initial.id
            }
            assertEquals(OperationType.DELETE, delete.op)
            assertEquals(1, delete.baseVersion)
        } finally { db.close() }
    }

    private suspend fun seedPlan(database: FitTrackDatabase): WorkoutPlan {
        val plans = RoomPlanRepository(database)
        val exercise = Exercise(
            id = UUID.randomUUID().toString(),
            name = "Squat",
            movementPattern = "SQUAT",
            muscleRole = "QUADS",
            equipment = "BARBELL",
        )
        plans.upsertExercise(exercise)
        val plan = WorkoutPlan(
            id = UUID.randomUUID().toString(),
            name = "Plan A",
            dayLabel = "Dzień A",
            scheduledDayOfWeek = 2,
            scheduledMinuteOfDay = 18 * 60,
            version = 1,
            active = true,
            exercises = listOf(
                PlanExercise(
                    id = UUID.randomUUID().toString(),
                    exerciseId = exercise.id,
                    exerciseName = exercise.name,
                    position = 0,
                    warmupSets = 1,
                    workingSets = 2,
                    repMin = 6,
                    repMax = 8,
                    targetRir = 2,
                    startingWeightKg = 40.0,
                    restSeconds = 120,
                    weightIncrementKg = 2.5,
                ),
            ),
        )
        plans.createPlan(plan)
        return plan
    }

    private fun SyncOperation.toRemote(version: Int, sequence: Int) = RemoteChange(
        entityType = entityType,
        id = entityId,
        version = version,
        updatedAtUtc = "2026-08-01T12:00:${sequence.toString().padStart(2, '0')}Z",
        deletedAtUtc = null,
        dataJson = dataJson,
    )

    private fun database(): FitTrackDatabase = FitTrackDatabase.createInMemory(
        ApplicationProvider.getApplicationContext<Context>(),
    )

    private fun createVersionOneSchema(db: SQLiteDatabase) {
        val tables = listOf(
            "CREATE TABLE IF NOT EXISTS user_profile (id TEXT NOT NULL, goal TEXT NOT NULL, experience TEXT NOT NULL, equipmentCsv TEXT NOT NULL, schedule TEXT NOT NULL, constraints TEXT NOT NULL, preferences TEXT NOT NULL, approved INTEGER NOT NULL, version INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE IF NOT EXISTS exercises (id TEXT NOT NULL, name TEXT NOT NULL, movementPattern TEXT NOT NULL, muscleRole TEXT NOT NULL, equipment TEXT NOT NULL, active INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, PRIMARY KEY(id))",
            "CREATE TABLE IF NOT EXISTS workout_plans (id TEXT NOT NULL, name TEXT NOT NULL, dayLabel TEXT NOT NULL, scheduledDayOfWeek INTEGER, scheduledMinuteOfDay INTEGER, version INTEGER NOT NULL, active INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, PRIMARY KEY(id))",
            "CREATE TABLE IF NOT EXISTS plan_exercises (id TEXT NOT NULL, planId TEXT NOT NULL, exerciseId TEXT NOT NULL, position INTEGER NOT NULL, warmupSets INTEGER NOT NULL, workingSets INTEGER NOT NULL, repMin INTEGER NOT NULL, repMax INTEGER NOT NULL, targetRir INTEGER, startingWeightKg REAL NOT NULL, restSeconds INTEGER NOT NULL, weightIncrementKg REAL NOT NULL, notes TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(planId) REFERENCES workout_plans(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON UPDATE NO ACTION ON DELETE NO ACTION)",
            "CREATE TABLE IF NOT EXISTS workout_sessions (id TEXT NOT NULL, planId TEXT NOT NULL, planNameSnapshot TEXT NOT NULL, planVersionSnapshot INTEGER NOT NULL, dayLabelSnapshot TEXT NOT NULL, planSnapshotJson TEXT NOT NULL, status TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, note TEXT NOT NULL, fitbitSyncStatus TEXT NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, PRIMARY KEY(id))",
            "CREATE TABLE IF NOT EXISTS exercise_sessions (id TEXT NOT NULL, workoutSessionId TEXT NOT NULL, planExerciseId TEXT NOT NULL, exerciseId TEXT NOT NULL, originalExerciseId TEXT NOT NULL, exerciseNameSnapshot TEXT NOT NULL, position INTEGER NOT NULL, restSecondsSnapshot INTEGER NOT NULL, status TEXT NOT NULL, startedAt INTEGER, endedAt INTEGER, skippedAt INTEGER, skipReason TEXT NOT NULL, note TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(workoutSessionId) REFERENCES workout_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS set_logs (id TEXT NOT NULL, exerciseSessionId TEXT NOT NULL, position INTEGER NOT NULL, setType TEXT NOT NULL, targetRepMin INTEGER NOT NULL, targetRepMax INTEGER NOT NULL, targetRir INTEGER, targetWeightKg REAL NOT NULL, actualReps INTEGER, actualWeightKg REAL, actualRir INTEGER, pain INTEGER NOT NULL, techniqueIssue INTEGER NOT NULL, concerningSymptom INTEGER NOT NULL, completedAt INTEGER, updatedAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(exerciseSessionId) REFERENCES exercise_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE TABLE IF NOT EXISTS health_daily (date TEXT NOT NULL, sleepMinutes INTEGER, restingHeartRate INTEGER, steps INTEGER, source TEXT NOT NULL, syncedAt INTEGER NOT NULL, PRIMARY KEY(date))",
            "CREATE TABLE IF NOT EXISTS cardio_activities (id TEXT NOT NULL, sourceId TEXT NOT NULL, kind TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, durationSeconds INTEGER NOT NULL, distanceMeters REAL, steps INTEGER, averageHeartRate INTEGER, syncedAt INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE IF NOT EXISTS heart_rate_samples (workoutSessionId TEXT NOT NULL, sampledAt INTEGER NOT NULL, bpm INTEGER NOT NULL, PRIMARY KEY(workoutSessionId, sampledAt))",
            "CREATE TABLE IF NOT EXISTS exercise_hr_summary (exerciseSessionId TEXT NOT NULL, averageBpm INTEGER NOT NULL, maximumBpm INTEGER NOT NULL, recoveryDeltaBpm INTEGER, sampleCount INTEGER NOT NULL, computedAt INTEGER NOT NULL, PRIMARY KEY(exerciseSessionId))",
            "CREATE TABLE IF NOT EXISTS coach_suggestions (id TEXT NOT NULL, type TEXT NOT NULL, targetId TEXT NOT NULL, beforeValue TEXT NOT NULL, afterValue TEXT NOT NULL, reasonCode TEXT NOT NULL, explanation TEXT NOT NULL, signalsJson TEXT NOT NULL, citationsJson TEXT NOT NULL, confidence TEXT NOT NULL, status TEXT NOT NULL, decisionReason TEXT NOT NULL, createdAt INTEGER NOT NULL, decidedAt INTEGER, PRIMARY KEY(id))",
            "CREATE TABLE IF NOT EXISTS sync_outbox (id TEXT NOT NULL, entityType TEXT NOT NULL, entityId TEXT NOT NULL, operation TEXT NOT NULL, payloadJson TEXT NOT NULL, version INTEGER NOT NULL, status TEXT NOT NULL, attemptCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, lastAttemptAt INTEGER, PRIMARY KEY(id))",
        )
        tables.forEach(db::execSQL)
        listOf(
            "CREATE INDEX IF NOT EXISTS index_exercises_movementPattern ON exercises(movementPattern)",
            "CREATE INDEX IF NOT EXISTS index_exercises_equipment ON exercises(equipment)",
            "CREATE INDEX IF NOT EXISTS index_workout_plans_active ON workout_plans(active)",
            "CREATE INDEX IF NOT EXISTS index_plan_exercises_planId ON plan_exercises(planId)",
            "CREATE INDEX IF NOT EXISTS index_plan_exercises_exerciseId ON plan_exercises(exerciseId)",
            "CREATE INDEX IF NOT EXISTS index_workout_sessions_planId ON workout_sessions(planId)",
            "CREATE INDEX IF NOT EXISTS index_workout_sessions_status ON workout_sessions(status)",
            "CREATE INDEX IF NOT EXISTS index_exercise_sessions_workoutSessionId ON exercise_sessions(workoutSessionId)",
            "CREATE INDEX IF NOT EXISTS index_exercise_sessions_exerciseId ON exercise_sessions(exerciseId)",
            "CREATE INDEX IF NOT EXISTS index_exercise_sessions_planExerciseId ON exercise_sessions(planExerciseId)",
            "CREATE INDEX IF NOT EXISTS index_set_logs_exerciseSessionId ON set_logs(exerciseSessionId)",
            "CREATE INDEX IF NOT EXISTS index_cardio_activities_startedAt ON cardio_activities(startedAt)",
            "CREATE INDEX IF NOT EXISTS index_heart_rate_samples_sampledAt ON heart_rate_samples(sampledAt)",
            "CREATE INDEX IF NOT EXISTS index_coach_suggestions_status ON coach_suggestions(status)",
            "CREATE INDEX IF NOT EXISTS index_coach_suggestions_targetId ON coach_suggestions(targetId)",
            "CREATE INDEX IF NOT EXISTS index_sync_outbox_status ON sync_outbox(status)",
            "CREATE INDEX IF NOT EXISTS index_sync_outbox_entityType_entityId ON sync_outbox(entityType, entityId)",
        ).forEach(db::execSQL)
    }
}
