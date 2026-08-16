package com.fittrack.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fittrack.core.model.ExerciseSession
import com.fittrack.core.model.ExerciseHeartRateSummary
import com.fittrack.core.model.CardioActivity
import com.fittrack.core.model.SetLog
import com.fittrack.core.model.WorkoutSession
import com.fittrack.core.model.WorkoutStatus
import com.fittrack.core.model.repository.FitbitDataRepository
import com.fittrack.core.model.repository.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val workoutRepository: WorkoutRepository,
    private val fitbitDataRepository: FitbitDataRepository,
) : ViewModel() {
    private val refreshes = MutableStateFlow(0)
    private val ui = MutableStateFlow(HistoryUiState())

    val state: StateFlow<HistoryState> = refreshes
        .flatMapLatest {
            combine(
                workoutRepository.observeHistory(limit = 30),
                fitbitDataRepository.observeCardioActivities(),
                fitbitDataRepository.observeHeartRateSamples(),
                fitbitDataRepository.observeExerciseHeartRateSummaries(),
            ) { workouts, cardio, heartRateSamples, exerciseSummaries ->
                HistorySources(workouts, cardio, heartRateSamples, exerciseSummaries)
            }
                .mapLatest { sources ->
                    val summaries = sources.exerciseSummaries.associateBy { it.exerciseSessionId }
                    val strength = sources.workouts
                        .filter { it.status == WorkoutStatus.COMPLETED }
                        .map { workout -> workoutRepository.mapHistory(workout, summaries) }
                    val heartRateByWorkout = sources.heartRateSamples
                        .groupBy { it.workoutSessionId }
                        .mapValues { (workoutId, samples) ->
                            WorkoutHeartRate(
                                workoutId = workoutId,
                                samples = samples.sortedBy { it.sampledAt }.map {
                                    HeartRatePoint(recordedAt = it.sampledAt, bpm = it.bpm)
                                },
                            )
                        }
                    HistoryState(
                        isLoading = false,
                        strength = strength,
                        cardio = sources.cardio.map(CardioActivity::toHistoryItem),
                        heartRateByWorkout = heartRateByWorkout,
                        summary = HistorySummary(
                            workoutCount = strength.size,
                            completedSetCount = strength.sumOf { it.completedSetCount },
                            totalVolumeKg = strength.sumOf { it.totalVolumeKg },
                            totalMinutes = strength.sumOf { it.durationSeconds } / 60,
                        ),
                    )
                }
                .onStart { emit(HistoryState(isLoading = true)) }
                .catch {
                    emit(
                        HistoryState(
                            isLoading = false,
                            errorMessage = "Nie udało się odczytać historii treningów.",
                        ),
                    )
                }
        }
        .combine(ui) { historyState, uiState ->
            historyState.copy(
                pendingDeleteWorkoutId = uiState.pendingDeleteWorkoutId,
                isDeleting = uiState.isDeleting,
                deleteErrorMessage = uiState.deleteErrorMessage,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = HistoryState(isLoading = true),
        )

    fun refresh() {
        refreshes.update(Int::inc)
    }

    fun requestDeleteWorkout(workoutId: String) {
        ui.update { it.copy(pendingDeleteWorkoutId = workoutId, deleteErrorMessage = null) }
    }

    fun cancelDeleteWorkout() {
        ui.update { it.copy(pendingDeleteWorkoutId = null) }
    }

    fun confirmDeleteWorkout() {
        val workoutId = ui.value.pendingDeleteWorkoutId ?: return
        if (ui.value.isDeleting) return
        viewModelScope.launch {
            ui.update { it.copy(isDeleting = true, deleteErrorMessage = null) }
            try {
                workoutRepository.deleteWorkout(workoutId)
                ui.update { it.copy(isDeleting = false, pendingDeleteWorkoutId = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                ui.update { it.copy(isDeleting = false, deleteErrorMessage = "Nie udało się usunąć treningu.") }
            }
        }
    }
}

private data class HistoryUiState(
    val pendingDeleteWorkoutId: String? = null,
    val isDeleting: Boolean = false,
    val deleteErrorMessage: String? = null,
)

private data class HistorySources(
    val workouts: List<WorkoutSession>,
    val cardio: List<CardioActivity>,
    val heartRateSamples: List<com.fittrack.core.model.HeartRateSample>,
    val exerciseSummaries: List<ExerciseHeartRateSummary>,
)

internal suspend fun WorkoutRepository.mapHistory(
    workout: WorkoutSession,
    heartRateSummaries: Map<String, ExerciseHeartRateSummary> = emptyMap(),
): StrengthHistoryItem {
    val exerciseSessions = getExerciseSessions(workout.id).sortedBy(ExerciseSession::position)
    var completedSetCount = 0
    var totalVolumeKg = 0.0
    val exercises = exerciseSessions.map { exercise ->
        val sets = getSetLogs(exercise.id).sortedBy(SetLog::position)
        val completed = sets.filter { it.completedAt != null && !it.skipped }
        completedSetCount += completed.size
        totalVolumeKg += completed.sumOf { (it.actualWeightKg ?: 0.0) * (it.actualReps ?: 0) }
        exercise.toHistoryItem(sets, heartRateSummaries[exercise.id])
    }
    return StrengthHistoryItem(
        workoutId = workout.id,
        planName = workout.planSnapshot.planName,
        startedAt = workout.startedAt,
        endedAt = workout.endedAt,
        note = workout.note,
        exerciseCount = exerciseSessions.size,
        fitbitState = workout.fitbitSyncStatus,
        exercises = exercises,
        durationSeconds = workout.endedAt?.let { java.time.Duration.between(workout.startedAt, it).seconds.coerceAtLeast(0) } ?: 0,
        completedSetCount = completedSetCount,
        totalVolumeKg = totalVolumeKg,
    )
}

private fun ExerciseSession.toHistoryItem(
    sets: List<SetLog>,
    heartRateSummary: ExerciseHeartRateSummary?,
): ExerciseHistoryItem {
    val completed = sets.filter { it.completedAt != null && !it.skipped }
    val volume = completed.sumOf { (it.actualWeightKg ?: 0.0) * (it.actualReps ?: 0) }
    val best = completed.maxByOrNull { (it.actualWeightKg ?: 0.0) * (1.0 + (it.actualReps ?: 0) / 30.0) }
    return ExerciseHistoryItem(
        exerciseSessionId = id,
        name = exerciseName,
        completedSets = completed.size,
        summary = completed.toSetSummary(),
        note = when {
            note.isNotBlank() -> note
            skipReason.isNotBlank() -> "Pominięto: $skipReason"
            else -> ""
        },
        heartRate = heartRateSummary?.takeIf { it.hasData }?.let {
            ExerciseHeartRate(
                averageBpm = checkNotNull(it.averageBpm),
                maximumBpm = checkNotNull(it.maximumBpm),
                recoveryDeltaBpm = it.recoveryDeltaBpm,
            )
        },
        volumeKg = volume,
        bestSet = best?.let { "${formatWeight(it.actualWeightKg ?: 0.0)} kg × ${it.actualReps ?: 0}" },
    )
}

private fun CardioActivity.toHistoryItem() = CardioHistoryItem(
    id = id,
    kind = kind,
    startedAt = startedAt,
    durationSeconds = durationSeconds,
    distanceMeters = distanceMeters,
    steps = steps,
    averageHeartRate = averageHeartRate,
)

private fun List<SetLog>.toSetSummary(): String = when {
    isEmpty() -> "brak ukończonych serii"
    else -> joinToString(separator = " · ") { set ->
        val weight = set.actualWeightKg?.let(::formatWeight) ?: "—"
        val reps = set.actualReps?.toString() ?: "—"
        "$weight kg × $reps"
    }
}

private fun formatWeight(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
