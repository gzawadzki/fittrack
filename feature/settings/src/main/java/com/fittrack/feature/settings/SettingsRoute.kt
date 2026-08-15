package com.fittrack.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fittrack.core.model.repository.HealthRepository
import com.fittrack.core.network.AuthRepository
import com.fittrack.core.network.HealthConnectGateway
import com.fittrack.core.network.HealthConnectSyncGateway
import com.fittrack.core.network.NetworkWorkScheduler

@Composable
fun SettingsRoute(
    authRepository: AuthRepository,
    healthConnectGateway: HealthConnectGateway,
    healthRepository: HealthRepository,
    healthConnectSyncGateway: HealthConnectSyncGateway,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(
            authRepository,
            healthConnectGateway,
            healthRepository,
            healthConnectSyncGateway,
        ),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Health Connect permissions are a system permission dialog (Activity
    // Result contract), not a browser redirect - see
    // docs/adr/0001-fitbit-health-connect-pivot.md.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { grantedPermissions -> viewModel.onPermissionsResult(grantedPermissions) }

    SettingsScreen(
        state = state,
        onLogin = viewModel::signIn,
        onLogout = viewModel::signOut,
        onRetry = viewModel::retryLoad,
        onGrantHealthConnectPermissions = { permissionLauncher.launch(state.requiredPermissions) },
        onSyncHealthConnect = viewModel::syncHealthConnect,
        // Plans/exercises/profile/workout history sync through SyncOrchestrator's
        // outbox on a 15-minute periodic worker with no manual trigger today -
        // unlike Health Connect's own "Synchronizuj teraz" above, there was no way
        // to force this sooner. enqueueImmediateSync already existed for the
        // post-workout path; this just exposes it here too.
        onSyncNow = { NetworkWorkScheduler.enqueueImmediateSync(context) },
        modifier = modifier,
    )
}
