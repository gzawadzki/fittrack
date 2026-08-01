package com.fittrack.app.di

import com.fittrack.core.model.WorkoutSession
import com.fittrack.core.model.repository.WorkoutRepository
import com.fittrack.core.training.CoachEngine

/** Adds post-workout application side effects without coupling feature/workout to Android or networking. */
class IntegratedWorkoutRepository(
    private val delegate: WorkoutRepository,
    private val coachEngine: CoachEngine,
    private val afterWorkout: (String) -> Unit,
) : WorkoutRepository by delegate {
    override suspend fun finishWorkout(workoutId: String, note: String): WorkoutSession {
        val completed = delegate.finishWorkout(workoutId, note)
        runCatching {
            val suggestions = coachEngine.generateSuggestions(workoutId)
            if (suggestions.isNotEmpty()) delegate.saveSuggestions(suggestions)
        }
        afterWorkout(completed.id)
        return completed
    }
}
