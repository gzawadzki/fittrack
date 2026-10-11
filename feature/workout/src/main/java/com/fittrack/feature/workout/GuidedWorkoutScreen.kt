package com.fittrack.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fittrack.core.model.SetLog
import com.fittrack.core.model.SetType
import com.fittrack.core.designsystem.FitTrackCard
import com.fittrack.core.designsystem.FitTrackColors
import com.fittrack.core.designsystem.asNumeral
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
fun GuidedWorkoutRoute(
    viewModel: WorkoutViewModel,
    availableEquipment: Set<String>,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GuidedWorkoutScreen(
        state = state,
        onStartExercise = viewModel::startNextExercise,
        onLogSet = viewModel::logSet,
        onSkipSet = viewModel::skipSet,
        onFinishExercise = viewModel::finishExercise,
        onSkip = viewModel::skip,
        onLoadSwaps = { viewModel.loadSwaps(availableEquipment) },
        onDismissSwaps = viewModel::dismissSwaps,
        onSwap = viewModel::swap,
        onFinishWorkout = viewModel::finishWorkout,
        modifier = modifier,
    )
}

@Composable
fun GuidedWorkoutScreen(
    state: WorkoutUiState,
    onStartExercise: () -> Unit,
    onLogSet: (String, Int, Double, Int?, Boolean, Boolean, Boolean) -> Unit,
    onSkipSet: (String) -> Unit,
    onFinishExercise: (String) -> Unit,
    onSkip: (String, String) -> Unit,
    onLoadSwaps: () -> Unit,
    onDismissSwaps: () -> Unit,
    onSwap: (String) -> Unit,
    onFinishWorkout: (String) -> Unit,
    clock: UtcClock = SystemUtcClock,
    modifier: Modifier = Modifier,
) {
    val workout = state.workout
    val current = state.currentExercise
    val next = state.nextExercise
    val focus = current ?: next
    if (workout == null) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Brak aktywnego treningu", style = MaterialTheme.typography.headlineSmall)
        }
        return
    }
    var exerciseNote by rememberSaveable(focus?.id) { mutableStateOf(focus?.note.orEmpty()) }
    var workoutNote by rememberSaveable(workout.id) { mutableStateOf(workout.note) }
    var skipDialog by rememberSaveable { mutableStateOf(false) }
    var finishDialog by rememberSaveable { mutableStateOf(false) }
    var noteDialog by rememberSaveable { mutableStateOf(false) }
    var optionsExpanded by rememberSaveable { mutableStateOf(false) }
    val latestSet = state.lastCompletedSetAtUtcMillis

    Column(modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        WorkoutHeader(workout.id, workout.planSnapshot.planName, workout.startedAt.toEpochMilli(), latestSet, state.lastRestSeconds, clock)
        LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (focus == null) {
                item { Text("Wszystkie ćwiczenia są zakończone", style = MaterialTheme.typography.headlineSmall) }
            } else if (current == null) {
                item {
                    FitTrackCard(Modifier.fillMaxWidth()) {
                        Text("NASTĘPNE ĆWICZENIE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(focus.exerciseName, style = MaterialTheme.typography.headlineSmall)
                        state.previousResult?.let { Text("Ostatnio  $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text("Pomiar czasu ćwiczenia i HR rozpocznie się po starcie.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Button(onClick = onStartExercise, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text("  Rozpocznij ćwiczenie")
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onLoadSwaps, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Zamień") }
                        OutlinedButton(onClick = { skipDialog = true }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Pomiń") }
                    }
                }
            } else {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(current.exerciseName, style = MaterialTheme.typography.titleLarge)
                                val completedCount = state.sets.count { it.completedAt != null }
                                Text("$completedCount z ${state.sets.size} serii", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                state.previousResult?.let {
                                    Text(
                                        "Ostatnio: $it",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { optionsExpanded = true }, enabled = !state.busy) { Icon(Icons.Default.MoreHoriz, "Opcje ćwiczenia") }
                                DropdownMenu(expanded = optionsExpanded, onDismissRequest = { optionsExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Dodaj notatkę") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) },
                                        onClick = { optionsExpanded = false; noteDialog = true },
                                    )
                                DropdownMenuItem(
                                    text = { Text("Zamień ćwiczenie") },
                                    onClick = { optionsExpanded = false; onLoadSwaps() },
                                    enabled = !state.busy && state.sets.none { it.completedAt != null },
                                )
                                    DropdownMenuItem(
                                        text = { Text("Pomiń ćwiczenie") },
                                        onClick = { optionsExpanded = false; skipDialog = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Zakończ cały trening") },
                                        onClick = { optionsExpanded = false; finishDialog = true },
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    SetFlowCard(state.sets, state.busy, onSkip = { set -> onSkipSet(set.id) }) { set, reps, weight, rir, pain, technique, symptom ->
                        onLogSet(set.id, reps, weight, rir, pain, technique, symptom)
                    }
                }
            }
            state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (focus == null) {
                item { Button({ finishDialog = true }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("Zakończ trening") } }
            }
        }
        if (current != null) {
            val allSetsDone = state.sets.isNotEmpty() && state.sets.all { it.completedAt != null }
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { noteDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.Notes, null)
                        Text("  Notatka")
                    }
                    if (allSetsDone) {
                        Button(
                            onClick = { onFinishExercise(exerciseNote) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        ) { Text("Zakończ ćwiczenie") }
                    } else {
                        val nextNumber = state.sets.indexOfFirst { it.completedAt == null }.coerceAtLeast(0) + 1
                        Text(
                            "Teraz seria $nextNumber z ${state.sets.size}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (focus != null && state.swapForExerciseId == focus.id) SwapDialog(state.candidates, onDismissSwaps, onSwap)
    if (skipDialog && focus != null) ReasonDialog(
        title = "Pominąć ${focus.exerciseName}?",
        label = "Powód pominięcia",
        dismiss = { skipDialog = false },
        confirm = { reason -> onSkip(reason, exerciseNote); skipDialog = false },
    )
    if (noteDialog && focus != null) AlertDialog(
        onDismissRequest = { noteDialog = false },
        title = { Text("Notatka do ćwiczenia") },
        text = {
            OutlinedTextField(
                exerciseNote,
                { exerciseNote = it },
                placeholder = { Text("Technika, samopoczucie, ustawienie…") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
        },
        confirmButton = { Button({ noteDialog = false }) { Text("Gotowe") } },
        dismissButton = { TextButton({ noteDialog = false }) { Text("Zamknij") } },
    )
    if (finishDialog) AlertDialog(
        onDismissRequest = { finishDialog = false },
        title = { Text("Zakończyć trening?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (focus == null) "Trening zostanie zapisany." else "Bieżące i pozostałe ćwiczenia zostaną oznaczone jako pominięte.")
                OutlinedTextField(
                    workoutNote,
                    { workoutNote = it },
                    label = { Text("Notatka do treningu") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        },
        confirmButton = { Button({ onFinishWorkout(workoutNote); finishDialog = false }) { Text("Zakończ") } },
        dismissButton = { TextButton({ finishDialog = false }) { Text("Anuluj") } },
    )
}

@Composable
private fun WorkoutHeader(workoutId: String, planName: String, startedAt: Long, lastSetAt: Long?, restSeconds: Int, clock: UtcClock) {
    var now by remember(workoutId) { mutableLongStateOf(clock.nowUtcEpochMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, workoutId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) now = clock.nowUtcEpochMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(workoutId) { while (true) { delay(1_000); now = clock.nowUtcEpochMillis() } }
    val elapsed = max(0L, (now - startedAt) / 1_000)
    val remaining = lastSetAt?.let { max(0, restSeconds - ((now - it) / 1_000).toInt()) } ?: 0
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (remaining > 0) FitTrackColors.RestContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (remaining > 0) FitTrackColors.Rest else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(planName, style = MaterialTheme.typography.titleMedium, color = if (remaining > 0) FitTrackColors.OnRestContainer else MaterialTheme.colorScheme.onSurface)
                Text(
                    "Trening  ${elapsed / 3600}:${((elapsed % 3600) / 60).toString().padStart(2, '0')}:${(elapsed % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.labelMedium.asNumeral(),
                    color = if (remaining > 0) FitTrackColors.OnRestContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (remaining > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("PRZERWA", style = MaterialTheme.typography.labelSmall, color = FitTrackColors.Rest)
                    Text("${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.headlineMedium.asNumeral(), color = FitTrackColors.OnRestContainer)
                }
            } else {
                Icon(Icons.Default.Timer, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SetFlowCard(
    sets: List<SetLog>,
    busy: Boolean,
    onSkip: (SetLog) -> Unit,
    onLog: (SetLog, Int, Double, Int?, Boolean, Boolean, Boolean) -> Unit,
) {
    val active = sets.firstOrNull { it.completedAt == null }
    val completed = sets.filter { it.completedAt != null }
    val upcoming = sets.filter { it.completedAt == null }.drop(1)
    // Sets 2+ carry the weight/reps/RIR you just logged for the previous set of
    // the same type forward as the pre-fill, instead of always resetting to the
    // exercise's flat plan target — only the first set of each type (warmup or
    // working) falls back to that plan target (which itself already tracks your
    // last session via progression).
    val carryForward = active?.let { current ->
        sets.filter {
            it.planned.type == current.planned.type && it.position < current.position &&
                it.completedAt != null && it.actualReps != null
        }.maxByOrNull { it.position }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            active?.let { set ->
                ActiveSetEditor(set, carryForward, sets.displayLabel(set), busy, onSkip = { onSkip(set) }) { reps, weight, rir, pain, technique, symptom ->
                    onLog(set, reps, weight, rir, pain, technique, symptom)
                }
            }
            if (completed.isNotEmpty()) {
                if (active != null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "UKOŃCZONE  ${completed.size}/${sets.size}",
                    Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = FitTrackColors.Success,
                )
                completed.forEachIndexed { index, set ->
                    CompletedSetRow(set, sets.displayLabel(set))
                    if (index != completed.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (upcoming.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "DALEJ",
                    Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                upcoming.forEach { set -> PendingSetRow(set, sets.displayLabel(set)) }
            }
        }
    }
}

private fun List<SetLog>.displayLabel(set: SetLog): String {
    val position = filter { it.planned.type == set.planned.type }.indexOfFirst { it.id == set.id } + 1
    return if (set.planned.type == SetType.WARMUP) "W$position" else "$position"
}

@Composable
private fun ActiveSetEditor(
    set: SetLog,
    carryForward: SetLog?,
    displayLabel: String,
    busy: Boolean,
    onSkip: () -> Unit,
    onLog: (Int, Double, Int?, Boolean, Boolean, Boolean) -> Unit,
) {
    var reps by rememberSaveable(set.id, set.completedAt) { mutableStateOf((set.actualReps ?: carryForward?.actualReps ?: set.planned.repMin).toString()) }
    var weight by rememberSaveable(set.id, set.completedAt) { mutableStateOf((set.actualWeightKg ?: carryForward?.actualWeightKg ?: set.planned.targetWeightKg).toString()) }
    var rir by rememberSaveable(set.id, set.completedAt) { mutableStateOf(set.actualRir?.toString() ?: carryForward?.actualRir?.toString() ?: set.planned.targetRir?.toString().orEmpty()) }
    var pain by rememberSaveable(set.id, set.completedAt) { mutableStateOf(set.pain) }
    var technique by rememberSaveable(set.id, set.completedAt) { mutableStateOf(set.techniqueIssue) }
    var symptom by rememberSaveable(set.id, set.completedAt) { mutableStateOf(set.concerningSymptom) }
    val parsedReps = reps.toIntOrNull()
    val parsedWeight = weight.replace(',', '.').toDoubleOrNull()
    val parsedRir = rir.takeIf { it.isNotBlank() }?.toIntOrNull()
    val valid = parsedReps != null && parsedReps in 1..100 && parsedWeight != null && parsedWeight in 0.0..1000.0 &&
        (rir.isBlank() || (parsedRir != null && parsedRir in 0..10))
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SetBadge(displayLabel, active = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (set.planned.type == SetType.WARMUP) "Seria rozgrzewkowa" else "Seria robocza", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Cel ${formatLoad(set.planned.targetWeightKg)} kg × ${set.planned.repMin}–${set.planned.repMax}${set.planned.targetRir?.let { "  ·  RIR $it" }.orEmpty()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // Side by side when there is room, stacked on narrow phones / large font scale.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 2,
        ) {
            val stepperModifier = Modifier.weight(1f).widthIn(min = 160.dp)
            WorkoutStepper("CIĘŻAR KG", weight, 2.5, 0.0..1000.0, true, stepperModifier) { weight = it }
            WorkoutStepper("POWTÓRZENIA", reps, 1.0, 1.0..100.0, false, stepperModifier) { reps = it }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("RIR", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            CompactField("", rir, Modifier.width(72.dp)) { rir = it }
            Spacer(Modifier.width(10.dp))
            Text("opcjonalnie", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(pain, { pain = !pain }, { Text("Ból") })
            FilterChip(technique, { technique = !technique }, { Text("Technika") })
            FilterChip(symptom, { symptom = !symptom }, { Text("Objaw") })
        }
        if (!valid) {
            Text("Sprawdź ciężar, powtórzenia i RIR.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onSkip, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Text("Pomiń serię")
            }
            Button(
                onClick = { onLog(parsedReps!!, parsedWeight!!, parsedRir, pain, technique, symptom) },
                enabled = valid && !busy,
                modifier = Modifier.weight(2f).heightIn(min = 52.dp),
            ) {
                Icon(Icons.Default.Check, null)
                Text("  Zapisz serię")
            }
        }
    }
}

@Composable
private fun CompletedSetRow(set: SetLog, displayLabel: String) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetBadge(displayLabel, active = false)
        Spacer(Modifier.width(12.dp))
        if (set.skipped) {
            Text(
                "Pominięta",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "${formatLoad(set.actualWeightKg ?: 0.0)} kg × ${set.actualReps ?: 0}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = FitTrackColors.Success,
            )
            set.actualRir?.let { Text("RIR $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Check,
            if (set.skipped) "Pominięta" else "Ukończona",
            tint = if (set.skipped) MaterialTheme.colorScheme.onSurfaceVariant else FitTrackColors.Success,
        )
    }
}

@Composable
private fun PendingSetRow(set: SetLog, displayLabel: String) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SetBadge(displayLabel, active = false)
        Spacer(Modifier.width(12.dp))
        Text(
            "${formatLoad(set.planned.targetWeightKg)} kg × ${set.planned.repMin}–${set.planned.repMax}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetBadge(label: String, active: Boolean) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(9.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(label, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WorkoutStepper(
    label: String,
    value: String,
    step: Double,
    range: ClosedFloatingPointRange<Double>,
    decimal: Boolean,
    modifier: Modifier,
    update: (String) -> Unit,
) {
    fun adjust(delta: Double) {
        val current = value.replace(',', '.').toDoubleOrNull() ?: range.start
        val adjusted = (current + delta).coerceIn(range)
        update(formatLoad(adjusted))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                val validInput = if (decimal) input.matches(Regex("[0-9]*[,.]?[0-9]*")) else input.all(Char::isDigit)
                if (input.isEmpty() || validInput) update(input)
            },
            leadingIcon = { IconButton({ adjust(-step) }) { Icon(Icons.Default.Remove, "Zmniejsz $label") } },
            trailingIcon = { IconButton({ adjust(step) }) { Icon(Icons.Default.Add, "Zwiększ $label") } },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold).asNumeral(),
        )
    }
}

private fun formatLoad(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", value)

@Composable
private fun CompactField(
    label: String,
    value: String,
    modifier: Modifier,
    decimal: Boolean = false,
    update: (String) -> Unit,
) =
    OutlinedTextField(
        value,
        { input ->
            val validInput = if (decimal) input.matches(Regex("[0-9]*[,.]?[0-9]*")) else input.all(Char::isDigit)
            if (input.isEmpty() || validInput) update(input)
        },
        label = label.takeIf(String::isNotBlank)?.let { text -> { Text(text) } },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        singleLine = true,
    )

@Composable
private fun SwapDialog(candidates: List<SwapCandidate>, dismiss: () -> Unit, select: (String) -> Unit) = AlertDialog(
    onDismissRequest = dismiss,
    title = { Text("Zamienniki na tę sesję") },
    text = {
        Column {
            if (candidates.isEmpty()) Text("Brak zgodnych zamienników dla dostępnego sprzętu.")
            candidates.take(3).forEach { candidate -> TextButton({ select(candidate.id) }) { Column { Text(candidate.name); Text(candidate.rationale, style = MaterialTheme.typography.bodySmall) } } }
        }
    },
    confirmButton = {},
    dismissButton = { TextButton(dismiss) { Text("Anuluj") } },
)

@Composable
private fun ReasonDialog(title: String, label: String, dismiss: () -> Unit, confirm: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(reason, { reason = it }, label = { Text(label) }) },
        confirmButton = { Button({ confirm(reason) }) { Text("Potwierdź") } },
        dismissButton = { TextButton(dismiss) { Text("Anuluj") } },
    )
}
