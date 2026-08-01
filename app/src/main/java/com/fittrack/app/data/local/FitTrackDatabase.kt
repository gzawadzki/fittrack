package com.fittrack.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        @Volatile private var instance: FitTrackDatabase? = null

        fun create(context: Context): FitTrackDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FitTrackDatabase::class.java,
                "fittrack.db",
            ).build().also { instance = it }
        }
    }
}
