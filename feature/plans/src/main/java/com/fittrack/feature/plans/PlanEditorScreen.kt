package com.fittrack.feature.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fittrack.core.designsystem.FitTrackCard
import kotlin.math.roundToInt

private val weekDays = listOf(
    null to "Dowolny",
    1 to "Pon",
    2 to "Wt",
    3 to "Śr",
    4 to "Czw",
    5 to "Pt",
    6 to "Sob",
    7 to "Niedz",
)

@Composable
fun PlanEditorScreen(
    draft: WorkoutPlanDraft,
    saving: Boolean,
    error: String?,
    onChange: (WorkoutPlanDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var newExercise by rememberSaveable { mutableStateOf(ExerciseTemplate()) }
    LazyColumn(
        modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("PLAN", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text(if (draft.version == 0) "Nowy trening" else "Edytuj trening", style = MaterialTheme.typography.headlineMedium)
                }
                IconButton(onClick = onCancel, enabled = !saving) { Icon(Icons.Default.Close, "Zamknij") }
            }
        }
        item {
            FitTrackCard(Modifier.fillMaxWidth()) {
                Text("Podstawy", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    draft.name,
                    { onChange(draft.copy(name = it)) },
                    label = { Text("Nazwa planu") },
                    placeholder = { Text("np. Góra A") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    draft.dayLabel,
                    { onChange(draft.copy(dayLabel = it)) },
                    label = { Text("Etykieta dnia") },
                    placeholder = { Text("np. Klatka + plecy") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
        item {
            ScheduleFields(draft, onChange)
        }
        items(draft.exercises, key = { it.id }) { exercise ->
            ExerciseCard(
                exercise = exercise,
                onChange = { updated -> onChange(draft.copy(exercises = draft.exercises.map { if (it.id == updated.id) updated else it })) },
                onDelete = { onChange(draft.copy(exercises = draft.exercises.filterNot { it.id == exercise.id })) },
            )
        }
        item {
            FitTrackCard(Modifier.fillMaxWidth()) {
                Text("Dodaj ćwiczenie", style = MaterialTheme.typography.titleLarge)
                ExerciseFields(newExercise) { newExercise = it }
                Button(
                    onClick = {
                        if (newExercise.name.isNotBlank()) {
                            onChange(draft.copy(exercises = draft.exercises + newExercise))
                            newExercise = ExerciseTemplate()
                        }
                    },
                    enabled = newExercise.name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null)
                    Text("  Dodaj do planu")
                }
            }
        }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        item { Button(onClick = onSave, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Zapisywanie…" else "Zapisz plan") } }
        item { TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Anuluj zmiany") } }
    }
}

@Composable
private fun ScheduleFields(draft: WorkoutPlanDraft, onChange: (WorkoutPlanDraft) -> Unit) {
    FitTrackCard(Modifier.fillMaxWidth()) {
        Text("Harmonogram", style = MaterialTheme.typography.titleLarge)
        Text("Kiedy zwykle robisz ten trening?", color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            weekDays.forEach { (day, label) ->
                FilterChip(
                    selected = draft.scheduledDayOfWeek == day,
                    onClick = {
                        onChange(
                            draft.copy(
                                scheduledDayOfWeek = day,
                                scheduledMinuteOfDay = if (day == null) null else draft.scheduledMinuteOfDay ?: 18 * 60,
                            ),
                        )
                    },
                    label = { Text(label) },
                )
            }
        }
        if (draft.scheduledDayOfWeek != null) {
            val minuteOfDay = draft.scheduledMinuteOfDay ?: 18 * 60
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberStepper(
                    label = "Godzina",
                    value = (minuteOfDay / 60).toDouble(),
                    step = 1.0,
                    range = 0.0..23.0,
                    modifier = Modifier.weight(1f),
                ) { hour -> onChange(draft.copy(scheduledMinuteOfDay = hour.roundToInt() * 60 + minuteOfDay % 60)) }
                NumberStepper(
                    label = "Minuta",
                    value = (minuteOfDay % 60).toDouble(),
                    step = 5.0,
                    range = 0.0..59.0,
                    modifier = Modifier.weight(1f),
                ) { minute -> onChange(draft.copy(scheduledMinuteOfDay = minuteOfDay / 60 * 60 + minute.roundToInt())) }
            }
        }
    }
}

@Composable
private fun ExerciseCard(exercise: ExerciseTemplate, onChange: (ExerciseTemplate) -> Unit, onDelete: () -> Unit) {
    FitTrackCard(Modifier.fillMaxWidth()) {
        ExerciseFields(exercise, onChange)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Default.DeleteOutline, null)
            Text("  Usuń ćwiczenie")
        }
    }
}

@Composable
private fun ExerciseFields(exercise: ExerciseTemplate, onChange: (ExerciseTemplate) -> Unit) {
    OutlinedTextField(
        exercise.name,
        { onChange(exercise.copy(name = it)) },
        label = { Text("Ćwiczenie") },
        placeholder = { Text("np. Wyciskanie sztangi") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Text("Serie i powtórzenia", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberStepper("Serie rozgrzewkowe", exercise.warmupSets.toDouble(), 1.0, 0.0..10.0, Modifier.weight(1f)) {
            onChange(exercise.copy(warmupSets = it.roundToInt()))
        }
        NumberStepper("Serie robocze", exercise.workingSets.toDouble(), 1.0, 1.0..10.0, Modifier.weight(1f)) {
            onChange(exercise.copy(workingSets = it.roundToInt()))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberStepper("Powt. od", exercise.repMin.toDouble(), 1.0, 1.0..100.0, Modifier.weight(1f)) {
            onChange(exercise.copy(repMin = it.roundToInt()))
        }
        NumberStepper("Powt. do", exercise.repMax.toDouble(), 1.0, 1.0..100.0, Modifier.weight(1f)) {
            onChange(exercise.copy(repMax = it.roundToInt()))
        }
    }
    Text("Obciążenie i intensywność", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberStepper("Ciężar, kg", exercise.startingWeightKg, 2.5, 0.0..1000.0, Modifier.weight(1f), decimal = true) {
            onChange(exercise.copy(startingWeightKg = it))
        }
        NullableNumberInput("Docelowy RIR", exercise.targetRir, 0..10, Modifier.weight(1f)) {
            onChange(exercise.copy(targetRir = it))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberStepper("Przerwa, sek.", exercise.restSeconds.toDouble(), 15.0, 15.0..600.0, Modifier.weight(1f)) {
            onChange(exercise.copy(restSeconds = it.roundToInt()))
        }
        NumberStepper("Progresja, kg", exercise.weightIncrementKg, 2.5, 2.5..50.0, Modifier.weight(1f), decimal = true) {
            onChange(exercise.copy(weightIncrementKg = it))
        }
    }
    Text(
        "Ciężar i progresja zmieniają się co 2,5 kg — standardowy najmniejszy skok dla sztangi i hantli.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        exercise.note,
        { onChange(exercise.copy(note = it)) },
        label = { Text("Notatka") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
}

@Composable
private fun NumberStepper(
    label: String,
    value: Double,
    step: Double,
    range: ClosedFloatingPointRange<Double>,
    modifier: Modifier,
    decimal: Boolean = false,
    onValueChange: (Double) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(formatNumber(value, decimal)) }
    val focusManager = LocalFocusManager.current

    fun commit(raw: String) {
        val parsed = raw.replace(',', '.').toDoubleOrNull()
        val result = parsed?.coerceIn(range) ?: value
        text = formatNumber(result, decimal)
        onValueChange(result)
    }

    fun adjust(delta: Double) {
        val result = (value + delta).coerceIn(range)
        text = formatNumber(result, decimal)
        onValueChange(result)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.isEmpty() || input.matches(Regex("[0-9]*[,.]?[0-9]*"))) {
                text = input
                input.replace(',', '.').toDoubleOrNull()?.takeIf { it in range }?.let(onValueChange)
            }
        },
        label = { Text(label) },
        leadingIcon = { IconButton(onClick = { adjust(-step) }) { Icon(Icons.Default.Remove, "Zmniejsz $label") } },
        trailingIcon = { IconButton(onClick = { adjust(step) }) { Icon(Icons.Default.Add, "Zwiększ $label") } },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { commit(text); focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged { if (!it.isFocused && text != formatNumber(value, decimal)) commit(text) },
        singleLine = true,
    )
}

@Composable
private fun NullableNumberInput(
    label: String,
    value: Int?,
    range: IntRange,
    modifier: Modifier,
    onValueChange: (Int?) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(value?.toString().orEmpty()) }
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.isEmpty() || input.all(Char::isDigit)) {
                text = input
                onValueChange(input.toIntOrNull()?.takeIf { it in range })
            }
        },
        label = { Text(label) },
        placeholder = { Text("opcjonalnie") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier,
        singleLine = true,
    )
}

private fun formatNumber(value: Double, decimal: Boolean): String =
    if (!decimal || value % 1.0 == 0.0) value.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", value)
