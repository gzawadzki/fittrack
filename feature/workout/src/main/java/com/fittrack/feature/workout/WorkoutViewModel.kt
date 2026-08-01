package com.fittrack.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fittrack.core.model.ExerciseSession
import com.fittrack.core.model.ExerciseSessionStatus
import com.fittrack.core.model.repository.PlanRepository
import com.fittrack.core.model.repository.WorkoutRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class WorkoutViewModel(
    private val workouts: WorkoutRepository,
    private val plans: PlanRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkoutUiState())
    val state: StateFlow<WorkoutUiState> = _state.asStateFlow()
    private var workoutObserver: Job? = null
    private var setObserver: Job? = null
    private var previousObserver: Job? = null
    private val mutationMutex = Mutex()

    init {
        viewModelScope.launch {
            workouts.observeActiveWorkout().collect { workout ->
                _state.update { it.copy(workout = workout) }
                workoutObserver?.cancel()
                if (workout != null) {
                    observeExercises(workout.id)
                } else {
                    setObserver?.cancel()
                    previousObserver?.cancel()
                    _state.value = WorkoutUiState()
                }
            }
        }
    }

    fun start(planId: String) = action { workouts.startWorkout(planId) }
    fun startNextExercise() { state.value.nextExercise?.let { next -> action { workouts.startExercise(next.id) } } }

    fun logSet(setId: String, reps: Int, weight: Double, rir: Int?, pain: Boolean, technique: Boolean, symptom: Boolean = false) {
        val error = when { reps !in 1..100 -> "Powtórzenia muszą być w zakresie 1–100"; !weight.isFinite() || weight !in 0.0..1000.0 -> "Nieprawidłowy ciężar"; rir != null && rir !in 0..10 -> "RIR musi być w zakresie 0–10"; else -> null }
        if (error != null) { _state.update { it.copy(error = error) }; return }
        action { workouts.completeSet(setId, reps, weight, rir, pain, technique, symptom) }
    }

    fun finishExercise(note: String) {
        val current = state.value.currentExercise ?: return
        if (state.value.sets.any { it.completedAt == null }) { _state.update { it.copy(error = "Ukończ wszystkie serie albo pomiń ćwiczenie") }; return }
        action { workouts.finishExercise(current.id, note) }
    }

    fun skip(reason: String, note: String = "") {
        (state.value.currentExercise ?: state.value.nextExercise)?.let { current ->
            val persistedReason = listOf(reason.trim(), note.trim().takeIf { it.isNotBlank() }?.let { "Notatka: $it" }).filterNotNull().joinToString("\n")
            action { workouts.skipExercise(current.id, persistedReason) }
        }
    }

    fun loadSwaps(availableEquipment: Set<String>) {
        val current = state.value.currentExercise ?: state.value.nextExercise ?: return
        if (current.status == ExerciseSessionStatus.ACTIVE && state.value.sets.any { it.completedAt != null }) {
            _state.update { it.copy(error = "Nie można zamienić ćwiczenia po zapisaniu serii") }
            return
        }
        action {
            val candidates = plans.findSwapCandidates(current.exerciseId, availableEquipment, 3)
            val focusId = state.value.currentExercise?.id ?: state.value.nextExercise?.id
            if (focusId == current.id) _state.update { it.copy(swapForExerciseId = current.id, candidates = candidates.map { item -> SwapCandidate(item.id, item.name, "Ten sam wzorzec ruchowy i dostępny sprzęt") }) }
        }
    }

    fun swap(candidateId: String) {
        val current = state.value.currentExercise ?: state.value.nextExercise ?: return
        if (state.value.swapForExerciseId != current.id) return
        action { workouts.swapExercise(current.id, candidateId); dismissSwaps() }
    }

    fun dismissSwaps() = _state.update { it.copy(swapForExerciseId = null, candidates = emptyList()) }
    fun finishWorkout(note: String) { state.value.workout?.let { workout -> action { workouts.finishWorkout(workout.id, note) } } }

    private fun observeExercises(workoutId: String) {
        workoutObserver = viewModelScope.launch {
            workouts.observeExerciseSessions(workoutId).collect { sessions ->
                val current = sessions.firstOrNull { it.status == ExerciseSessionStatus.ACTIVE }
                val next = if (current == null) sessions.firstOrNull { it.status == ExerciseSessionStatus.PENDING } else null
                val focus = current ?: next
                val focusChanged = _state.value.currentExercise?.id != current?.id || _state.value.nextExercise?.id != next?.id
                if (focusChanged) {
                    setObserver?.cancel()
                    _state.update { it.copy(sets = emptyList(), previousResult = null) }
                }
                _state.update { it.copy(exercises = sessions, currentExercise = current, nextExercise = next) }
                if (current == null && next != null && _state.value.lastCompletedSetAtUtcMillis == null) {
                    restoreLastRest(sessions)
                }
                previousObserver?.cancel()
                if (current != null) {
                    observeSets(current.id)
                }
                if (focus != null) observePreviousResult(focus.id, focus.planExerciseId, workoutId)
            }
        }
    }

    private fun observeSets(exerciseId: String) {
        setObserver?.cancel()
        setObserver = viewModelScope.launch {
            workouts.observeSetLogs(exerciseId).collect { logs ->
                val latest = logs.mapNotNull { it.completedAt }.maxOrNull()?.toEpochMilli()
                _state.update {
                    it.copy(
                        sets = logs,
                        lastCompletedSetAtUtcMillis = latest ?: it.lastCompletedSetAtUtcMillis,
                        lastRestSeconds = if (latest != null) it.currentExercise?.restSeconds ?: it.lastRestSeconds else it.lastRestSeconds,
                    )
                }
            }
        }
    }

    private suspend fun restoreLastRest(sessions: List<ExerciseSession>) {
        for (session in sessions.sortedByDescending { it.position }) {
            if (session.status == ExerciseSessionStatus.PENDING || session.status == ExerciseSessionStatus.ACTIVE) continue
            val latest = workouts.getSetLogs(session.id).mapNotNull { it.completedAt }.maxOrNull()?.toEpochMilli() ?: continue
            _state.update { it.copy(lastCompletedSetAtUtcMillis = latest, lastRestSeconds = session.restSeconds) }
            return
        }
    }

    private fun observePreviousResult(focusExerciseId: String, planExerciseId: String, workoutId: String) {
        previousObserver = viewModelScope.launch {
            val previous = workouts.getExerciseSessionHistory(planExerciseId, excludeWorkoutId = workoutId, limit = 1).firstOrNull()
            val summary = previous?.let { session ->
                val completed = workouts.getSetLogs(session.id).filter { it.completedAt != null }
                completed.joinToString(" · ") { set -> "${set.actualWeightKg ?: 0.0} kg × ${set.actualReps ?: 0}" }.ifBlank { null }
            }
            val activeFocusId = _state.value.currentExercise?.id ?: _state.value.nextExercise?.id
            if (activeFocusId == focusExerciseId) _state.update { it.copy(previousResult = summary) }
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (!mutationMutex.tryLock()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update { it.copy(error = failure.message ?: "Operacja nie powiodła się") }
            } finally {
                _state.update { it.copy(busy = false) }
                mutationMutex.unlock()
            }
        }
    }
}
