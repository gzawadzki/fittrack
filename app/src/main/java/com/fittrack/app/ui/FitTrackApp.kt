package com.fittrack.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fittrack.app.data.FitTrackRepository
import com.fittrack.app.data.local.*
import com.fittrack.app.domain.ReadinessLevel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

private enum class Tab(val label: String) { TODAY("Dziś"), PLANS("Plany"), HISTORY("Historia"), COACH("Trener") }

@Composable
fun FitTrackApp(viewModel: FitTrackViewModel) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val activeWorkout by viewModel.activeWorkout.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.text)
            viewModel.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            profile == null -> OnboardingScreen(viewModel)
            activeWorkout != null -> LiveWorkoutScreen(viewModel, activeWorkout!!, profile!!)
            else -> MainTabs(viewModel, profile!!)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun OnboardingScreen(viewModel: FitTrackViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf(
        "Jaki jest Twój najważniejszy cel?",
        "Jak oceniasz swoje doświadczenie?",
        "Jaki sprzęt masz zwykle dostępny?",
        "W jakie dni i o jakiej porze chcesz trenować?",
        "Czy masz urazy, ból lub inne ograniczenia?",
        "Jakie masz preferencje treningowe?",
    )
    val answers = remember { mutableStateListOf("", "", "", "", "", "") }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Pierwsza rozmowa", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { (step + 1f) / 7f }, Modifier.fillMaxWidth())
                if (step < labels.size) {
                    Text(labels[step], style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = answers[step],
                        onValueChange = { answers[step] = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text("Twoja odpowiedź") },
                    )
                } else {
                    Text("Sprawdź kartę profilu", style = MaterialTheme.typography.titleLarge)
                    ProfileReview(labels.zip(answers))
                    Text("Zapis nastąpi dopiero po zatwierdzeniu.", color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("Wstecz") }
                Button(
                    onClick = {
                        if (step < labels.size) step++ else viewModel.saveProfile(
                            answers[0], answers[1], answers[2], answers[3], answers[4], answers[5],
                        )
                    },
                    enabled = step >= labels.size || answers[step].isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (step < labels.size) "Dalej" else "Zatwierdź") }
            }
        }
    }
}

@Composable
private fun ProfileReview(items: List<Pair<String, String>>) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { (label, answer) ->
                Column {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(answer.ifBlank { "Nie podano" })
                }
            }
        }
    }
}

@Composable
private fun MainTabs(viewModel: FitTrackViewModel, profile: UserProfileEntity) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    val icon = when (item) {
                        Tab.TODAY -> Icons.Default.Home
                        Tab.PLANS -> Icons.Default.FitnessCenter
                        Tab.HISTORY -> Icons.Default.History
                        Tab.COACH -> Icons.Default.Psychology
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.TODAY -> TodayScreen(viewModel)
                Tab.PLANS -> PlansScreen(viewModel)
                Tab.HISTORY -> HistoryScreen(viewModel)
                Tab.COACH -> CoachScreen(viewModel, profile)
            }
        }
    }
}

@Composable
private fun TodayScreen(viewModel: FitTrackViewModel) {
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val today = health.maxByOrNull { it.date }

    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("Dzisiaj", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = readinessColor(readiness))) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gotowość: ${readinessLabel(readiness)}", style = MaterialTheme.typography.titleLarge)
                    Text("Sen: ${today?.sleepMinutes?.let { "${it / 60} h ${it % 60} min" } ?: "brak danych"}")
                    Text("RHR: ${today?.restingHeartRate?.let { "$it bpm" } ?: "brak danych"}  •  Kroki: ${today?.steps ?: "—"}")
                    Text("Brak danych jest neutralny — trener nie zgaduje.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Zaplanowany trening", style = MaterialTheme.typography.titleLarge) }
        items(plans.take(2), key = { it.id }) { plan ->
            PlanCard(plan, onStart = { viewModel.startWorkout(plan.id) })
        }
        if (plans.isEmpty()) item { Text("Plan startowy jest przygotowywany…") }
    }
}

