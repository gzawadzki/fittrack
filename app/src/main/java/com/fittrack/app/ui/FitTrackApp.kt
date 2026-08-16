package com.fittrack.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fittrack.app.AppCommand
import com.fittrack.app.di.AppContainer
import com.fittrack.core.designsystem.LoadingState
import com.fittrack.core.designsystem.FitTrackCard
import com.fittrack.core.designsystem.MetricPill
import com.fittrack.core.designsystem.SectionTitle
import com.fittrack.core.model.HealthDay
import com.fittrack.core.model.WorkoutPlan
import com.fittrack.feature.coach.CoachRoute
import com.fittrack.feature.coach.CoachViewModel
import com.fittrack.feature.history.HistoryRoute
import com.fittrack.feature.history.HistoryViewModel
import com.fittrack.feature.onboarding.OnboardingRoute
import com.fittrack.feature.onboarding.OnboardingViewModel
import com.fittrack.feature.plans.PlansRoute
import com.fittrack.feature.plans.PlansViewModel
import com.fittrack.feature.settings.SettingsRoute
import com.fittrack.feature.workout.GuidedWorkoutRoute
import com.fittrack.feature.workout.WorkoutViewModel
import com.fittrack.platform.reminders.ACTION_RESCHEDULE
import com.fittrack.platform.reminders.ACTION_START
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class MainDestination(val route: String, val label: String) {
    TODAY("today", "Dziś"),
    PLANS("plans", "Plany"),
    HISTORY("history", "Historia"),
    COACH("coach", "Trener"),
    SETTINGS("settings", "Ustawienia"),
}

@Composable
fun FitTrackApp(container: AppContainer, command: AppCommand?, onCommandConsumed: () -> Unit) {
    val appViewModel: FitTrackViewModel = viewModel(
        factory = viewModelFactory { initializer { FitTrackViewModel(container.profileRepository, container.workoutRepository) } },
    )
    val state by appViewModel.state.collectAsStateWithLifecycle()
    val profile = state.profile
    val activeWorkout = state.activeWorkout
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            appViewModel.clearError()
        }
    }

    LaunchedEffect(command?.nonce, state.loading, profile?.id, activeWorkout?.id) {
        if (state.loading || command == null) return@LaunchedEffect
        when (command.action) {
            ACTION_START -> {
                command.planId?.let(appViewModel::startWorkout)
                onCommandConsumed()
            }
            ACTION_RESCHEDULE -> if (profile == null || activeWorkout != null) {
                onCommandConsumed()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            state.loading -> LoadingState("Ładowanie danych lokalnych…")
            profile == null -> {
                val onboarding: OnboardingViewModel = viewModel(
                    key = "onboarding",
                    factory = viewModelFactory {
                        initializer { OnboardingViewModel(container.profileRepository, createSavedStateHandle()) }
                    },
                )
                OnboardingRoute(onboarding, onCompleted = {})
            }
            activeWorkout != null -> {
                val workout: WorkoutViewModel = viewModel(
                    key = "active-workout",
                    factory = viewModelFactory {
                        initializer { WorkoutViewModel(container.workoutRepository, container.planRepository) }
                    },
                )
                GuidedWorkoutRoute(workout, availableEquipment = profile.equipment)
            }
            else -> MainNavigation(container, appViewModel, state, command, onCommandConsumed)
        }
        SnackbarHost(snackbar, Modifier.padding(16.dp))
    }
}

@Composable
private fun MainNavigation(
    container: AppContainer,
    appViewModel: FitTrackViewModel,
    appState: AppUiState,
    command: AppCommand?,
    onCommandConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route

    LaunchedEffect(command?.nonce) {
        when (command?.action) {
            ACTION_RESCHEDULE -> {
                navController.navigate(MainDestination.PLANS.route) { launchSingleTop = true }
                onCommandConsumed()
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                MainDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { navController.navigate(destination.route) { launchSingleTop = true; restoreState = true } },
                        icon = { Icon(destination.icon(), contentDescription = destination.label) },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.secondary,
                            selectedTextColor = MaterialTheme.colorScheme.secondary,
                            indicatorColor = Color.Transparent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = MainDestination.TODAY.route, modifier = Modifier.padding(padding)) {
            composable(MainDestination.TODAY.route) { TodayScreen(container, appState.insights, appViewModel::startWorkout) }
            composable(MainDestination.PLANS.route) {
                val plans: PlansViewModel = viewModel(
                    factory = viewModelFactory { initializer { PlansViewModel(container.planRepository, createSavedStateHandle()) } },
                )
                PlansRoute(plans, onStartWorkout = appViewModel::startWorkout)
            }
            composable(MainDestination.HISTORY.route) {
                val history: HistoryViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            HistoryViewModel(container.workoutRepository, container.fitbitDataRepository)
                        }
                    },
                )
                HistoryRoute(history)
            }
            composable(MainDestination.COACH.route) {
                val coach: CoachViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            CoachViewModel(
                                container.workoutRepository,
                                container.planRepository,
                                container.coachGateway,
                                container.coachBriefingCache,
                            )
                        }
                    },
                )
                CoachRoute(coach)
            }
            composable(MainDestination.SETTINGS.route) {
                SettingsRoute(
                    authRepository = container.authRepository,
                    healthConnectGateway = container.healthConnectGateway,
                    healthRepository = container.healthRepository,
                    healthConnectSyncGateway = container.healthConnectSyncGateway,
                )
            }
        }
    }
}

