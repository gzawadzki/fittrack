package com.fittrack.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fittrack.core.database.entity.CardioActivityEntity
import com.fittrack.core.database.entity.CoachSuggestionEntity
import com.fittrack.core.database.entity.ExerciseEntity
import com.fittrack.core.database.entity.ExerciseHrSummaryEntity
import com.fittrack.core.database.entity.ExerciseSessionEntity
import com.fittrack.core.database.entity.HealthDailyEntity
import com.fittrack.core.database.entity.HeartRateSampleEntity
import com.fittrack.core.database.entity.PlanExerciseEntity
import com.fittrack.core.database.entity.SetLogEntity
import com.fittrack.core.database.entity.SyncOutboxEntity
import com.fittrack.core.database.entity.SyncStateEntity
import com.fittrack.core.database.entity.UserProfileEntity
import com.fittrack.core.database.entity.WorkoutPlanEntity
import com.fittrack.core.database.entity.WorkoutSessionEntity

@Database(
    entities = [
        UserProfileEntity::class,
        ExerciseEntity::class,
        WorkoutPlanEntity::class,
        PlanExerciseEntity::class,
        WorkoutSessionEntity::class,
        ExerciseSessionEntity::class,
        SetLogEntity::class,
        HealthDailyEntity::class,
        CardioActivityEntity::class,
        HeartRateSampleEntity::class,
        ExerciseHrSummaryEntity::class,
        CoachSuggestionEntity::class,
        SyncOutboxEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class FitTrackDatabase : RoomDatabase() {
    abstract fun dao(): FitTrackDao

    companion object {
        // The pre-modular prototype used fittrack.db with a different v1 schema.
        // A distinct name avoids a Room identity-hash crash during the private MVP migration.
        private const val DB_NAME = "fittrack_v2.db"

        @Volatile private var instance: FitTrackDatabase? = null

        fun create(context: Context): FitTrackDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FitTrackDatabase::class.java,
                DB_NAME,
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        /** For tests: build an isolated in-memory instance instead of the singleton. */
        fun createInMemory(context: Context): FitTrackDatabase =
            Room.inMemoryDatabaseBuilder(context, FitTrackDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN serverVersion INTEGER")
                db.execSQL("ALTER TABLE user_profile ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE exercises ADD COLUMN serverVersion INTEGER")
                db.execSQL("ALTER TABLE workout_plans ADD COLUMN serverVersion INTEGER")
                db.execSQL("ALTER TABLE plan_exercises ADD COLUMN serverVersion INTEGER")
                db.execSQL("ALTER TABLE plan_exercises ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN serverVersion INTEGER")
                db.execSQL("ALTER TABLE exercise_sessions ADD COLUMN serverVersion INTEGER")
                db.execSQL("ALTER TABLE exercise_sessions ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE set_logs ADD COLUMN serverVersion INTEGER")
                db.execSQL("ALTER TABLE set_logs ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE coach_suggestions ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE coach_suggestions SET updatedAt = createdAt WHERE updatedAt = 0")
                db.execSQL("ALTER TABLE coach_suggestions ADD COLUMN serverVersion INTEGER")
                db.execSQL("ALTER TABLE coach_suggestions ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN baseVersion INTEGER")
                db.execSQL("ALTER TABLE sync_outbox ADD COLUMN activeBatchId TEXT")
                db.execSQL(
                    "UPDATE sync_outbox SET entityType = CASE entityType " +
                        "WHEN 'user_profile' THEN 'user_profiles' WHEN 'workout_plan' THEN 'workout_plans' " +
                        "WHEN 'plan_exercise' THEN 'plan_exercises' WHEN 'workout_session' THEN 'workout_sessions' " +
                        "WHEN 'exercise_session' THEN 'exercise_sessions' WHEN 'set_log' THEN 'set_logs' " +
                        "WHEN 'exercise' THEN 'exercises' ELSE entityType END",
                )
                // No legacy request could pass the old server validation, so every
                // surviving legacy mutation is safely rebuilt as a CREATE from the
                // current full local row by RoomSyncOutboxStore.
                db.execSQL("UPDATE sync_outbox SET operation = 'CREATE', baseVersion = NULL, status = 'PENDING'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_state (" +
                        "singletonId INTEGER NOT NULL PRIMARY KEY, cursor INTEGER NOT NULL, " +
                        "activeBatchId TEXT, blockedReason TEXT)",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO sync_state(singletonId, cursor, activeBatchId, blockedReason) " +
                        "VALUES (1, 0, NULL, NULL)",
                )
            }
        }
    }
}