@Composable
private fun PlansScreen(viewModel: FitTrackViewModel) {
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    var showBuilder by remember { mutableStateOf(false) }
    Scaffold(
        floatingActionButton = { FloatingActionButton(onClick = { showBuilder = true }) { Icon(Icons.Default.Add, "Dodaj plan") } },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Plany", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
            items(plans, key = { it.id }) { plan -> PlanCard(plan, onStart = { viewModel.startWorkout(plan.id) }) }
        }
    }
    if (showBuilder) PlanBuilderDialog(
        onDismiss = { showBuilder = false },
        onSave = { name, exercises -> viewModel.createPlan(name, exercises); showBuilder = false },
    )
}

@Composable
private fun PlanCard(plan: WorkoutPlanEntity, onStart: () -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(plan.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(plan.dayLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Wersja ${plan.version}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onStart) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Start") }
        }
    }
}

@Composable
private fun PlanBuilderDialog(onDismiss: () -> Unit, onSave: (String, List<FitTrackRepository.NewPlanExercise>) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var exerciseName by rememberSaveable { mutableStateOf("") }
    var sets by rememberSaveable { mutableStateOf("3") }
    var repMin by rememberSaveable { mutableStateOf("8") }
    var repMax by rememberSaveable { mutableStateOf("12") }
    var weight by rememberSaveable { mutableStateOf("0") }
    val exercises = remember { mutableStateListOf<FitTrackRepository.NewPlanExercise>() }

    fun addExercise() {
        if (exerciseName.isBlank()) return
        exercises += FitTrackRepository.NewPlanExercise(
            name = exerciseName,
            workingSets = sets.toIntOrNull() ?: 3,
            repMin = repMin.toIntOrNull() ?: 8,
            repMax = repMax.toIntOrNull() ?: 12,
            targetRir = 2,
            startingWeightKg = weight.replace(',', '.').toDoubleOrNull() ?: 0.0,
            restSeconds = 120,
            weightIncrementKg = 2.5,
        )
        exerciseName = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nowy plan") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nazwa planu") }, singleLine = true)
                HorizontalDivider()
                Text("Ćwiczenie ${exercises.size + 1}", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(exerciseName, { exerciseName = it }, label = { Text("Nazwa ćwiczenia") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallNumberField(sets, { sets = it }, "Serie", Modifier.weight(1f))
                    SmallNumberField(repMin, { repMin = it }, "Min", Modifier.weight(1f))
                    SmallNumberField(repMax, { repMax = it }, "Max", Modifier.weight(1f))
                    SmallNumberField(weight, { weight = it }, "kg", Modifier.weight(1f), decimal = true)
                }
                OutlinedButton(onClick = ::addExercise, enabled = exerciseName.isNotBlank()) { Text("Dodaj ćwiczenie") }
                exercises.forEachIndexed { index, item -> Text("${index + 1}. ${item.name} — ${item.workingSets} × ${item.repMin}–${item.repMax}") }
            }
        },
        confirmButton = {
            Button(onClick = { if (exerciseName.isNotBlank()) addExercise(); onSave(name, exercises.toList()) }, enabled = name.isNotBlank() && (exercises.isNotEmpty() || exerciseName.isNotBlank())) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun HistoryScreen(viewModel: FitTrackViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Historia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        items(history, key = { it.id }) { workout ->
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(workout.planNameSnapshot, style = MaterialTheme.typography.titleMedium)
                    Text(formatDate(workout.startedAt))
                    Text("${workout.status} • Fitbit: ${workout.fitbitSyncStatus}", style = MaterialTheme.typography.bodySmall)
                    if (workout.note.isNotBlank()) Text(workout.note)
                }
            }
        }
        if (history.isEmpty()) item { Text("Pierwszy ukończony trening pojawi się tutaj.") }
    }
}

@Composable
private fun CoachScreen(viewModel: FitTrackViewModel, profile: UserProfileEntity) {
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Trener", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Kierunek", fontWeight = FontWeight.Bold)
                    Text(profile.goal)
                    Text("Porady są edukacyjne i nie zastępują diagnozy medycznej.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Zmiany do zatwierdzenia", style = MaterialTheme.typography.titleLarge) }
        items(suggestions, key = { it.id }) { suggestion -> SuggestionCard(suggestion, viewModel) }
        if (suggestions.isEmpty()) item { Text("Po treningu pojawią się tutaj uzasadnione propozycje progresji.") }
    }
}

