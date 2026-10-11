package com.fittrack.feature.coach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fittrack.core.designsystem.ErrorState
import com.fittrack.core.designsystem.LoadingState
import com.fittrack.core.designsystem.FitTrackCard
import com.fittrack.core.model.Citation
import com.fittrack.core.model.CoachMessage
import com.fittrack.core.model.CoachMessageRole
import com.fittrack.core.model.CoachSuggestion
import com.fittrack.core.model.Confidence
import com.fittrack.core.model.SuggestionType
import java.net.URI

@Composable
fun CoachRoute(
    viewModel: CoachViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    CoachScreen(
        state = state,
        onRefresh = viewModel::refreshBriefing,
        onDraftChange = viewModel::updateMessageDraft,
        onSendMessage = viewModel::sendMessage,
        onAccept = viewModel::acceptSuggestion,
        onReject = viewModel::rejectSuggestion,
        onDismissError = viewModel::clearError,
        onOpenCitation = { url ->
            if (url.isSafeHttpsUrl()) runCatching { uriHandler.openUri(url) }
        },
        modifier = modifier,
    )
}

@Composable
fun CoachScreen(
    state: CoachUiState,
    onRefresh: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onAccept: (String) -> Unit,
    onReject: (String, String?) -> Unit,
    onDismissError: () -> Unit,
    onOpenCitation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading && state.briefing == null -> LoadingState("Przygotowanie briefingu…", modifier)
        state.errorMessage != null && state.briefing == null && state.conversation.isEmpty() ->
            ErrorState(state.errorMessage, onRefresh, modifier)
        else -> CoachContent(
            state = state,
            onRefresh = onRefresh,
            onDraftChange = onDraftChange,
            onSendMessage = onSendMessage,
            onAccept = onAccept,
            onReject = onReject,
            onDismissError = onDismissError,
            onOpenCitation = onOpenCitation,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoachContent(
    state: CoachUiState,
    onRefresh: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onAccept: (String) -> Unit,
    onReject: (String, String?) -> Unit,
    onDismissError: () -> Unit,
    onOpenCitation: (String) -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("COACH", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text("Twój trener", style = MaterialTheme.typography.headlineMedium)
                    Text("Decyzje pozostają po Twojej stronie.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.errorMessage?.let { error ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            OutlinedButton(onClick = onDismissError) { Text("Zamknij") }
                        }
                    }
                }
            }
            item {
                BriefingCard(
                    briefing = state.briefing?.text,
                    citations = state.briefing?.citations.orEmpty(),
                    refreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                    onOpenCitation = onOpenCitation,
                )
            }
            item { Text("Propozycje do zatwierdzenia", style = MaterialTheme.typography.titleLarge) }
            if (state.pending.isEmpty()) item { Text("Brak zmian oczekujących na decyzję.") }
            items(state.pending, key = { it.id }) { suggestion ->
                SuggestionCard(
                    item = suggestion,
                    targetName = state.targetNames[suggestion.targetId] ?: suggestion.targetId,
                    deciding = suggestion.id in state.decidingSuggestionIds,
                    onAccept = onAccept,
                    onReject = onReject,
                    onOpenCitation = onOpenCitation,
                )
            }
            item { Text("Rozmowa", style = MaterialTheme.typography.titleLarge) }
            items(state.conversation, key = { it.id }) { MessageBubble(it, onOpenCitation) }
        }
        }
        ChatComposer(
            value = state.messageDraft,
            enabled = !state.isSending,
            onValueChange = onDraftChange,
            onSend = onSendMessage,
        )
    }
}

@Composable
private fun BriefingCard(
    briefing: String?,
    citations: List<Citation>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenCitation: (String) -> Unit,
) {
    FitTrackCard(Modifier.fillMaxWidth()) {
            Text("DZISIEJSZY BRIEFING", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(briefing ?: "Briefing nie jest jeszcze dostępny.")
            CitationList(citations, onOpenCitation)
            Text(
                "Porady są edukacyjne i nie zastępują pomocy medycznej.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onRefresh, enabled = !refreshing) {
                Text(if (refreshing) "Odświeżanie…" else "Odśwież briefing")
            }
    }
}

@Composable
private fun SuggestionCard(
    item: CoachSuggestion,
    targetName: String,
    deciding: Boolean,
    onAccept: (String) -> Unit,
    onReject: (String, String?) -> Unit,
    onOpenCitation: (String) -> Unit,
) {
    var askReason by rememberSaveable(item.id) { mutableStateOf(false) }
    var reason by rememberSaveable(item.id) { mutableStateOf("") }
    FitTrackCard(Modifier.fillMaxWidth()) {
            Text(item.type.toPolishLabel(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text(targetName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${item.before} → ${item.after}", style = MaterialTheme.typography.headlineSmall)
            Text(item.explanation)
            if (item.signals.isNotEmpty()) {
                Text("Sygnały: ${item.signals.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Pewność: ${item.confidence.toPolishLabel()}", style = MaterialTheme.typography.bodySmall)
            CitationList(item.citations, onOpenCitation)
            if (askReason) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Powód odrzucenia (opcjonalnie)") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !deciding,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAccept(item.id) }, enabled = !deciding) {
                    Text(if (deciding) "Zapisywanie…" else "Akceptuj")
                }
                OutlinedButton(
                    onClick = {
                        if (askReason) onReject(item.id, reason.ifBlank { null }) else askReason = true
                    },
                    enabled = !deciding,
                ) {
                    Text(if (askReason) "Potwierdź odrzucenie" else "Odrzuć")
                }
            }
    }
}

@Composable
private fun MessageBubble(message: CoachMessage, onOpenCitation: (String) -> Unit) {
    val own = message.role == CoachMessageRole.USER
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (own) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (own) "Ty" else "Trener", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(message.text)
            CitationList(message.citations, onOpenCitation)
        }
    }
}

@Composable
private fun CitationList(citations: List<Citation>, onOpenCitation: (String) -> Unit) {
    citations.forEach { citation ->
        val safe = citation.url.isSafeHttpsUrl()
        Text(
            text = "Źródło: ${citation.title}${citation.claim.takeIf(String::isNotBlank)?.let { " — $it" }.orEmpty()}",
            style = MaterialTheme.typography.bodySmall,
            color = if (safe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (safe) {
                Modifier
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .clickable(role = Role.Button, onClickLabel = "Otwórz źródło") { onOpenCitation(citation.url) }
            } else {
                Modifier
            },
        )
    }
}

@Composable
private fun ChatComposer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Zapytaj trenera") },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            maxLines = 3,
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSend, enabled = enabled && value.isNotBlank()) { Text("Wyślij") }
      }
    }
}

private fun SuggestionType.toPolishLabel(): String = when (this) {
    SuggestionType.LOAD -> "Ciężar"
    SuggestionType.VOLUME -> "Objętość"
    SuggestionType.SWAP -> "Zamiana ćwiczenia"
    SuggestionType.RESCHEDULE -> "Zmiana terminu"
    SuggestionType.HOLD -> "Bez zmian"
}

private fun Confidence.toPolishLabel(): String = when (this) {
    Confidence.LOW -> "Niska"
    Confidence.MEDIUM -> "Średnia"
    Confidence.HIGH -> "Wysoka"
}

internal fun String.isSafeHttpsUrl(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}.getOrDefault(false)
