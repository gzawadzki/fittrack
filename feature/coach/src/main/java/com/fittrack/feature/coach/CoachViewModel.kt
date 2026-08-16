package com.fittrack.feature.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fittrack.core.model.CoachMessage
import com.fittrack.core.model.CoachMessageRole
import com.fittrack.core.model.CoachReply
import com.fittrack.core.model.repository.CoachBriefingCache
import com.fittrack.core.model.repository.CoachGateway
import com.fittrack.core.model.repository.PlanRepository
import com.fittrack.core.model.repository.WorkoutRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class CoachViewModel(
    private val workoutRepository: WorkoutRepository,
    private val planRepository: PlanRepository,
    private val coachGateway: CoachGateway,
    private val briefingCache: CoachBriefingCache,
) : ViewModel() {
    // Coach model calls take seconds to tens of seconds (LLM generation).
    // Starting from the last cached briefing instead of null means the tab
    // shows something immediately instead of a blank loading state on every
    // single visit while refreshBriefing() fetches a fresh one in the background.
    private val cachedBriefing = briefingCache.read()
    private val local = MutableStateFlow(CoachUiState(briefing = cachedBriefing, isLoading = cachedBriefing == null))

    val state = combine(
        local,
        workoutRepository.observePendingSuggestions(),
    ) { current, pending -> current to pending }
        .mapLatest { (current, pending) ->
            val targetNames = pending.associate { suggestion ->
                val name = runCatching { planRepository.getPlanExercise(suggestion.targetId)?.exerciseName }
                    .getOrNull()
                suggestion.targetId to (name ?: suggestion.targetId)
            }
            current.copy(pending = pending, targetNames = targetNames)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CoachUiState(briefing = cachedBriefing, isLoading = cachedBriefing == null),
        )

    init {
        refreshBriefing()
    }

    fun updateMessageDraft(value: String) {
        local.update { it.copy(messageDraft = value) }
    }

    fun clearError() {
        local.update { it.copy(errorMessage = null) }
    }

    fun refreshBriefing() {
        if (local.value.isRefreshing) return
        viewModelScope.launch {
            local.update {
                it.copy(
                    isLoading = it.briefing == null,
                    isRefreshing = true,
                    errorMessage = null,
                )
            }
            try {
                val briefing = coachGateway.getBriefing()
                briefingCache.write(briefing)
                local.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        briefing = briefing,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                local.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = failure.userMessage("Nie udało się pobrać briefingu."),
                    )
                }
            }
        }
    }

    fun sendMessage() {
        val message = local.value.messageDraft.trim()
        if (message.isBlank() || local.value.isSending) return

        viewModelScope.launch {
            local.update { it.copy(isSending = true, errorMessage = null) }
            try {
                val reply = coachGateway.sendMessage(message)
                val userMessage = CoachMessage(
                    id = UUID.randomUUID().toString(),
                    role = CoachMessageRole.USER,
                    text = message,
                    sentAt = Instant.now(),
                )
                local.update {
                    it.copy(
                        isSending = false,
                        messageDraft = "",
                        conversation = it.conversation + userMessage + reply.toCoachMessage(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                local.update {
                    it.copy(
                        isSending = false,
                        errorMessage = failure.userMessage("Nie udało się wysłać wiadomości."),
                    )
                }
            }
        }
    }

    fun requestDebrief(workoutSessionId: String) {
        if (workoutSessionId.isBlank() || local.value.isSending) return
        viewModelScope.launch {
            local.update { it.copy(isSending = true, errorMessage = null) }
            try {
                val reply = coachGateway.getDebrief(workoutSessionId)
                local.update {
                    it.copy(
                        isSending = false,
                        conversation = it.conversation + reply.toCoachMessage(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                local.update {
                    it.copy(
                        isSending = false,
                        errorMessage = failure.userMessage("Nie udało się pobrać podsumowania treningu."),
                    )
                }
            }
        }
    }

    fun acceptSuggestion(id: String) = decideSuggestion(id, accept = true, reason = "")

    fun rejectSuggestion(id: String, reason: String?) =
        decideSuggestion(id, accept = false, reason = reason.orEmpty().trim())

    private fun decideSuggestion(id: String, accept: Boolean, reason: String) {
        if (id.isBlank() || id in local.value.decidingSuggestionIds) return
        local.update { it.copy(decidingSuggestionIds = it.decidingSuggestionIds + id, errorMessage = null) }

        viewModelScope.launch {
            try {
                if (accept) {
                    val suggestion = requireNotNull(workoutRepository.getSuggestion(id)) { "Suggestion does not exist" }
                    planRepository.applySuggestion(suggestion)
                }
                workoutRepository.decideSuggestion(id, accept, reason)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                local.update {
                    it.copy(errorMessage = failure.userMessage("Nie udało się zapisać decyzji."))
                }
            } finally {
                local.update { it.copy(decidingSuggestionIds = it.decidingSuggestionIds - id) }
            }
        }
    }
}

private fun CoachReply.toCoachMessage() = CoachMessage(
    id = id,
    role = CoachMessageRole.COACH,
    text = text,
    sentAt = generatedAt,
    citations = citations,
)

private fun Throwable.userMessage(fallback: String): String = fallback
