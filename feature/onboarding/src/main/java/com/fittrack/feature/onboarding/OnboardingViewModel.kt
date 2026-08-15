package com.fittrack.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fittrack.core.model.UserProfile
import com.fittrack.core.model.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Serializable
import java.util.UUID

/** Equipment types the exercise library knows about, with their Polish
 * display labels. Most users train at a reasonably well-equipped gym missing
 * only a few things, so onboarding defaults every type to selected and asks
 * the user to uncheck what they don't have, rather than starting empty. */
object EquipmentOption {
    val ALL: List<Pair<String, String>> = listOf(
        "BARBELL" to "Sztanga",
        "DUMBBELL" to "Hantle",
        "MACHINE" to "Maszyny",
        "CABLE" to "Wyciągi",
        "BODYWEIGHT" to "Masa własna",
    )
    val KEYS: Set<String> = ALL.map { it.first }.toSet()
    fun label(key: String): String = ALL.firstOrNull { it.first == key }?.second ?: key
}

data class ProfileDraft(
    val goal: String = "",
    val experience: String = "",
    val equipment: Set<String> = EquipmentOption.KEYS,
    val schedule: String = "",
    val constraints: String = "",
    val preferences: String = "",
) : Serializable

data class OnboardingUiState(
    val draft: ProfileDraft = ProfileDraft(),
    val review: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
)

class OnboardingViewModel(
    private val repository: ProfileRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(
        OnboardingUiState(
            draft = savedStateHandle["profileDraft"] ?: ProfileDraft(),
            review = savedStateHandle["profileReview"] ?: false,
        ),
    )
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun update(transform: (ProfileDraft) -> ProfileDraft) {
        val next = transform(_state.value.draft)
        savedStateHandle["profileDraft"] = next
        _state.value = _state.value.copy(draft = next, error = null)
    }

    fun showReview() {
        validate(_state.value.draft)?.let { _state.value = _state.value.copy(error = it); return }
        savedStateHandle["profileReview"] = true
        _state.value = _state.value.copy(review = true, error = null)
    }

    fun edit() {
        savedStateHandle["profileReview"] = false
        _state.value = _state.value.copy(review = false)
    }

    fun approve() = viewModelScope.launch {
        val draft = _state.value.draft
        validate(draft)?.let { _state.value = _state.value.copy(error = it); return@launch }
        _state.value = _state.value.copy(saving = true, error = null)
        runCatching {
            val current = repository.getProfile()
            repository.saveProfile(
                UserProfile(
                    id = current?.id ?: UUID.randomUUID().toString(),
                    goal = draft.goal.trim(),
                    experience = draft.experience.trim(),
                    equipment = draft.equipment,
                    schedule = draft.schedule.trim(),
                    constraints = draft.constraints.trim(),
                    preferences = draft.preferences.trim(),
                    approved = true,
                    version = (current?.version ?: 0) + 1,
                ),
            )
        }.onSuccess {
            savedStateHandle.remove<ProfileDraft>("profileDraft")
            savedStateHandle.remove<Boolean>("profileReview")
            _state.value = _state.value.copy(saving = false, completed = true)
        }.onFailure { _state.value = _state.value.copy(saving = false, error = it.message ?: "Nie udało się zapisać profilu") }
    }

    fun consumeCompletion() {
        _state.value = _state.value.copy(completed = false)
    }

    private fun validate(draft: ProfileDraft): String? = when {
        draft.goal.isBlank() -> "Podaj główny cel"
        draft.experience.isBlank() -> "Podaj doświadczenie treningowe"
        draft.equipment.isEmpty() -> "Zaznacz co najmniej jeden dostępny sprzęt"
        draft.schedule.isBlank() -> "Podaj planowane dni i godziny"
        else -> null
    }
}
