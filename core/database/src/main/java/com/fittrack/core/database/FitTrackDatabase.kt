package com.fittrack.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    ],
    version = 1,
    exportSchema = true,
)
abstract class FitTrackDatabase : RoomDatabase() {
    abstract fun dao(): FitTrackDao

    companion object {
        private const val DB_NAME = "fittrack.db"

        @Volatile private var instance: FitTrackDatabase? = null

        fun create(context: Context): FitTrackDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FitTrackDatabase::class.java,
                DB_NAME,
            ).build().also { instance = it }
        }

        /** For tests: build an isolated in-memory instance instead of the singleton. */
        fun createInMemory(context: Context): FitTrackDatabase =
            Room.inMemoryDatabaseBuilder(context, FitTrackDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
