package com.fittrack.core.network

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseFitbitGatewayTest {
    @Test
    fun `oauth parser accepts an absolute HTTPS authorize URL`() {
        val url = parseFitbitAuthorizeUrl(
            """{"authorizeUrl":"https://www.fitbit.com/oauth2/authorize?client_id=test"}""",
        )

        assertEquals("https://www.fitbit.com/oauth2/authorize?client_id=test", url)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oauth parser rejects a non HTTPS authorize URL`() {
        parseFitbitAuthorizeUrl("""{"authorizeUrl":"http://www.fitbit.com/oauth2/authorize"}""")
    }

    @Test
    fun `sync parser preserves pending retry and imported counts`() {
        val result = parseFitbitSyncResponse(
            """{"status":"PENDING","importedCounts":{"heartRateSamples":0},"retryAfter":42}""",
        )

        assertEquals(FitbitRemoteSyncStatus.PENDING, result.status)
        assertEquals(mapOf("heartRateSamples" to 0), result.importedCounts)
        assertEquals(42L, result.retryAfterSeconds)
    }

    @Test
    fun `sync parser accepts partial and returns normalized daily data`() {
        val result = parseFitbitSyncResponse(
            """{
                "status":"PARTIAL",
                "importedCounts":{"steps":1},
                "retryAfter":30,
                "data":{
                    "dailyMetrics":[{"date":"2026-08-01","sleepMinutes":420,"restingHeartRate":58,"steps":9000,"source":"FITBIT"}],
                    "cardioActivities":[{"sourceId":"log-1","kind":"Run","startedAt":"2026-08-01T06:00:00Z","endedAt":"2026-08-01T06:30:00Z","durationSeconds":1800,"distanceMeters":5000.0,"steps":6000,"averageHeartRate":142}],
                    "heartRateSamples":[],
                    "exerciseHeartRateSummaries":[]
                }
            }""".trimIndent(),
        )

        assertEquals(FitbitRemoteSyncStatus.PARTIAL, result.status)
        assertEquals(30L, result.retryAfterSeconds)
        assertEquals(9000, result.dailyMetrics.single().steps)
        assertEquals(Instant.parse("2026-08-01T06:00:00Z"), result.cardioActivities.single().startedAt)
    }

    @Test
    fun `sync parser accepts failed instead of reporting invalid response`() {
        val result = parseFitbitSyncResponse(
            """{"status":"FAILED","importedCounts":{},"retryAfter":null,"data":{}}""",
        )

        assertEquals(FitbitRemoteSyncStatus.FAILED, result.status)
    }

    @Test
    fun `sync parser returns normalized workout heart rate data`() {
        val result = parseFitbitSyncResponse(
            """{
                "status":"SYNCED",
                "importedCounts":{"heartRateSamples":1},
                "retryAfter":null,
                "data":{
                    "heartRateSamples":[{"workoutSessionId":"workout-1","sampledAt":"2026-08-01T10:02:00Z","bpm":135}],
                    "exerciseHeartRateSummaries":[{"exerciseSessionId":"exercise-session-1","averageBpm":132.5,"maximumBpm":150,"recoveryDeltaBpm":25,"sampleCount":3}]
                }
            }""".trimIndent(),
        )

        assertEquals("workout-1", result.heartRateSamples.single().workoutSessionId)
        assertEquals("exercise-session-1", result.exerciseHeartRateSummaries.single().exerciseSessionId)
        assertEquals(132.5, result.exerciseHeartRateSummaries.single().averageBpm)
    }

    @Test
    fun `failed sync produces a controlled state instead of invalid response`() = runTest {
        val gateway = SupabaseFitbitGateway(
            "https://project.supabase.co/functions/v1",
            FakeAuthRepository(session("token"), AuthResult.Success(session("token"))),
            RecordingTransport(
                mutableListOf(
                    HttpResponse(200, """{"status":"FAILED","importedCounts":{},"retryAfter":15}"""),
                ),
            ),
        )

        val result = gateway.sync(FitbitSyncRequest(FitbitSyncMode.DAILY_METRICS))

        assertTrue(result is FitbitSyncResult.Success)
        val state = gateway.state.value as FitbitGatewayState.Failure
        assertEquals("FITBIT_SYNC_FAILED", state.error.code)
        assertEquals(15L, state.error.retryAfterSeconds)
    }

    @Test
    fun `401 refreshes once and retries the exact Fitbit request`() = runTest {
        val oldSession = session("old-token")
        val newSession = session("new-token")
        val auth = FakeAuthRepository(oldSession, AuthResult.Success(newSession))
        val transport = RecordingTransport(
            mutableListOf(
                HttpResponse(401, """{"error":{"code":"FITBIT_UNAUTHORIZED","retryable":false}}"""),
                HttpResponse(200, """{"status":"SYNCED","importedCounts":{"sleep":1},"retryAfter":null}"""),
            ),
        )
        val gateway = SupabaseFitbitGateway("https://project.supabase.co/functions/v1", auth, transport)

        val result = gateway.sync(FitbitSyncRequest(FitbitSyncMode.DAILY_METRICS))

        assertTrue(result is FitbitSyncResult.Success)
        assertEquals(1, auth.refreshCalls)
        assertEquals(2, transport.requests.size)
        assertEquals(transport.requests[0].url, transport.requests[1].url)
        assertEquals(transport.requests[0].body, transport.requests[1].body)
        assertEquals("Bearer old-token", transport.requests[0].headers["Authorization"])
        assertEquals("Bearer new-token", transport.requests[1].headers["Authorization"])
        assertEquals(FitbitGatewayState.Connected, gateway.state.value)
    }

    @Test
    fun `callback stores only a controlled failure reason`() {
        val gateway = SupabaseFitbitGateway(
            "https://project.supabase.co/functions/v1",
            FakeAuthRepository(session("token"), AuthResult.Success(session("token"))),
            RecordingTransport(mutableListOf()),
        )

        gateway.handleOAuthCallback(success = false, reason = "token=do-not-expose")

        val failure = gateway.state.value as FitbitGatewayState.Failure
        assertEquals("OAUTH_CALLBACK_FAILED", failure.error.code)
        assertTrue("do-not-expose" !in failure.error.userMessage)
    }

    private class FakeAuthRepository(
        private var current: AuthSession?,
        private val refreshResult: AuthResult,
    ) : AuthRepository {
        var refreshCalls = 0

        override suspend fun signIn(email: String, password: CharArray) = refreshResult

        override suspend fun refreshSession(): AuthResult {
            refreshCalls += 1
            if (refreshResult is AuthResult.Success) current = refreshResult.session
            return refreshResult
        }

        override suspend fun signOut() {
            current = null
        }

        override suspend fun currentSession(): AuthSession? = current
    }

    private class RecordingTransport(
        private val responses: MutableList<HttpResponse>,
    ) : HttpTransport {
        val requests = mutableListOf<Request>()

        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            requests += Request(url, headers, body)
            return responses.removeAt(0)
        }
    }

    private data class Request(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun session(accessToken: String) = AuthSession(
        accessToken = accessToken,
        refreshToken = "refresh-token",
        expiresAtEpochSeconds = 9_999,
        userId = "user-id",
    )
}
