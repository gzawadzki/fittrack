package com.fittrack.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fittrack.core.model.repository.HealthRepository
import com.fittrack.core.network.AuthRepository
import com.fittrack.core.network.HealthConnectGateway
import com.fittrack.core.network.HealthConnectSyncGateway

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
        modifier = modifier,
    )
}
