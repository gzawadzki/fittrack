package com.fittrack.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fittrack.core.network.AuthRepository
import com.fittrack.core.network.AuthResult
import com.fittrack.core.network.FitbitGateway
import com.fittrack.core.network.FitbitGatewayState
import com.fittrack.core.network.FitbitOAuthStartResult
import com.fittrack.core.network.FitbitOperation
import com.fittrack.core.network.FitbitRemoteSyncStatus
import com.fittrack.core.network.FitbitSyncMode
import com.fittrack.core.network.FitbitSyncRequest
import com.fittrack.core.network.FitbitSyncResult
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val fitbitGateway: FitbitGateway,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        observeFitbitState()
        loadSession()
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

    fun startFitbitOAuth(redirectUri: String) {
        if (_state.value.isFitbitBusy) return
        val previousConnectionState = _state.value.fitbitState
        _state.update { it.copy(fitbitErrorMessage = null, fitbitAuthorizeUrl = null) }
        viewModelScope.launch {
            try {
                when (val result = fitbitGateway.startOAuth(redirectUri)) {
                    is FitbitOAuthStartResult.Success -> if (result.authorizeUrl.isSafeHttpsAuthorizeUrl()) {
                        _state.update {
                            it.copy(
                                fitbitState = FitbitConnectionState.AWAITING_CALLBACK,
                                fitbitAuthorizeUrl = result.authorizeUrl,
                                fitbitErrorMessage = null,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                fitbitState = FitbitConnectionState.ERROR,
                                fitbitAuthorizeUrl = null,
                                fitbitErrorMessage = "Serwer zwrócił nieprawidłowy adres autoryzacji Fitbit.",
                            )
                        }
                    }
                    is FitbitOAuthStartResult.Failure -> _state.update {
                        it.copy(
                            fitbitState = FitbitConnectionState.ERROR,
                            fitbitErrorMessage = result.error.userMessage,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(
                        fitbitState = previousConnectionState,
                        fitbitAuthorizeUrl = null,
                    )
                }
                throw cancelled
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        fitbitState = FitbitConnectionState.ERROR,
                        fitbitErrorMessage = "Nie można teraz rozpocząć połączenia z Fitbit.",
                    )
                }
            }
        }
    }

    fun consumeFitbitAuthorizeUrl() {
        _state.update { it.copy(fitbitAuthorizeUrl = null) }
    }

    fun reportFitbitAuthorizeLaunchFailure() {
        _state.update {
            it.copy(
                fitbitState = FitbitConnectionState.ERROR,
                fitbitErrorMessage = "Nie można otworzyć strony autoryzacji Fitbit.",
                fitbitAuthorizeUrl = null,
            )
        }
    }

    fun syncFitbitDailyMetrics() {
        if (_state.value.isFitbitBusy) return
        val previousSyncState = _state.value.fitbitSyncState
        _state.update {
            it.copy(
                fitbitSyncState = FitbitSyncState.SYNCING,
                fitbitErrorMessage = null,
                fitbitRetryAfterSeconds = null,
            )
        }
        viewModelScope.launch {
            try {
                when (val result = fitbitGateway.sync(FitbitSyncRequest(FitbitSyncMode.DAILY_METRICS))) {
                    is FitbitSyncResult.Success -> _state.update {
                        it.copy(
                            fitbitState = FitbitConnectionState.CONNECTED,
                            fitbitSyncState = when (result.status) {
                                FitbitRemoteSyncStatus.PENDING,
                                FitbitRemoteSyncStatus.PARTIAL,
                                -> FitbitSyncState.PENDING
                                FitbitRemoteSyncStatus.SYNCED -> FitbitSyncState.SYNCED
                                FitbitRemoteSyncStatus.FAILED -> FitbitSyncState.ERROR
                            },
                            fitbitErrorMessage = if (result.status == FitbitRemoteSyncStatus.FAILED) {
                                "Synchronizacja Fitbit nie została ukończona."
                            } else {
                                null
                            },
                            fitbitRetryAfterSeconds = result.retryAfterSeconds,
                            fitbitImportedCounts = result.importedCounts,
                        )
                    }
                    is FitbitSyncResult.Failure -> _state.update {
                        it.copy(
                            fitbitSyncState = FitbitSyncState.ERROR,
                            fitbitErrorMessage = result.error.userMessage,
                            fitbitRetryAfterSeconds = result.error.retryAfterSeconds,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(fitbitSyncState = previousSyncState) }
                throw cancelled
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        fitbitSyncState = FitbitSyncState.ERROR,
                        fitbitErrorMessage = "Nie można teraz zsynchronizować Fitbit.",
                    )
                }
            }
        }
    }

    private fun observeFitbitState() {
        viewModelScope.launch {
            fitbitGateway.state.collect { gatewayState ->
                _state.update { current -> current.withGatewayState(gatewayState) }
            }
        }
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
        fun factory(authRepository: AuthRepository, fitbitGateway: FitbitGateway): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
                    return SettingsViewModel(authRepository, fitbitGateway) as T
                }
            }
    }
}

private fun CharArray.clearSecurely() = fill('\u0000')

private fun String.isSafeHttpsAuthorizeUrl(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

private fun SettingsUiState.withGatewayState(gatewayState: FitbitGatewayState): SettingsUiState = when (gatewayState) {
    FitbitGatewayState.Disconnected -> copy(fitbitState = FitbitConnectionState.DISCONNECTED)
    FitbitGatewayState.StartingOAuth -> copy(
        fitbitState = FitbitConnectionState.STARTING_OAUTH,
        fitbitErrorMessage = null,
    )
    FitbitGatewayState.AwaitingOAuthCallback -> if (
        fitbitState == FitbitConnectionState.ERROR && fitbitErrorMessage != null
    ) {
        this
    } else {
        copy(
            fitbitState = FitbitConnectionState.AWAITING_CALLBACK,
            fitbitErrorMessage = null,
        )
    }
    FitbitGatewayState.Connected -> copy(
        fitbitState = FitbitConnectionState.CONNECTED,
        fitbitErrorMessage = null,
    )
    is FitbitGatewayState.Syncing -> copy(
        fitbitSyncState = FitbitSyncState.SYNCING,
        fitbitErrorMessage = null,
    )
    is FitbitGatewayState.Failure -> when (gatewayState.operation) {
        FitbitOperation.SYNC -> copy(
            fitbitSyncState = FitbitSyncState.ERROR,
            fitbitErrorMessage = gatewayState.error.userMessage,
            fitbitRetryAfterSeconds = gatewayState.error.retryAfterSeconds,
        )
        FitbitOperation.OAUTH_START, FitbitOperation.OAUTH_CALLBACK -> copy(
            fitbitState = FitbitConnectionState.ERROR,
            fitbitErrorMessage = gatewayState.error.userMessage,
        )
    }
}