@Composable
private fun SuggestionCard(item: CoachSuggestionEntity, viewModel: FitTrackViewModel) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (item.type == "LOAD") "Zmiana obciążenia" else "Zmiana objętości", style = MaterialTheme.typography.titleMedium)
            Text("${item.beforeValue} → ${item.afterValue}", style = MaterialTheme.typography.headlineSmall)
            Text(item.explanation)
            Text("Pewność: ${item.confidence}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.decideSuggestion(item.id, true) }) { Text("Akceptuj") }
                OutlinedButton(onClick = { viewModel.decideSuggestion(item.id, false) }) { Text("Odrzuć") }
            }
        }
    }
}

@Composable
private fun LiveWorkoutScreen(viewModel: FitTrackViewModel, workout: WorkoutSessionEntity, profile: UserProfileEntity) {
    val exercises by viewModel.exerciseSessions.collectAsStateWithLifecycle()
    val current by viewModel.currentExercise.collectAsStateWithLifecycle()
    val sets by viewModel.currentSets.collectAsStateWithLifecycle()
    val candidates by viewModel.swapCandidates.collectAsStateWithLifecycle()
    var exerciseNote by rememberSaveable(current?.id) { mutableStateOf("") }
    var workoutNote by rememberSaveable { mutableStateOf("") }
    var showFinish by remember { mutableStateOf(false) }
    var showSkip by remember { mutableStateOf(false) }

    val latestCompleted = sets.mapNotNull { it.completedAt }.maxOrNull()
    RestTimer(latestCompleted, current?.restSecondsSnapshot ?: 120)

    Scaffold(
        topBar = {
            Surface(shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(workout.planNameSnapshot, fontWeight = FontWeight.Bold)
                        Text("${exercises.count { it.status == "COMPLETED" || it.status == "SKIPPED" }}/${exercises.size} ćwiczeń", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(formatElapsed(workout.startedAt))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (current != null) {
                item {
                    Text(current!!.exerciseNameSnapshot, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Rozpoczęto ${formatTime(current!!.startedAt)}")
                }
                items(sets, key = { it.id }) { set -> SetLogger(set, viewModel) }
                item {
                    OutlinedTextField(
                        exerciseNote,
                        { exerciseNote = it },
                        label = { Text("Komentarz po ćwiczeniu") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.loadSwapCandidates(current!!.id, profile.equipment) }, modifier = Modifier.weight(1f)) { Text("Zamień") }
                        OutlinedButton(onClick = { showSkip = true }, modifier = Modifier.weight(1f)) { Text("Pomiń") }
                    }
                }
                item {
                    Button(
                        onClick = { viewModel.finishExercise(current!!.id, exerciseNote) },
                        enabled = sets.any { it.completedAt != null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Zakończ ćwiczenie") }
                }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("Wszystkie ćwiczenia zakończone", style = MaterialTheme.typography.titleLarge)
                            Text("Dodaj komentarz i zapisz trening.")
                        }
                    }
                }
            }
            item {
                OutlinedTextField(workoutNote, { workoutNote = it }, label = { Text("Komentarz do treningu") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                TextButton(onClick = { showFinish = true }, modifier = Modifier.fillMaxWidth()) { Text("Zakończ cały trening") }
            }
        }
    }

    if (candidates.isNotEmpty() && current != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Zamienniki na tę sesję") },
            text = { Column { candidates.forEach { candidate -> TextButton(onClick = { viewModel.swapExercise(current!!.id, candidate.id) }) { Text(candidate.name) } } } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { viewModel.swapExercise(current!!.id, current!!.exerciseId) }) { Text("Anuluj") } },
        )
    }
    if (showSkip && current != null) {
        ConfirmDialog("Pominąć ćwiczenie?", "Powód można dopisać w komentarzu ćwiczenia.", onDismiss = { showSkip = false }) {
            viewModel.skipExercise(current!!.id, exerciseNote)
            showSkip = false
        }
    }
    if (showFinish) {
        ConfirmDialog("Zakończyć trening?", "Niedokończone ćwiczenia zostaną oznaczone jako pominięte.", onDismiss = { showFinish = false }) {
            viewModel.finishWorkout(workout.id, workoutNote)
            showFinish = false
        }
    }
}

@Composable
private fun SetLogger(set: SetLogEntity, viewModel: FitTrackViewModel) {
    var reps by rememberSaveable(set.id, set.completedAt) { mutableStateOf((set.actualReps ?: set.targetRepMin).toString()) }
    var weight by rememberSaveable(set.id, set.completedAt) { mutableStateOf((set.actualWeightKg ?: set.targetWeightKg).toString()) }
    var rir by rememberSaveable(set.id, set.completedAt) { mutableStateOf(set.actualRir?.toString() ?: "") }
    var pain by rememberSaveable(set.id, set.completedAt) { mutableStateOf(set.pain) }
    var technique by rememberSaveable(set.id, set.completedAt) { mutableStateOf(set.techniqueIssue) }
    val complete = set.completedAt != null

    Card(colors = CardDefaults.cardColors(containerColor = if (complete) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (set.setType == "WARMUP") "R" else "S"}${set.position + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                Text("Cel ${set.targetWeightKg.clean()} kg × ${set.targetRepMin}–${set.targetRepMax}, RIR ${set.targetRir ?: "—"}", modifier = Modifier.weight(1f))
                if (complete) Icon(Icons.Default.CheckCircle, "Ukończona", tint = MaterialTheme.colorScheme.primary)
            }
            if (!complete) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallNumberField(weight, { weight = it }, "kg", Modifier.weight(1f), decimal = true)
                    SmallNumberField(reps, { reps = it }, "Powt.", Modifier.weight(1f))
                    SmallNumberField(rir, { rir = it }, "RIR", Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(pain, { pain = !pain }, { Text("Ból") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(technique, { technique = !technique }, { Text("Technika") })
                    Spacer(Modifier.weight(1f))
                    FilledIconButton(onClick = {
                        viewModel.completeSet(
                            set,
                            reps.toIntOrNull() ?: 0,
                            weight.replace(',', '.').toDoubleOrNull() ?: 0.0,
                            rir.toIntOrNull(),
                            pain,
                            technique,
                        )
                    }) { Icon(Icons.Default.Check, "Zapisz serię") }
                }
            } else {
                Text("${set.actualWeightKg?.clean()} kg × ${set.actualReps}, RIR ${set.actualRir ?: "—"}")
            }
        }
    }
}

@Composable
private fun RestTimer(lastCompletedAt: Long?, restSeconds: Int) {
    if (lastCompletedAt == null) return
    var remaining by remember(lastCompletedAt) { mutableIntStateOf(max(0, restSeconds - ((System.currentTimeMillis() - lastCompletedAt) / 1000).toInt())) }
    LaunchedEffect(lastCompletedAt) {
        while (remaining > 0) { delay(1_000); remaining-- }
    }
    if (remaining > 0) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 76.dp), contentAlignment = Alignment.TopCenter) {
            AssistChip(onClick = { remaining = 0 }, label = { Text("Przerwa ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')} • pomiń") }, leadingIcon = { Icon(Icons.Default.Timer, null) })
        }
    }
}

@Composable
private fun SmallNumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { Button(onClick = onConfirm) { Text("Potwierdź") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable private fun readinessColor(level: ReadinessLevel) = when (level) {
    ReadinessLevel.GREEN -> MaterialTheme.colorScheme.primaryContainer
    ReadinessLevel.YELLOW -> MaterialTheme.colorScheme.tertiaryContainer
    ReadinessLevel.RED -> MaterialTheme.colorScheme.errorContainer
    ReadinessLevel.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
}

private fun readinessLabel(level: ReadinessLevel) = when (level) {
    ReadinessLevel.GREEN -> "zielona"
    ReadinessLevel.YELLOW -> "ostrożnie"
    ReadinessLevel.RED -> "regeneracja"
    ReadinessLevel.UNKNOWN -> "za mało danych"
}

private fun formatDate(epoch: Long): String = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epoch))
private fun formatTime(epoch: Long?): String = epoch?.let { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) } ?: "—"
@Composable private fun formatElapsed(start: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val seconds = ((now - start) / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}
private fun Double.clean(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()
