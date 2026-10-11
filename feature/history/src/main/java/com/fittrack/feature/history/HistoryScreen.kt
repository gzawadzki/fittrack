package com.fittrack.feature.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.fittrack.core.designsystem.EmptyState
import com.fittrack.core.designsystem.ErrorState
import com.fittrack.core.designsystem.LoadingState
import com.fittrack.core.designsystem.FitTrackCard
import com.fittrack.core.designsystem.FitTrackColors
import com.fittrack.core.designsystem.MetricPill
import com.fittrack.core.model.FitbitSyncStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

@Composable
fun HistoryRoute(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        onRetry = viewModel::refresh,
        onRequestDelete = viewModel::requestDeleteWorkout,
        onConfirmDelete = viewModel::confirmDeleteWorkout,
        onCancelDelete = viewModel::cancelDeleteWorkout,
        modifier = modifier,
    )
}

@Composable
fun HistoryScreen(
    state: HistoryState,
    onRetry: () -> Unit,
    onRequestDelete: (String) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> LoadingState("Ładowanie historii…", modifier)
        state.errorMessage != null -> ErrorState(state.errorMessage, onRetry, modifier)
        else -> HistoryContent(state, onRetry, onRequestDelete, onConfirmDelete, onCancelDelete, modifier)
    }
}

