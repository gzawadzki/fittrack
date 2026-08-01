package com.fittrack.feature.history

import com.fittrack.core.model.FitbitSyncStatus
import java.time.Instant

data class StrengthHistoryItem(
    val workoutId: String,
    val planName: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val note: String,
    val exerciseCount: Int,
    val fitbitState: FitbitSyncStatus,
    val exercises: List<ExerciseHistoryItem> = emptyList(),
    val durationSeconds: Long = 0,
    val completedSetCount: Int = 0,
    val totalVolumeKg: Double = 0.0,
)

data class ExerciseHistoryItem(
    val exerciseSessionId: String,
    val name: String,
    val completedSets: Int,
    val summary: String,
    val note: String,
    val heartRate: ExerciseHeartRate? = null,
    val volumeKg: Double = 0.0,
    val bestSet: String? = null,
)

data class ExerciseHeartRate(
    val averageBpm: Int,
    val maximumBpm: Int,
    val recoveryDeltaBpm: Int?,
)

data class HeartRatePoint(val recordedAt: Instant, val bpm: Int)

data class WorkoutHeartRate(
    val workoutId: String,
    val samples: List<HeartRatePoint>,
    val sourceLabel: String = "Fitbit",
)

data class CardioHistoryItem(
    val id: String,
    val kind: String,
    val startedAt: Instant,
    val durationSeconds: Int,
    val distanceMeters: Double?,
    val steps: Int?,
    val averageHeartRate: Int?,
)

data class HistoryState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val strength: List<StrengthHistoryItem> = emptyList(),
    val cardio: List<CardioHistoryItem> = emptyList(),
    val heartRateByWorkout: Map<String, WorkoutHeartRate> = emptyMap(),
    val cardioUnavailableMessage: String? = null,
    val heartRateUnavailableMessage: String? = null,
    val summary: HistorySummary = HistorySummary(),
)

data class HistorySummary(
    val workoutCount: Int = 0,
    val completedSetCount: Int = 0,
    val totalVolumeKg: Double = 0.0,
    val totalMinutes: Long = 0,
)
