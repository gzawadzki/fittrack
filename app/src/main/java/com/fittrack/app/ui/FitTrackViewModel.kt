package com.fittrack.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fittrack.core.model.UserProfile
import com.fittrack.core.model.WorkoutSession
import com.fittrack.core.model.WorkoutStatus
import com.fittrack.core.model.repository.ProfileRepository
import com.fittrack.core.model.repository.WorkoutRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val activeWorkout: WorkoutSession? = null,
    val insights: DashboardInsights = DashboardInsights(),
    val error: String? = null,
)

data class DashboardInsights(
    val workoutCountsByDay: Map<LocalDate, Int> = emptyMap(),
    val personalRecords: List<PersonalRecordUi> = emptyList(),
    val completedWorkoutCount: Int = 0,
)

/** The three powerlifting competition lifts. "Rekordy osobiste" only ever
 * tracks these (and their variations) - accessory work (curls, raises,
 * pulldowns, ...) isn't a meaningful PR to surface on the dashboard. */
enum class CoreLift(val flagLabel: String) {
    SQUAT("PRZYSIAD"),
    BENCH_PRESS("WYCISKANIE"),
    DEADLIFT("MARTWY CIĄG"),
}

data class PersonalRecordUi(
    val exerciseName: String,
    val coreLift: CoreLift,
    val weightKg: Double,
    val reps: Int,
    val estimatedOneRepMaxKg: Double,
    val achievedAt: Instant,
)

private val SquatKeywords = listOf("squat", "przysiad")
private val DeadliftKeywords = listOf("deadlift", "martwy ciąg", "martwy")
private val PressWords = listOf("wyciskanie", "wycisk", "press")
private val BenchSurfaceWords = listOf("leż", "ław", "bench", "incline", "decline", "flat", "chest", "klat", "skoś")

/** Name-based classification, not a catalog field - it has to work against
 * whatever exercise names are already in a user's plans/history (Polish or
 * English, library or hand-typed), not just a curated seed list. Matches on
 * the lift family (any squat/bench/deadlift variation), not equipment, per
 * the "(all variations)" scope. Returns null for anything else, including
 * exercises that merely train the same muscles (leg press, shoulder press). */
fun classifyCoreLift(exerciseName: String): CoreLift? {
    val name = exerciseName.lowercase()
    return when {
        SquatKeywords.any { name.contains(it) } -> CoreLift.SQUAT
        DeadliftKeywords.any { name.contains(it) } -> CoreLift.DEADLIFT
        PressWords.any { name.contains(it) } && BenchSurfaceWords.any { name.contains(it) } -> CoreLift.BENCH_PRESS
        else -> null
    }
}

class FitTrackViewModel(
    profileRepository: ProfileRepository,
    private val workoutRepository: WorkoutRepository,
) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)
    private val insights = workoutRepository.observeHistory(limit = 120)
        .mapLatest { workouts -> workoutRepository.buildDashboardInsights(workouts) }

    val state: StateFlow<AppUiState> = combine(
        profileRepository.observeProfile(),
        workoutRepository.observeActiveWorkout(),
        insights,
        error,
    ) { profile, workout, dashboardInsights, message ->
        AppUiState(false, profile, workout, dashboardInsights, message)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    fun startWorkout(planId: String) {
        viewModelScope.launch {
            try {
                error.value = null
                workoutRepository.startWorkout(planId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                error.value = "Nie udało się rozpocząć treningu."
            }
        }
    }

    fun clearError() = error.update { null }
}

private suspend fun WorkoutRepository.buildDashboardInsights(workouts: List<WorkoutSession>): DashboardInsights {
    val completed = workouts.filter { it.status == WorkoutStatus.COMPLETED }
    val zone = ZoneId.systemDefault()
    val counts = completed.groupingBy { it.startedAt.atZone(zone).toLocalDate() }.eachCount()
    val records = mutableMapOf<String, PersonalRecordUi>()
    completed.forEach { workout ->
        getExerciseSessions(workout.id).forEach { exercise ->
            val coreLift = classifyCoreLift(exercise.exerciseName) ?: return@forEach
            getSetLogs(exercise.id)
                .filter { set ->
                    set.completedAt != null &&
                        set.actualWeightKg != null &&
                        (set.actualReps ?: 0) > 0
                }
                .forEach { set ->
                    val weight = checkNotNull(set.actualWeightKg)
                    val reps = checkNotNull(set.actualReps)
                    val estimate = weight * (1.0 + reps / 30.0)
                    val candidate = PersonalRecordUi(
                        exerciseName = exercise.exerciseName,
                        coreLift = coreLift,
                        weightKg = weight,
                        reps = reps,
                        estimatedOneRepMaxKg = estimate,
                        achievedAt = checkNotNull(set.completedAt),
                    )
                    val current = records[exercise.exerciseName]
                    if (current == null || candidate.estimatedOneRepMaxKg > current.estimatedOneRepMaxKg) {
                        records[exercise.exerciseName] = candidate
                    }
                }
        }
    }
    return DashboardInsights(
        workoutCountsByDay = counts,
        personalRecords = records.values.sortedByDescending { it.achievedAt }.take(3),
        completedWorkoutCount = completed.size,
    )
}
