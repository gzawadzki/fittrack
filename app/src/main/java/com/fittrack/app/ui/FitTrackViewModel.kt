package com.fittrack.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fittrack.app.FitTrackApplication
import com.fittrack.app.data.FitTrackRepository
import com.fittrack.app.data.local.*
import com.fittrack.app.domain.ReadinessCalculator
import com.fittrack.app.domain.ReadinessLevel
import com.fittrack.app.domain.RecoveryDay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AppMessage(val text: String, val isError: Boolean = false)

class FitTrackViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as FitTrackApplication).repository

    val profile = repository.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val plans = repository.plans.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeWorkout = repository.activeWorkout.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history = repository.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val health = repository.health.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val suggestions = repository.suggestions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val exerciseSessions: StateFlow<List<ExerciseSessionEntity>> = activeWorkout
        .flatMapLatest { workout -> workout?.let { repository.exerciseSessions(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentExercise: StateFlow<ExerciseSessionEntity?> = exerciseSessions
        .map { sessions -> sessions.firstOrNull { it.status == "ACTIVE" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val currentSets: StateFlow<List<SetLogEntity>> = currentExercise
        .flatMapLatest { exercise -> exercise?.let { repository.setLogs(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val readiness: StateFlow<ReadinessLevel> = health.map { rows ->
        val ordered = rows.sortedBy { it.date }
        val today = ordered.lastOrNull() ?: return@map ReadinessLevel.UNKNOWN
        ReadinessCalculator.calculate(
            RecoveryDay(today.sleepMinutes, today.restingHeartRate),
            ordered.dropLast(1).map { RecoveryDay(it.sleepMinutes, it.restingHeartRate) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadinessLevel.UNKNOWN)

    private val _message = MutableStateFlow<AppMessage?>(null)
    val message = _message.asStateFlow()
    private val _swapCandidates = MutableStateFlow<List<ExerciseEntity>>(emptyList())
    val swapCandidates = _swapCandidates.asStateFlow()

    fun clearMessage() { _message.value = null }

    fun saveProfile(goal: String, experience: String, equipment: String, schedule: String, constraints: String, preferences: String) = runAction {
        require(goal.isNotBlank()) { "Podaj główny cel" }
        repository.saveProfile(goal, experience, equipment, schedule, constraints, preferences)
        _message.value = AppMessage("Profil trenera został zatwierdzony")
    }

    fun startWorkout(planId: String) = runAction { repository.startWorkout(planId) }

    fun createPlan(name: String, exercises: List<FitTrackRepository.NewPlanExercise>) = runAction {
        repository.createPlan(name, name, exercises)
        _message.value = AppMessage("Plan został dodany")
    }

    fun completeSet(set: SetLogEntity, reps: Int, weight: Double, rir: Int?, pain: Boolean, technique: Boolean) = runAction {
        repository.completeSet(
            set.id, set.exerciseSessionId, set.position, set.setType, set.targetRepMin, set.targetRepMax,
            set.targetRir, set.targetWeightKg, reps, weight, rir, pain, technique,
        )
    }

    fun finishExercise(id: String, note: String) = runAction { repository.finishExercise(id, note) }
    fun skipExercise(id: String, reason: String) = runAction { repository.skipExercise(id, reason) }

    fun loadSwapCandidates(id: String, equipment: String) = runAction {
        val available = equipment.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        _swapCandidates.value = repository.swapCandidates(id, available.ifEmpty { listOf("BARBELL", "DUMBBELL", "CABLE", "MACHINE", "BODYWEIGHT") })
    }

    fun swapExercise(id: String, replacementId: String) = runAction {
        repository.swapExercise(id, replacementId)
        _swapCandidates.value = emptyList()
    }

    fun finishWorkout(id: String, note: String) = runAction {
        repository.finishWorkout(id, note, readiness.value)
        _message.value = AppMessage("Trening zapisany. Import tętna czeka na synchronizację Fitbit.")
    }

    fun decideSuggestion(id: String, accept: Boolean) = runAction {
        repository.decideSuggestion(id, accept)
        _message.value = AppMessage(if (accept) "Zmiana zastosowana w planie" else "Sugestia odrzucona")
    }

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { _message.value = AppMessage(it.message ?: "Nieznany błąd", true) }
        }
    }
}
