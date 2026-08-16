package com.fittrack.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fittrack.core.model.repository.HealthRepository
import com.fittrack.core.network.AuthRepository
import com.fittrack.core.network.AuthResult
import com.fittrack.core.network.HealthConnectGateway
import com.fittrack.core.network.HealthConnectReadOutcome
import com.fittrack.core.network.HealthConnectSyncGateway
import com.fittrack.core.network.SyncResult
import com.fittrack.core.network.toHealthDays
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Health Connect permissions are granted through Android's system permission
 * dialog (an Activity Result contract launched by SettingsRoute), not a
 * browser redirect - see docs/adr/0001-fitbit-health-connect-pivot.md. This
 * view model only tracks "have we been granted the permissions" and lets the
 * UI layer own launching the actual system prompt.
 *
 * Business rule reminder (IMPLEMENTATION_PLAN.md section 5): step count /
 * health signals are context only, never a standalone reason to change a
 * plan or suggestion. Nothing here writes to PlanRepository/WorkoutRepository.
 */
class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val healthConnectGateway: HealthConnectGateway,
    private val healthRepository: HealthRepository,
    private val healthConnectSyncGateway: HealthConnectSyncGateway,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        loadSession()
        refreshHealthConnectState()
    }

    fun retryLoad() {
        if (_state.value.isLoading || _state.value.isSubmitting) return
        loadSession()
    }

    fun signIn(email: String, password: CharArray) {
        val normalizedEmail = email.trim()
        if (_state.value.isSubmitting) {
            password.clearSecurely()
            return
        }
        if (normalizedEmail.isBlank() || password.isEmpty()) {
            password.clearSecurely()
            _state.update {
                it.copy(actionErrorMessage = "Podaj e-mail i hasło.")
            }
            return
        }

        _state.update {
            it.copy(isSubmitting = true, actionErrorMessage = null)
        }
        viewModelScope.launch {
            val result = try {
                authRepository.signIn(normalizedEmail, password)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                password.clearSecurely()
            }

            when (result) {
                is AuthResult.Success -> _state.update {
                    it.copy(
                        isSubmitting = false,
                        isAuthenticated = true,
                        email = normalizedEmail,
                        actionErrorMessage = null,
                    )
                }
                is AuthResult.Failure -> _state.update {
                    it.copy(
                        isSubmitting = false,
                        actionErrorMessage = result.error.userMessage,
                    )
                }
                null -> _state.update {
                    it.copy(
                        isSubmitting = false,
                        actionErrorMessage = "Nie można teraz się zalogować.",
                    )
                }
            }
        }
    }

    fun signOut() {
        if (_state.value.isSubmitting) return
        _state.update {
            it.copy(isSubmitting = true, actionErrorMessage = null)
        }
        viewModelScope.launch {
            val succeeded = try {
                authRepository.signOut()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            _state.update {
                if (succeeded) {
                    SettingsUiState(isLoading = false)
                } else {
                    it.copy(
                        isSubmitting = false,
                        actionErrorMessage = "Nie można teraz się wylogować.",
                    )
                }
            }
        }
    }

    /** Re-checks Health Connect availability/permission state. Called on init
     * and after the UI's permission-request Activity Result returns. */
    fun refreshHealthConnectState() {
        viewModelScope.launch {
            checkHealthConnectPermissions()
        }
    }

    /** Called by the UI once the system permission dialog (launched via
     * `PermissionController.createRequestPermissionResultContract()`) returns. */
    fun onPermissionsResult(grantedPermissions: Set<String>) {
        refreshHealthConnectState()
    }

    private suspend fun checkHealthConnectPermissions() {
        if (!healthConnectGateway.isAvailable()) {
            _state.update {
                it.copy(
                    healthConnectState = HealthConnectState.UNAVAILABLE,
                    requiredPermissions = emptySet(),
                    healthConnectErrorMessage = null,
                )
            }
            return
        }
        val required = healthConnectGateway.requiredPermissions()
        val granted = try {
            healthConnectGateway.hasAllPermissions()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        _state.update {
            it.copy(
                healthConnectState = if (granted) HealthConnectState.GRANTED else HealthConnectState.NOT_GRANTED,
                requiredPermissions = required,
                healthConnectErrorMessage = null,
            )
        }
    }

    fun syncHealthConnect() {
        if (_state.value.isHealthConnectBusy) return
        if (_state.value.healthConnectState != HealthConnectState.GRANTED) return
        _state.update {
            it.copy(
                healthConnectSyncState = HealthConnectSyncState.SYNCING,
                healthConnectErrorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val metrics = when (val outcome = healthConnectGateway.readDailyMetrics()) {
                    is HealthConnectReadOutcome.Ok -> outcome.data
                    is HealthConnectReadOutcome.PartiallyFailed -> {
                        // TODO: surface outcome.failedTypes in the UI instead of
                        // silently treating a partial read the same as a full
                        // one — deferred scope decision, see the M8
                        // architecture review (candidate 1) / grilling notes.
                        outcome.data
                    }
                    is HealthConnectReadOutcome.Failed -> {
                        _state.update {
                            it.copy(
                                healthConnectSyncState = HealthConnectSyncState.ERROR,
                                healthConnectErrorMessage = outcome.error.userMessage,
                            )
                        }
                        return@launch
                    }
                }
                healthRepository.upsertDailyHealth(metrics.toHealthDays())
                when (val pushResult = healthConnectSyncGateway.pushDailyMetrics(metrics)) {
                    is SyncResult.Success -> _state.update {
                        it.copy(
                            healthConnectSyncState = HealthConnectSyncState.SYNCED,
                            lastSyncedAt = now(),
                            lastSyncedDayCount = metrics.size,
                            healthConnectErrorMessage = null,
                        )
                    }
                    is SyncResult.Failure -> _state.update {
                        it.copy(
                            healthConnectSyncState = HealthConnectSyncState.ERROR,
                            healthConnectErrorMessage = pushResult.error.userMessage,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(healthConnectSyncState = HealthConnectSyncState.ERROR) }
                throw cancelled
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        healthConnectSyncState = HealthConnectSyncState.ERROR,
                        healthConnectErrorMessage = "Nie można teraz zsynchronizować danych zdrowotnych.",
                    )
                }
            }
        }
    }

    /** Called by the UI right before it enqueues a manual data sync, so the
     * "Synchronizuj teraz" button shows immediate feedback instead of looking
     * like a no-op until the WorkManager request finishes. */
    fun onDataSyncStarted() {
        if (_state.value.dataSyncState == DataSyncState.SYNCING) return
        _state.update { it.copy(dataSyncState = DataSyncState.SYNCING) }
    }

    /** Called by the UI once [com.fittrack.core.network.NetworkWorkScheduler.enqueueImmediateSyncAndAwait]
     * resolves. */
    fun onDataSyncFinished(succeeded: Boolean) {
        _state.update { it.copy(dataSyncState = if (succeeded) DataSyncState.SYNCED else DataSyncState.ERROR) }
    }

    private fun loadSession() {
        _state.update {
            it.copy(
                isLoading = true,
                loadErrorMessage = null,
                actionErrorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val session = authRepository.currentSession()
                _state.update {
                    it.copy(isLoading = false, loadErrorMessage = null, isAuthenticated = session != null, email = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update {
                    it.copy(isLoading = false, loadErrorMessage = "Nie można odczytać zapisanej sesji.", isAuthenticated = false, email = null)
                }
            }
        }
    }

    companion object {
        fun factory(
            authRepository: AuthRepository,
            healthConnectGateway: HealthConnectGateway,
            healthRepository: HealthRepository,
            healthConnectSyncGateway: HealthConnectSyncGateway,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
                    return SettingsViewModel(authRepository, healthConnectGateway, healthRepository, healthConnectSyncGateway) as T
                }
            }
    }
}

private fun CharArray.clearSecurely() = fill(' ')
