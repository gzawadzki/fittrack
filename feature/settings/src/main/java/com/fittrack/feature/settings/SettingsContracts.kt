package com.fittrack.feature.settings

import java.time.Instant

/**
 * Health Connect replaces the old Fitbit OAuth flow (see
 * docs/adr/0001-fitbit-health-connect-pivot.md). There is no browser
 * redirect/callback anymore - permissions are granted through Android's
 * system permission dialog, so the state machine collapses to "is the
 * provider app installed" x "have we been granted the permissions we need".
 */
enum class HealthConnectState {
    /** Health Connect (the provider app) is not installed/usable on this device. */
    UNAVAILABLE,
    NOT_GRANTED,
    GRANTED,
}

enum class HealthConnectSyncState { IDLE, SYNCING, SYNCED, ERROR }

data class SettingsUiState(
    val isLoading: Boolean = true,
    val loadErrorMessage: String? = null,
    val actionErrorMessage: String? = null,
    val email: String? = null,
    val isAuthenticated: Boolean = false,
    val healthConnectState: HealthConnectState = HealthConnectState.NOT_GRANTED,
    val healthConnectSyncState: HealthConnectSyncState = HealthConnectSyncState.IDLE,
    val healthConnectErrorMessage: String? = null,
    val requiredPermissions: Set<String> = emptySet(),
    val lastSyncedAt: Instant? = null,
    val lastSyncedDayCount: Int = 0,
    val isSubmitting: Boolean = false,
) {
    val isHealthConnectBusy: Boolean
        get() = healthConnectSyncState == HealthConnectSyncState.SYNCING
}
