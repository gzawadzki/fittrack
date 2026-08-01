package com.fittrack.feature.settings

import com.fittrack.core.network.AuthRepository
import com.fittrack.core.network.AuthResult
import com.fittrack.core.network.AuthSession
import com.fittrack.core.network.FitbitGateway
import com.fittrack.core.network.FitbitGatewayError
import com.fittrack.core.network.FitbitGatewayState
import com.fittrack.core.network.FitbitOAuthStartResult
import com.fittrack.core.network.FitbitRemoteSyncStatus
import com.fittrack.core.network.FitbitSyncRequest
import com.fittrack.core.network.FitbitSyncResult
import com.fittrack.core.network.NetworkError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `restores an existing authenticated session`() = runTest(dispatcher) {
        val repository = FakeAuthRepository(current = session())

        val viewModel = SettingsViewModel(repository, FakeFitbitGateway())
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.isAuthenticated)
        assertNull(viewModel.state.value.email)
    }

    @Test
    fun `blocks duplicate login and clears every password array`() = runTest(dispatcher) {
        val loginGate = CompletableDeferred<Unit>()
        val repository = FakeAuthRepository(loginGate = loginGate)
        val viewModel = SettingsViewModel(repository, FakeFitbitGateway())
        advanceUntilIdle()
        val firstPassword = "pierwsze".toCharArray()
        val duplicatePassword = "drugie".toCharArray()

        viewModel.signIn(" user@example.com ", firstPassword)
        runCurrent()
        viewModel.signIn("user@example.com", duplicatePassword)

        assertTrue(viewModel.state.value.isSubmitting)
        assertEquals(1, repository.signInCalls)
        assertTrue(duplicatePassword.all { it == '\u0000' })

        loginGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(firstPassword.all { it == '\u0000' })
        assertFalse(viewModel.state.value.isSubmitting)
        assertTrue(viewModel.state.value.isAuthenticated)
        assertEquals("user@example.com", viewModel.state.value.email)
    }

    @Test
    fun `shows repository error and permits another submission`() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            signInResult = AuthResult.Failure(
                NetworkError("INVALID_CREDENTIALS", "Nieprawidłowy e-mail lub hasło.", false),
            ),
        )
        val viewModel = SettingsViewModel(repository, FakeFitbitGateway())
        advanceUntilIdle()
        val password = "sekret".toCharArray()

        viewModel.signIn("user@example.com", password)
        advanceUntilIdle()

        assertTrue(password.all { it == '\u0000' })
        assertFalse(viewModel.state.value.isSubmitting)
        assertFalse(viewModel.state.value.isAuthenticated)
        assertEquals("Nieprawidłowy e-mail lub hasło.", viewModel.state.value.actionErrorMessage)
    }

    @Test
    fun `keeps authenticated state when logout fails`() = runTest(dispatcher) {
        val repository = FakeAuthRepository(current = session(), failSignOut = true)
        val viewModel = SettingsViewModel(repository, FakeFitbitGateway())
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isAuthenticated)
        assertFalse(viewModel.state.value.isSubmitting)
        assertEquals("Nie można teraz się wylogować.", viewModel.state.value.actionErrorMessage)
    }

    @Test
    fun `observes successful Fitbit callback as connected`() = runTest(dispatcher) {
        val fitbit = FakeFitbitGateway()
        val viewModel = SettingsViewModel(FakeAuthRepository(current = session()), fitbit)
        advanceUntilIdle()

        fitbit.handleOAuthCallback(success = true)
        advanceUntilIdle()

        assertEquals(FitbitConnectionState.CONNECTED, viewModel.state.value.fitbitState)
        assertNull(viewModel.state.value.fitbitErrorMessage)
    }

    @Test
    fun `OAuth start exposes only an HTTPS authorize URL once`() = runTest(dispatcher) {
        val fitbit = FakeFitbitGateway(
            oauthResult = FitbitOAuthStartResult.Success("https://www.fitbit.com/oauth2/authorize?state=opaque"),
        )
        val viewModel = SettingsViewModel(FakeAuthRepository(current = session()), fitbit)
        advanceUntilIdle()

        viewModel.startFitbitOAuth("fittrack://auth/fitbit")
        advanceUntilIdle()

        assertEquals(FitbitConnectionState.AWAITING_CALLBACK, viewModel.state.value.fitbitState)
        assertEquals("https://www.fitbit.com/oauth2/authorize?state=opaque", viewModel.state.value.fitbitAuthorizeUrl)
        viewModel.consumeFitbitAuthorizeUrl()
        assertNull(viewModel.state.value.fitbitAuthorizeUrl)
    }

    @Test
    fun `OAuth start exposes a loading state while the gateway is busy`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val fitbit = FakeFitbitGateway(
            oauthResult = FitbitOAuthStartResult.Success("https://www.fitbit.com/oauth2/authorize"),
            oauthGate = gate,
        )
        val viewModel = SettingsViewModel(FakeAuthRepository(current = session()), fitbit)
        advanceUntilIdle()

        viewModel.startFitbitOAuth("fittrack://auth/fitbit")
        runCurrent()

        assertEquals(FitbitConnectionState.STARTING_OAUTH, viewModel.state.value.fitbitState)
        assertTrue(viewModel.state.value.isFitbitBusy)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(FitbitConnectionState.AWAITING_CALLBACK, viewModel.state.value.fitbitState)
    }

    @Test
    fun `OAuth start rejects an unsafe URL from a gateway implementation`() = runTest(dispatcher) {
        val fitbit = FakeFitbitGateway(
            oauthResult = FitbitOAuthStartResult.Success("javascript:alert(1)"),
        )
        val viewModel = SettingsViewModel(FakeAuthRepository(current = session()), fitbit)
        advanceUntilIdle()

        viewModel.startFitbitOAuth("fittrack://auth/fitbit")
        advanceUntilIdle()

        assertEquals(FitbitConnectionState.ERROR, viewModel.state.value.fitbitState)
        assertNull(viewModel.state.value.fitbitAuthorizeUrl)
    }

    @Test
    fun `daily Fitbit sync preserves pending retry state`() = runTest(dispatcher) {
        val fitbit = FakeFitbitGateway(
            initialState = FitbitGatewayState.Connected,
            syncResult = FitbitSyncResult.Success(
                status = FitbitRemoteSyncStatus.PENDING,
                importedCounts = mapOf("sleep" to 1),
                retryAfterSeconds = 42,
            ),
        )
        val viewModel = SettingsViewModel(FakeAuthRepository(current = session()), fitbit)
        advanceUntilIdle()

        viewModel.syncFitbitDailyMetrics()
        advanceUntilIdle()

        assertEquals(FitbitConnectionState.CONNECTED, viewModel.state.value.fitbitState)
        assertEquals(FitbitSyncState.PENDING, viewModel.state.value.fitbitSyncState)
        assertEquals(42L, viewModel.state.value.fitbitRetryAfterSeconds)
        assertEquals(mapOf("sleep" to 1), viewModel.state.value.fitbitImportedCounts)
    }

    @Test
    fun `daily Fitbit sync exposes a controlled gateway error`() = runTest(dispatcher) {
        val fitbit = FakeFitbitGateway(
            initialState = FitbitGatewayState.Connected,
            syncResult = FitbitSyncResult.Failure(
                FitbitGatewayError("FITBIT_RATE_LIMITED", "Spróbuj później.", true, 60),
            ),
        )
        val viewModel = SettingsViewModel(FakeAuthRepository(current = session()), fitbit)
        advanceUntilIdle()

        viewModel.syncFitbitDailyMetrics()
        advanceUntilIdle()

        assertEquals(FitbitSyncState.ERROR, viewModel.state.value.fitbitSyncState)
        assertEquals("Spróbuj później.", viewModel.state.value.fitbitErrorMessage)
        assertEquals(60L, viewModel.state.value.fitbitRetryAfterSeconds)
    }

    private class FakeAuthRepository(
        private val current: AuthSession? = null,
        private val signInResult: AuthResult = AuthResult.Success(session()),
        private val loginGate: CompletableDeferred<Unit>? = null,
        private val failSignOut: Boolean = false,
    ) : AuthRepository {
        var signInCalls = 0

        override suspend fun signIn(email: String, password: CharArray): AuthResult {
            signInCalls += 1
            loginGate?.await()
            return signInResult
        }

        override suspend fun refreshSession(): AuthResult = signInResult

        override suspend fun signOut() {
            if (failSignOut) error("logout failed")
        }

        override suspend fun currentSession(): AuthSession? = current
    }

    private class FakeFitbitGateway(
        initialState: FitbitGatewayState = FitbitGatewayState.Disconnected,
        private val oauthResult: FitbitOAuthStartResult = FitbitOAuthStartResult.Failure(
            FitbitGatewayError("NOT_CONFIGURED", "Fitbit nie jest skonfigurowany.", false),
        ),
        private val syncResult: FitbitSyncResult = FitbitSyncResult.Failure(
            FitbitGatewayError("NOT_CONFIGURED", "Fitbit nie jest skonfigurowany.", false),
        ),
        private val oauthGate: CompletableDeferred<Unit>? = null,
    ) : FitbitGateway {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<FitbitGatewayState> = mutableState

        override suspend fun startOAuth(redirectUri: String): FitbitOAuthStartResult {
            mutableState.value = FitbitGatewayState.StartingOAuth
            oauthGate?.await()
            mutableState.value = when (oauthResult) {
                is FitbitOAuthStartResult.Success -> FitbitGatewayState.AwaitingOAuthCallback
                is FitbitOAuthStartResult.Failure -> FitbitGatewayState.Failure(
                    com.fittrack.core.network.FitbitOperation.OAUTH_START,
                    oauthResult.error,
                )
            }
            return oauthResult
        }

        override fun handleOAuthCallback(success: Boolean, reason: String?) {
            mutableState.value = if (success) {
                FitbitGatewayState.Connected
            } else {
                FitbitGatewayState.Failure(
                    com.fittrack.core.network.FitbitOperation.OAUTH_CALLBACK,
                    FitbitGatewayError("CALLBACK_FAILED", "Połączenie nie powiodło się.", false),
                )
            }
        }

        override suspend fun sync(request: FitbitSyncRequest): FitbitSyncResult {
            mutableState.value = FitbitGatewayState.Syncing(request.mode)
            mutableState.value = when (syncResult) {
                is FitbitSyncResult.Success -> FitbitGatewayState.Connected
                is FitbitSyncResult.Failure -> FitbitGatewayState.Failure(
                    com.fittrack.core.network.FitbitOperation.SYNC,
                    syncResult.error,
                )
            }
            return syncResult
        }
    }

    companion object {
        private fun session() = AuthSession(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtEpochSeconds = 10_000,
            userId = "user-id",
        )
    }
}
