package com.fittrack.app.di

import android.app.Application
import com.fittrack.app.BuildConfig
import com.fittrack.core.database.FitTrackDatabase
import com.fittrack.core.database.repository.RoomHealthRepository
import com.fittrack.core.database.repository.RoomFitbitDataRepository
import com.fittrack.core.database.repository.RoomPlanRepository
import com.fittrack.core.database.repository.RoomProfileRepository
import com.fittrack.core.database.repository.RoomWorkoutRepository
import com.fittrack.core.database.sync.RoomSyncOutboxStore
import com.fittrack.core.model.CoachBriefing
import com.fittrack.core.model.CoachReply
import com.fittrack.core.model.repository.CoachGateway
import com.fittrack.core.model.repository.HealthRepository
import com.fittrack.core.model.repository.FitbitDataRepository
import com.fittrack.core.model.repository.PlanRepository
import com.fittrack.core.model.repository.ProfileRepository
import com.fittrack.core.model.repository.WorkoutRepository
import com.fittrack.core.network.AuthRepository
import com.fittrack.core.network.CoachGatewayException
import com.fittrack.core.network.EncryptedSessionStore
import com.fittrack.core.network.FitbitGateway
import com.fittrack.core.network.FitbitGatewayError
import com.fittrack.core.network.FitbitGatewayState
import com.fittrack.core.network.FitbitOAuthStartResult
import com.fittrack.core.network.FitbitSyncRequest
import com.fittrack.core.network.FitbitSyncResult
import com.fittrack.core.network.SupabaseAuthRepository
import com.fittrack.core.network.SupabaseCoachGateway
import com.fittrack.core.network.SupabaseFitbitGateway
import com.fittrack.core.network.SupabaseSyncClient
import com.fittrack.core.network.SyncOrchestrator
import com.fittrack.core.network.NetworkWorkDependencies
import com.fittrack.core.network.NetworkWorkScheduler
import com.fittrack.core.training.CoachEngine
import com.fittrack.platform.reminders.ReminderDependencies
import com.fittrack.platform.reminders.ReminderScheduler
import com.fittrack.platform.reminders.WorkoutReminderPlan
import com.fittrack.platform.reminders.WorkoutStartStateProvider
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppContainer(private val application: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = FitTrackDatabase.create(application)

    val profileRepository: ProfileRepository = RoomProfileRepository(database)
    val planRepository: PlanRepository = RoomPlanRepository(database)
    val healthRepository: HealthRepository = RoomHealthRepository(database.dao())
    val fitbitDataRepository: FitbitDataRepository = RoomFitbitDataRepository(database)
    private val roomWorkoutRepository = RoomWorkoutRepository(database)
    private val coachEngine = CoachEngine(planRepository, roomWorkoutRepository, healthRepository)

    private val syncOrchestrator: SyncOrchestrator? by lazy {
        BuildConfig.SUPABASE_URL
            .takeIf { it.isValidHttpsBaseUrl() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank() }
            ?.let { baseUrl ->
                SyncOrchestrator(
                    outbox = RoomSyncOutboxStore(database),
                    client = SupabaseSyncClient("${baseUrl.trimEnd('/')}/functions/v1", authRepository),
                )
            }
    }

    val workoutRepository: WorkoutRepository = IntegratedWorkoutRepository(
        delegate = roomWorkoutRepository,
        coachEngine = coachEngine,
        afterWorkout = { workoutId ->
            if (syncOrchestrator != null) PostWorkoutSyncScheduler.enqueue(application, workoutId)
        },
    )

    private val sessionStore = EncryptedSessionStore(application)
    val authRepository: AuthRepository = SupabaseAuthRepository(
        projectUrl = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
        sessionStore = sessionStore,
    )

    val coachGateway: CoachGateway = BuildConfig.SUPABASE_URL
        .takeIf { it.isValidHttpsBaseUrl() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank() }
        ?.let { SupabaseCoachGateway("${it.trimEnd('/')}/functions/v1", authRepository) }
        ?: UnavailableCoachGateway

    private val remoteFitbitGateway: FitbitGateway = BuildConfig.SUPABASE_URL
        .takeIf { it.isValidHttpsBaseUrl() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank() }
        ?.let { SupabaseFitbitGateway("${it.trimEnd('/')}/functions/v1", authRepository) }
        ?: UnavailableFitbitGateway
    val fitbitGateway: FitbitGateway = PersistingFitbitGateway(remoteFitbitGateway, fitbitDataRepository)

    fun start() {
        syncOrchestrator?.let { orchestrator ->
            NetworkWorkDependencies.syncOrchestrator = orchestrator
            NetworkWorkScheduler.schedule(application)
        }
        ReminderScheduler.createChannel(application)
        ReminderDependencies.workoutStartStateProvider = object : WorkoutStartStateProvider {
            override suspend fun hasWorkoutStarted(planId: String, scheduledAt: Instant): Boolean {
                // The primary notification is delivered 60 minutes before the
                // scheduled occurrence, so starting from that action must count.
                val earliest = scheduledAt.minus(Duration.ofMinutes(65))
                val active = roomWorkoutRepository.getActiveWorkout()
                if (active?.planSnapshot?.planId == planId && !active.startedAt.isBefore(earliest)) return true
                return roomWorkoutRepository.observeHistory(limit = 200).first().any {
                    it.planSnapshot.planId == planId && !it.startedAt.isBefore(earliest)
                }
            }
        }
        appScope.launch { seedLocalData() }
        appScope.launch {
            planRepository.observePlans().collect { plans ->
                ReminderScheduler.updatePlans(
                    application,
                    plans.mapNotNull { plan ->
                        val day = plan.scheduledDayOfWeek ?: return@mapNotNull null
                        val minute = plan.scheduledMinuteOfDay ?: return@mapNotNull null
                        WorkoutReminderPlan(plan.id, plan.name, java.time.DayOfWeek.of(day), minute)
                    },
                )
            }
        }
    }

    private suspend fun seedLocalData() {
        val library = application.assets.open("exercise_library.json").bufferedReader().use { it.readText() }
        planRepository.importExerciseLibraryFromJson(library)
        if (planRepository.observePlans().first().isEmpty()) {
            val plan = application.assets.open("sample_plan.json").bufferedReader().use { it.readText() }
            planRepository.importPlanFromJson(plan)
        }
    }
}