@Composable
private fun TodayScreen(container: AppContainer, insights: DashboardInsights, onStartWorkout: (String) -> Unit) {
    val plans by container.planRepository.observePlans().collectAsStateWithLifecycle(initialValue = emptyList())
    val health by container.healthRepository.observeDailyHealth(14).collectAsStateWithLifecycle(initialValue = emptyList())
    val today = health.firstOrNull()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("DZISIAJ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Text("Gotowy na trening?", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("pl"))),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Default.FitnessCenter, null, Modifier.padding(14.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { HealthCard(today) }
        item { TrainingHeatmap(insights) }
        if (insights.personalRecords.isNotEmpty()) {
            item { PersonalRecords(insights.personalRecords) }
        }
        item { SectionTitle("Treningi", "${plans.size} planów") }
        items(plans.take(3), key = WorkoutPlan::id) { plan ->
            FitTrackCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(plan.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${plan.dayLabel.ifBlank { "Dowolny dzień" }}  •  ${plan.exercises.size} ćwiczeń",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onStartWorkout(plan.id) }, enabled = plan.exercises.isNotEmpty()) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary) {
                            Icon(Icons.Default.PlayArrow, "Rozpocznij", Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onSecondary)
                        }
                    }
                }
            }
        }
        if (plans.isEmpty()) item {
            FitTrackCard(Modifier.fillMaxWidth()) {
                Text("Brak planu", style = MaterialTheme.typography.titleMedium)
                Text("Dodaj pierwszy plan w zakładce Plany.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TrainingHeatmap(insights: DashboardInsights) {
    val today = LocalDate.now()
    val start = today.minusWeeks(17).with(java.time.DayOfWeek.MONDAY)
    FitTrackCard(Modifier.fillMaxWidth()) {
        SectionTitle("Regularność", "${insights.completedWorkoutCount} treningów")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(18) { week ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(7) { day ->
                        val date = start.plusDays((week * 7L) + day)
                        val count = if (date > today) 0 else insights.workoutCountsByDay[date] ?: 0
                        val color = when {
                            date > today || count == 0 -> MaterialTheme.colorScheme.surfaceVariant
                            count == 1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            count == 2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Box(Modifier.size(11.dp).background(color, RoundedCornerShape(3.dp)))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                start.format(DateTimeFormatter.ofPattern("d MMM", Locale("pl"))),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "ostatnie 18 tygodni",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PersonalRecords(records: List<PersonalRecordUi>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Rekordy osobiste", "e1RM")
        FitTrackCard(Modifier.fillMaxWidth()) {
            records.forEachIndexed { index, record ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Icon(Icons.Default.EmojiEvents, null, Modifier.padding(9.dp).size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(record.exerciseName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${formatDashboardWeight(record.weightKg)} kg × ${record.reps}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${formatDashboardWeight(record.estimatedOneRepMaxKg)} kg", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("szac. 1RM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (index != records.lastIndex) {
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private fun formatDashboardWeight(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale.US, value)

@Composable
private fun HealthCard(day: HealthDay?) {
    FitTrackCard(Modifier.fillMaxWidth()) {
        SectionTitle("Regeneracja", "FITBIT")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricPill("Sen", day?.sleepMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "—", Modifier.weight(1f))
            MetricPill("RHR", day?.restingHeartRate?.let { "$it" } ?: "—", Modifier.weight(1f))
            MetricPill("Kroki", day?.steps?.let { if (it >= 1000) "${it / 1000}.${(it % 1000) / 100}k" else "$it" } ?: "—", Modifier.weight(1f))
        }
        Text("Brak danych nie obniża gotowości.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun MainDestination.icon() = when (this) {
    MainDestination.TODAY -> Icons.Default.Home
    MainDestination.PLANS -> Icons.Default.FitnessCenter
    MainDestination.HISTORY -> Icons.Default.History
    MainDestination.COACH -> Icons.Default.Psychology
    MainDestination.SETTINGS -> Icons.Default.Settings
}
