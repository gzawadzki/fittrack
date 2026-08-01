package com.fittrack.feature.plans

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fittrack.core.model.repository.PlanRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PlansUiState(val plans: List<WorkoutPlanDraft> = emptyList(), val editing: WorkoutPlanDraft? = null, val saving: Boolean = false, val error: String? = null)

class PlansViewModel(private val repository: PlanRepository, private val savedStateHandle: SavedStateHandle) : ViewModel() {
    private val editor = MutableStateFlow<WorkoutPlanDraft?>(savedStateHandle["planDraft"])
    private val saving = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    val state: StateFlow<PlansUiState> = combine(repository.observePlans(), repository.observeExerciseLibrary(), editor, saving, error) { plans, library, edit, busy, failure ->
        val byId = library.associateBy { it.id }
        PlansUiState(plans.map { it.toDraft(byId) }, edit, busy, failure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlansUiState())

    fun edit(plan: WorkoutPlanDraft = WorkoutPlanDraft()) { editor.value = plan; savedStateHandle["planDraft"] = plan }
    fun change(plan: WorkoutPlanDraft) { editor.value = plan; savedStateHandle["planDraft"] = plan; error.value = null }
    fun cancelEdit() { editor.value = null; savedStateHandle.remove<WorkoutPlanDraft>("planDraft"); error.value = null }

    fun save() = viewModelScope.launch {
        val draft = editor.value ?: return@launch
        draft.validationError()?.let { error.value = it; return@launch }
        saving.value = true
        runCatching {
            if (draft.version > 0) {
                val persisted = requireNotNull(repository.getPlan(draft.id)) { "Plan nie istnieje" }
                require(persisted.version == draft.version) { "Plan zmienił się podczas edycji. Otwórz go ponownie." }
            }
            draft.exercises.forEach { repository.upsertExercise(it.toExercise()) }
            if (draft.version <= 0) {
                repository.createPlan(draft.copy(version = 1).toDomain())
            } else {
                repository.updatePlan(draft.toDomain())
            }
        }.onSuccess { cancelEdit() }.onFailure { error.value = it.message ?: "Nie udało się zapisać planu" }
        saving.value = false
    }
}