private fun String.isValidHttpsBaseUrl(): Boolean = runCatching {
    val uri = java.net.URI(this)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

private object UnavailableCoachGateway : CoachGateway {
    private fun unavailable(): Nothing = throw CoachGatewayException(
        code = "COACH_NOT_CONFIGURED",
        userMessage = "Trener online wymaga konfiguracji Supabase i zalogowania.",
        retryable = false,
    )

    override suspend fun getBriefing(): CoachBriefing = unavailable()
    override suspend fun sendMessage(message: String): CoachReply = unavailable()
    override suspend fun getDebrief(workoutSessionId: String): CoachReply = unavailable()
}

private object UnavailableFitbitGateway : FitbitGateway {
    private val unavailableError = FitbitGatewayError(
        code = "FITBIT_NOT_CONFIGURED",
        userMessage = "Fitbit wymaga konfiguracji Supabase i zalogowania.",
        retryable = false,
    )
    private val mutableState = MutableStateFlow<FitbitGatewayState>(FitbitGatewayState.Disconnected)
    override val state: StateFlow<FitbitGatewayState> = mutableState

    override suspend fun startOAuth(redirectUri: String): FitbitOAuthStartResult =
        FitbitOAuthStartResult.Failure(unavailableError).also {
            mutableState.value = FitbitGatewayState.Failure(com.fittrack.core.network.FitbitOperation.OAUTH_START, unavailableError)
        }

    override fun handleOAuthCallback(success: Boolean, reason: String?) = Unit

    override suspend fun sync(request: FitbitSyncRequest): FitbitSyncResult =
        FitbitSyncResult.Failure(unavailableError).also {
            mutableState.value = FitbitGatewayState.Failure(com.fittrack.core.network.FitbitOperation.SYNC, unavailableError)
        }
}
