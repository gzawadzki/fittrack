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
    val decidingSuggestionIds: Set<String> = emptySet(),
    val messageDraft: String = "",
)
