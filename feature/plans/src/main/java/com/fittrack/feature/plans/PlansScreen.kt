package com.fittrack.feature.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fittrack.core.designsystem.FitTrackCard

@Composable
fun PlansRoute(
    viewModel: PlansViewModel,
    onStartWorkout: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editing = state.editing
    if (editing == null) {
        PlansScreen(
            plans = state.plans,
            onCreate = { viewModel.edit() },
            onEdit = viewModel::edit,
            onStartWorkout = { onStartWorkout(it.id) },
            modifier = modifier,
        )
    } else {
        PlanEditorScreen(
            draft = editing,
            saving = state.saving,
            error = state.error,
            onChange = viewModel::change,
            onSave = viewModel::save,
            onCancel = viewModel::cancelEdit,
            modifier = modifier,
        )
    }
}

@Composable
fun PlansScreen(
    plans: List<WorkoutPlanDraft>,
    onCreate: () -> Unit,
    onEdit: (WorkoutPlanDraft) -> Unit,
    onStartWorkout: (WorkoutPlanDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("PLANY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text("Twoje treningi", style = MaterialTheme.typography.headlineMedium)
                }
                IconButton(onClick = onCreate) { Icon(Icons.Default.Add, "Dodaj plan", tint = MaterialTheme.colorScheme.primary) }
            }
        }
        items(plans, key = { it.id }) { plan ->
            FitTrackCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(plan.dayLabel.ifBlank { "DOWOLNY DZIEŃ" }.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(plan.name, style = MaterialTheme.typography.titleLarge)
                        Text("${plan.exercises.size} ćwiczeń", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onEdit(plan) }) { Icon(Icons.Default.Edit, "Edytuj") }
                }
                if (plan.exercises.isNotEmpty()) {
                    Text(
                        plan.exercises.take(3).joinToString("  •  ") { it.name }.let { if (plan.exercises.size > 3) "$it  +${plan.exercises.size - 3}" else it },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    { onStartWorkout(plan) },
                    Modifier.fillMaxWidth(),
                    enabled = plan.exercises.isNotEmpty(),
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text("  Rozpocznij trening")
                }
            }
        }
        if (plans.isEmpty()) item { Text("Dodaj pierwszy plan, aby rozpocząć trening offline.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            OutlinedButton(onCreate, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Text("  Nowy plan")
            }
        }
    }
}
