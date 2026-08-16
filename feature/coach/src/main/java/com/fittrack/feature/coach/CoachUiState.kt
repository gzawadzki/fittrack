package com.fittrack.feature.coach

import com.fittrack.core.model.CoachBriefing
import com.fittrack.core.model.CoachMessage
import com.fittrack.core.model.CoachSuggestion

data class CoachUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val briefing: CoachBriefing? = null,
    val conversation: List<CoachMessage> = emptyList(),
    val pending: List<CoachSuggestion> = emptyList(),
    /** [CoachSuggestion.targetId] (a plan exercise id) resolved to its exercise
     * name, so the UI never has to show a raw UUID. Missing entries fall back
     * to the id itself. */
    val targetNames: Map<String, String> = emptyMap(),
    val decidingSuggestionIds: Set<String> = emptySet(),
    val messageDraft: String = "",
)
