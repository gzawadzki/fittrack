package com.fittrack.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fittrack.core.designsystem.FitTrackCard
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun OnboardingRoute(
    viewModel: OnboardingViewModel,
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.completed) {
        if (state.completed) {
            onCompleted()
            viewModel.consumeCompletion()
        }
    }
    OnboardingScreen(
        state = state,
        onChange = viewModel::update,
        onReview = viewModel::showReview,
        onEdit = viewModel::edit,
        onApprove = viewModel::approve,
        modifier = modifier,
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onChange: ((ProfileDraft) -> ProfileDraft) -> Unit,
    onReview: () -> Unit,
    onEdit: () -> Unit,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(20.dp, 28.dp, 20.dp, 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("FITTRACK COACH", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                Text(if (state.review) "Sprawdź swój profil" else "Zbudujmy Twój kontekst", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (state.review) "Nic nie zostanie zapisane bez Twojej zgody."
                    else "Kilka konkretów wystarczy, żeby trener podejmował lepsze decyzje.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.review) {
            item { ProfileSummary(state.draft) }
            item {
                Button(onClick = onApprove, modifier = Modifier.fillMaxWidth(), enabled = !state.saving) {
                    Text(if (state.saving) "Zapisywanie…" else "Zatwierdzam profil")
                }
            }
            item { TextButton(onClick = onEdit, modifier = Modifier.fillMaxWidth(), enabled = !state.saving) { Text("Wróć do edycji") } }
        } else {
            item { PromptField("Jaki jest Twój główny cel?", state.draft.goal) { value -> onChange { it.copy(goal = value) } } }
            item { PromptField("Doświadczenie treningowe", state.draft.experience) { value -> onChange { it.copy(experience = value) } } }
            item {
                EquipmentChecklist(state.draft.equipment) { key ->
                    onChange { draft ->
                        draft.copy(equipment = if (key in draft.equipment) draft.equipment - key else draft.equipment + key)
                    }
                }
            }
            item { PromptField("Dni i godziny treningów", state.draft.schedule) { value -> onChange { it.copy(schedule = value) } } }
            item { PromptField("Ograniczenia lub urazy", state.draft.constraints) { value -> onChange { it.copy(constraints = value) } } }
            item { PromptField("Preferencje", state.draft.preferences) { value -> onChange { it.copy(preferences = value) } } }
            item { Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) { Text("Przejdź do podsumowania") } }
        }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun PromptField(label: String, value: String, update: (String) -> Unit) =
    OutlinedTextField(value = value, onValueChange = update, label = { Text(label) }, modifier = Modifier.fillMaxWidth())

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EquipmentChecklist(selected: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Dostępny sprzęt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EquipmentOption.ALL.forEach { (key, label) ->
                FilterChip(selected = key in selected, onClick = { onToggle(key) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun ProfileSummary(draft: ProfileDraft) {
    FitTrackCard(Modifier.fillMaxWidth()) {
            listOf(
                "Cel" to draft.goal,
                "Doświadczenie" to draft.experience,
                "Sprzęt" to draft.equipment.sorted().joinToString(", ") { EquipmentOption.label(it) },
                "Harmonogram" to draft.schedule,
                "Ograniczenia" to draft.constraints,
                "Preferencje" to draft.preferences,
            ).filter { it.second.isNotBlank() }.forEach { (title, content) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(content)
                }
            }
            Text("Profil zostanie zapisany dopiero po zatwierdzeniu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