@Composable
private fun HistoryContent(
    state: HistoryState,
    onRefresh: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    if (state.pendingDeleteWorkoutId != null) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("Usunąć trening?") },
            text = {
                Text(
                    if (state.deleteErrorMessage != null) {
                        state.deleteErrorMessage
                    } else {
                        "Ta operacja usunie trening z historii. Nie można jej cofnąć."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.isDeleting) {
                    Text(if (state.isDeleting) "Usuwanie…" else "Usuń")
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete, enabled = !state.isDeleting) { Text("Anuluj") }
            },
        )
    }
    Column(modifier) {
        Column(Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 14.dp)) {
            Text("HISTORIA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text("Twoja praca", style = MaterialTheme.typography.headlineMedium)
        }
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(Modifier.padding(4.dp)) {
                listOf("Siła", "Cardio").forEachIndexed { index, label ->
                    Surface(
                        onClick = { selectedTab = index },
                        modifier = Modifier.weight(1f),
                        color = if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (selectedTab == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(11.dp),
                    ) { Text(label, Modifier.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        if (selectedTab == 0) {
            StrengthHistory(
                entries = state.strength,
                summary = state.summary,
                heartRates = state.heartRateByWorkout,
                heartRateUnavailableMessage = state.heartRateUnavailableMessage,
                isRefreshing = state.isLoading,
                onRefresh = onRefresh,
                onRequestDelete = onRequestDelete,
            )
        } else {
            CardioHistory(state.cardio, state.cardioUnavailableMessage, isRefreshing = state.isLoading, onRefresh = onRefresh)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StrengthHistory(
    entries: List<StrengthHistoryItem>,
    summary: HistorySummary,
    heartRates: Map<String, WorkoutHeartRate>,
    heartRateUnavailableMessage: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyState("Brak historii treningów", "Ukończony trening pojawi się tutaj.")
        return
    }
    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh) {
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { HistorySummaryPanel(summary) }
            items(entries, key = { it.workoutId }) { item ->
                var expanded by remember(item.workoutId) { mutableStateOf(false) }
                FitTrackCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClickLabel = if (expanded) "Zwiń" else "Rozwiń",
                            onLongClickLabel = "Usuń trening",
                            onClick = { expanded = !expanded },
                            onLongClick = { onRequestDelete(item.workoutId) },
                        )
                        .semantics { stateDescription = if (expanded) "Rozwinięte" else "Zwinięte" },
                ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.planName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(fitbitLabel(item.fitbitState), style = MaterialTheme.typography.labelSmall, color = fitbitColor(item.fitbitState))
                        }
                        Text("${formatDate(item.startedAt)}  •  ${formatDuration(item.durationSeconds)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${item.exerciseCount} ćw.", style = MaterialTheme.typography.labelMedium)
                            Text("${item.completedSetCount} serii", style = MaterialTheme.typography.labelMedium)
                            Text("${formatVolume(item.totalVolumeKg)} kg", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        if (item.note.isNotBlank()) Text(item.note)
                        if (expanded) {
                            item.exercises.forEach { exercise -> ExerciseSummary(exercise) }
                            heartRates[item.workoutId]?.let { HeartRateChart(it) }
                                ?: heartRateUnavailableMessage?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            TextButton(onClick = { onRequestDelete(item.workoutId) }) {
                                Text("Usuń trening", color = MaterialTheme.colorScheme.error)
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun HistorySummaryPanel(summary: HistorySummary) {
    FitTrackCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricPill("Treningi", summary.workoutCount.toString(), Modifier.weight(1f))
            MetricPill("Serie", summary.completedSetCount.toString(), Modifier.weight(1f))
            MetricPill("Objętość", formatCompactVolume(summary.totalVolumeKg), Modifier.weight(1f))
        }
        Text(
            "Łączny czas  ${summary.totalMinutes / 60}h ${summary.totalMinutes % 60}m",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExerciseSummary(item: ExerciseHistoryItem) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(item.name, fontWeight = FontWeight.SemiBold)
        Text("${item.completedSets} ser. · ${item.summary}", style = MaterialTheme.typography.bodySmall)
        if (item.completedSets > 0) {
            Text(
                listOfNotNull(item.bestSet?.let { "najlepsza $it" }, "objętość ${formatVolume(item.volumeKg)} kg").joinToString("  •  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item.heartRate?.let { hr ->
            Text("HR śr. ${hr.averageBpm}, max ${hr.maximumBpm}" + (hr.recoveryDeltaBpm?.let { ", spadek $it bpm" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
        if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HeartRateChart(rate: WorkoutHeartRate) {
    if (rate.samples.isEmpty()) {
        Text("Tętno: brak próbek po synchronizacji Fitbit — neutralny stan.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(Modifier.padding(top = 10.dp)) {
        Text("Tętno podczas treningu (${rate.sourceLabel})", style = MaterialTheme.typography.titleSmall)
        Canvas(
            Modifier.fillMaxWidth().height(112.dp).semantics {
                contentDescription = "Wykres tętna: od ${rate.samples.minOf { it.bpm }} do ${rate.samples.maxOf { it.bpm }} uderzeń na minutę"
            },
        ) {
            val points = rate.samples.sortedBy(HeartRatePoint::recordedAt)
            val low = points.minOf { it.bpm }.toFloat()
            val high = max(low + 1f, points.maxOf { it.bpm }.toFloat())
            val firstTimestamp = points.first().recordedAt.toEpochMilli()
            val timestampSpan = max(1L, points.last().recordedAt.toEpochMilli() - firstTimestamp)
            val path = Path()
            points.forEachIndexed { index, point ->
                val elapsed = point.recordedAt.toEpochMilli() - firstTimestamp
                val x = if (points.size == 1) {
                    0f
                } else {
                    size.width * elapsed.toDouble().div(timestampSpan).toFloat()
                }
                val y = size.height - ((point.bpm - low) / (high - low) * size.height)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, FitTrackColors.HeartRate, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
        }
        Text("${rate.samples.minOf { it.bpm }}–${rate.samples.maxOf { it.bpm }} bpm · dane opisowe, nie diagnoza", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CardioHistory(entries: List<CardioHistoryItem>, unavailableMessage: String?, isRefreshing: Boolean, onRefresh: () -> Unit) {
    if (entries.isEmpty()) {
        EmptyState(
            "Brak aktywności cardio",
            unavailableMessage ?: "Spacery i biegi z Fitbit pojawią się po synchronizacji.",
        )
        return
    }
    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh) {
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.id }) { item ->
                FitTrackCard(Modifier.fillMaxWidth()) {
                        Text(item.kind, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(formatDate(item.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${item.durationSeconds / 60} min" + item.distanceMeters?.let { "  •  ${"%.2f".format(it / 1000)} km" }.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                        Text(listOfNotNull(item.steps?.let { "$it kroków" }, item.averageHeartRate?.let { "śr. $it bpm" }).joinToString("  •  "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun fitbitLabel(state: FitbitSyncStatus): String = when (state) {
    FitbitSyncStatus.SYNCED -> "Fitbit zsynchronizowany"
    FitbitSyncStatus.PENDING -> "Oczekuje na Fitbit"
    FitbitSyncStatus.FAILED -> "Fitbit: import niedostępny"
    FitbitSyncStatus.NOT_REQUESTED -> "Fitbit: nie zażądano importu"
}

@Composable
private fun fitbitColor(state: FitbitSyncStatus): Color = when (state) {
    FitbitSyncStatus.SYNCED -> FitTrackColors.Success
    FitbitSyncStatus.PENDING -> FitTrackColors.Warning
    FitbitSyncStatus.FAILED -> MaterialTheme.colorScheme.error
    FitbitSyncStatus.NOT_REQUESTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatDate(instant: Instant): String = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault()).format(instant)

private fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "<1 min"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private fun formatVolume(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

private fun formatCompactVolume(value: Double): String = when {
    value >= 1000 -> "%.1fk".format(value / 1000)
    else -> formatVolume(value)
}
