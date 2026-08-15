package com.fittrack.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onLogin: (String, CharArray) -> Unit,
    onLogout: () -> Unit,
    onRetry: () -> Unit,
    onGrantHealthConnectPermissions: () -> Unit,
    onSyncHealthConnect: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> LoadingState("Ładowanie ustawień…", modifier)
        state.loadErrorMessage != null -> ErrorState(state.loadErrorMessage, onRetry, modifier)
        state.isAuthenticated -> SignedInSettings(state, onLogout, onGrantHealthConnectPermissions, onSyncHealthConnect, onSyncNow, modifier)
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
    onGrantHealthConnectPermissions: () -> Unit,
    onSyncHealthConnect: () -> Unit,
    onSyncNow: () -> Unit,
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
        FitTrackCard(Modifier.fillMaxWidth()) {
                Text("Synchronizacja danych", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Plany, ćwiczenia i historia treningów synchronizują się automatycznie co 15 minut.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) { Text("Synchronizuj teraz") }
        }
        HealthConnectCard(state, onGrantHealthConnectPermissions, onSyncHealthConnect)
        Text("Dane zdrowotne pochodzą z Health Connect i są odczytywane wyłącznie na urządzeniu.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HealthConnectCard(state: SettingsUiState, onGrantPermissions: () -> Unit, onSync: () -> Unit) {
    FitTrackCard(Modifier.fillMaxWidth()) {
            Text("Health Connect", style = MaterialTheme.typography.titleMedium)
            Text(healthConnectStatusLabel(state.healthConnectState), color = MaterialTheme.colorScheme.primary)
            Text(healthConnectSyncLabel(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.healthConnectErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when (state.healthConnectState) {
                HealthConnectState.UNAVAILABLE -> Text(
                    "Zainstaluj lub zaktualizuj aplikację Health Connect, aby połączyć dane zdrowotne.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HealthConnectState.NOT_GRANTED -> Button(
                    onClick = onGrantPermissions,
                    enabled = !state.isHealthConnectBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Udziel uprawnień Health Connect") }
                HealthConnectState.GRANTED -> Button(
                    onClick = onSync,
                    enabled = !state.isHealthConnectBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.healthConnectSyncState == HealthConnectSyncState.SYNCING) "Synchronizowanie…" else "Synchronizuj teraz") }
            }
    }
}

private fun healthConnectStatusLabel(state: HealthConnectState) = when (state) {
    HealthConnectState.UNAVAILABLE -> "Health Connect niedostępny"
    HealthConnectState.NOT_GRANTED -> "Brak uprawnień"
    HealthConnectState.GRANTED -> "Uprawnienia przyznane"
}

private fun healthConnectSyncLabel(state: SettingsUiState): String = when (state.healthConnectSyncState) {
    HealthConnectSyncState.IDLE -> state.lastSyncedAt?.let { "Ostatnia synchronizacja: ${it.toLocalLabel()}" }
        ?: "Po przyznaniu uprawnień możesz zsynchronizować sen, RHR i kroki."
    HealthConnectSyncState.SYNCING -> "Odczytywanie danych z Health Connect…"
    HealthConnectSyncState.SYNCED -> "Zsynchronizowano ${state.lastSyncedDayCount} dni" +
        (state.lastSyncedAt?.let { " (${it.toLocalLabel()})" } ?: "")
    HealthConnectSyncState.ERROR -> "Synchronizacja nie została ukończona."
}

private val LAST_SYNC_FORMATTER = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun java.time.Instant.toLocalLabel(): String =
    LAST_SYNC_FORMATTER.format(this.atZone(ZoneId.systemDefault()))
