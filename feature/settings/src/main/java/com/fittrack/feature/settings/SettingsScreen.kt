package com.fittrack.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fittrack.core.designsystem.DestructiveOutlinedButton
import com.fittrack.core.designsystem.ErrorState
import com.fittrack.core.designsystem.LoadingState
import com.fittrack.core.designsystem.FitTrackCard

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onLogin: (String, CharArray) -> Unit,
    onLogout: () -> Unit,
    onRetry: () -> Unit,
    onConnectFitbit: () -> Unit,
    onSyncFitbit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> LoadingState("Ładowanie ustawień…", modifier)
        state.loadErrorMessage != null -> ErrorState(state.loadErrorMessage, onRetry, modifier)
        state.isAuthenticated -> SignedInSettings(state, onLogout, onConnectFitbit, onSyncFitbit, modifier)
        else -> LoginForm(state, onLogin, modifier)
    }
}

@Composable
private fun LoginForm(state: SettingsUiState, onLogin: (String, CharArray) -> Unit, modifier: Modifier) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("USTAWIENIA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text("Zaloguj się", style = MaterialTheme.typography.headlineMedium)
        Text("Użyj prywatnego konta skonfigurowanego dla tej aplikacji.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.isSubmitting)
        OutlinedTextField(password, { password = it }, label = { Text("Hasło") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.isSubmitting, visualTransformation = PasswordVisualTransformation())
        state.actionErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val passwordChars = password.toCharArray()
                password = ""
                onLogin(email.trim(), passwordChars)
            },
            enabled = !state.isSubmitting && email.isNotBlank() && password.isNotBlank(),
        ) {
            Text(if (state.isSubmitting) "Logowanie…" else "Zaloguj")
        }
    }
}

@Composable
private fun SignedInSettings(
    state: SettingsUiState,
    onLogout: () -> Unit,
    onConnectFitbit: () -> Unit,
    onSyncFitbit: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("USTAWIENIA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text("Konto i integracje", style = MaterialTheme.typography.headlineMedium)
        }
        FitTrackCard(Modifier.fillMaxWidth()) {
                Text("Konto", style = MaterialTheme.typography.titleMedium)
                Text(state.email ?: "Zalogowano", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.actionErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                DestructiveOutlinedButton(
                    label = if (state.isSubmitting) "Wylogowywanie…" else "Wyloguj",
                    onClick = onLogout,
                    enabled = !state.isSubmitting,
                )
        }
        FitbitCard(state, onConnectFitbit, onSyncFitbit)
        Text("Tokeny Fitbit i hasło nie są wyświetlane ani zapisywane w UI.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FitbitCard(state: SettingsUiState, onConnect: () -> Unit, onSync: () -> Unit) {
    FitTrackCard(Modifier.fillMaxWidth()) {
            Text("Fitbit", style = MaterialTheme.typography.titleMedium)
            Text(fitbitStatusLabel(state.fitbitState), color = MaterialTheme.colorScheme.primary)
            Text(fitbitSyncLabel(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.fitbitErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when (state.fitbitState) {
                FitbitConnectionState.DISCONNECTED -> {
                    Button(
                        onClick = onSync,
                        enabled = !state.isFitbitBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Sprawdź połączenie i synchronizuj") }
                    TextButton(
                        onClick = onConnect,
                        enabled = !state.isFitbitBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Połącz Fitbit") }
                }
                FitbitConnectionState.ERROR -> Button(
                    onClick = onConnect,
                    enabled = !state.isFitbitBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Połącz ponownie") }
                FitbitConnectionState.STARTING_OAUTH -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Otwieranie autoryzacji…") }
                FitbitConnectionState.AWAITING_CALLBACK -> {
                    Text("Dokończ autoryzację w otwartej stronie Fitbit.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onConnect, enabled = !state.isFitbitBusy, modifier = Modifier.fillMaxWidth()) {
                        Text("Rozpocznij ponownie")
                    }
                }
                FitbitConnectionState.CONNECTED -> Button(
                    onClick = onSync,
                    enabled = !state.isFitbitBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.fitbitSyncState == FitbitSyncState.SYNCING) "Synchronizowanie…" else "Synchronizuj dane dzienne") }
            }
    }
}

private fun fitbitStatusLabel(state: FitbitConnectionState) = when (state) {
    FitbitConnectionState.DISCONNECTED -> "Konto niepołączone"
    FitbitConnectionState.STARTING_OAUTH -> "Rozpoczynanie autoryzacji"
    FitbitConnectionState.AWAITING_CALLBACK -> "Oczekiwanie na Fitbit"
    FitbitConnectionState.CONNECTED -> "Konto połączone"
    FitbitConnectionState.ERROR -> "Połączenie wymaga uwagi"
}

private fun fitbitSyncLabel(state: SettingsUiState): String = when (state.fitbitSyncState) {
    FitbitSyncState.IDLE -> "Po połączeniu możesz ręcznie pobrać sen, RHR, kroki i cardio."
    FitbitSyncState.SYNCING -> "Pobieranie danych z Fitbit…"
    FitbitSyncState.PENDING -> state.fitbitRetryAfterSeconds?.let { "Fitbit jeszcze przetwarza dane. Ponów za $it s." }
        ?: "Fitbit jeszcze przetwarza dane. Spróbuj ponownie później."
    FitbitSyncState.SYNCED -> state.fitbitImportedCounts.entries
        .sortedBy { it.key }
        .joinToString(prefix = "Zsynchronizowano: ", separator = ", ") { "${it.key}: ${it.value}" }
        .takeUnless { state.fitbitImportedCounts.isEmpty() }
        ?: "Dane Fitbit są aktualne."
    FitbitSyncState.ERROR -> "Synchronizacja nie została ukończona."
}
