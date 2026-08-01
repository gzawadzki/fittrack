package com.fittrack.feature.settings

enum class FitbitConnectionState {
    DISCONNECTED,
    STARTING_OAUTH,
    AWAITING_CALLBACK,
    CONNECTED,
    ERROR,
}

enum class FitbitSyncState { IDLE, SYNCING, PENDING, SYNCED, ERROR }

data class SettingsUiState(
    val isLoading: Boolean = true,
    val loadErrorMessage: String? = null,
    val actionErrorMessage: String? = null,
    val email: String? = null,
    val isAuthenticated: Boolean = false,
    val fitbitState: FitbitConnectionState = FitbitConnectionState.DISCONNECTED,
    val fitbitSyncState: FitbitSyncState = FitbitSyncState.IDLE,
    val fitbitErrorMessage: String? = null,
    val fitbitAuthorizeUrl: String? = null,
    val fitbitRetryAfterSeconds: Long? = null,
    val fitbitImportedCounts: Map<String, Int> = emptyMap(),
    val isSubmitting: Boolean = false,
) {
    val isFitbitBusy: Boolean
        get() = fitbitState == FitbitConnectionState.STARTING_OAUTH || fitbitSyncState == FitbitSyncState.SYNCING
}
