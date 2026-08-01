package com.fittrack.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fittrack.core.network.AuthRepository
import com.fittrack.core.network.FitbitGateway

@Composable
fun SettingsRoute(
    authRepository: AuthRepository,
    fitbitGateway: FitbitGateway,
    fitbitOAuthRedirectUri: String,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(authRepository, fitbitGateway),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(state.fitbitAuthorizeUrl) {
        state.fitbitAuthorizeUrl?.let { authorizeUrl ->
            val opened = runCatching { uriHandler.openUri(authorizeUrl) }.isSuccess
            if (opened) viewModel.consumeFitbitAuthorizeUrl()
            else viewModel.reportFitbitAuthorizeLaunchFailure()
        }
    }
    SettingsScreen(
        state = state,
        onLogin = viewModel::signIn,
        onLogout = viewModel::signOut,
        onRetry = viewModel::retryLoad,
        onConnectFitbit = { viewModel.startFitbitOAuth(fitbitOAuthRedirectUri) },
        onSyncFitbit = viewModel::syncFitbitDailyMetrics,
        modifier = modifier,
    )
}
