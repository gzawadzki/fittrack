package com.fittrack.feature.workout

import com.fittrack.core.model.*

data class SwapCandidate(val id: String, val name: String, val rationale: String)

data class WorkoutUiState(
    val workout: WorkoutSession? = null,
    val exercises: List<ExerciseSession> = emptyList(),
    val currentExercise: ExerciseSession? = null,
    val nextExercise: ExerciseSession? = null,
    val sets: List<SetLog> = emptyList(),
    val lastCompletedSetAtUtcMillis: Long? = null,
    val lastRestSeconds: Int = 0,
    val previousResult: String? = null,
    val swapForExerciseId: String? = null,
    val candidates: List<SwapCandidate> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

fun interface UtcClock { fun nowUtcEpochMillis(): Long }
object SystemUtcClock : UtcClock { override fun nowUtcEpochMillis() = System.currentTimeMillis() }
