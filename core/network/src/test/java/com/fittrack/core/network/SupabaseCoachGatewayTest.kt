package com.fittrack.core.network

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseCoachGatewayTest {

    @Test
    fun `briefing posts minimum mode and maps validated response`() = runTest {
        val transport = RecordingTransport(
            HttpResponse(
                status = 200,
                body = response(
                    message = "Dziś utrzymaj ciężar.",
                    citationUrl = "https://example.org/evidence",
                ),
            ),
        )
        val gateway = gateway(transport = transport)

        val briefing = gateway.getBriefing()

        assertEquals("Dziś utrzymaj ciężar.", briefing.text)
        assertEquals(FIXED_NOW, briefing.generatedAt)
        assertEquals("https://example.org/evidence", briefing.citations.single().url)
        assertEquals("https://functions.example.test/coach", transport.calls.single().url)
        assertEquals("Bearer access-old", transport.calls.single().headers["Authorization"])
        assertEquals("briefing", JSONObject(transport.calls.single().body).getString("mode"))
        assertEquals(1, JSONObject(transport.calls.single().body).length())
    }

    @Test
    fun `chat and debrief send their mode-specific input and create local replies`() = runTest {
        val transport = RecordingTransport(
            HttpResponse(200, response("Odpowiedź pierwsza")),
            HttpResponse(200, response("Podsumowanie")),
        )
        val gateway = gateway(transport = transport)

        val chat = gateway.sendMessage("  Jak progresować?  ")
        val debrief = gateway.getDebrief("workout-1")

        assertEquals("local-id", chat.id)
        assertEquals(FIXED_NOW, chat.generatedAt)
        assertEquals("local-id", debrief.id)
        assertEquals(FIXED_NOW, debrief.generatedAt)
        JSONObject(transport.calls[0].body).also {
            assertEquals("chat", it.getString("mode"))
            assertEquals("Jak progresować?", it.getString("message"))
        }
        JSONObject(transport.calls[1].body).also {
            assertEquals("debrief", it.getString("mode"))
            assertEquals("workout-1", it.getString("workoutSessionId"))
        }
    }

    @Test
    fun `401 refreshes JWT and retries exactly once`() = runTest {
        val auth = FakeAuthRepository(
            session = session("access-old"),
            refreshResult = AuthResult.Success(session("access-new")),
        )
        val transport = RecordingTransport(
            HttpResponse(401, "do not expose this"),
            HttpResponse(200, response("Po odświeżeniu")),
        )
        val gateway = gateway(auth = auth, transport = transport)

        gateway.getBriefing()

        assertEquals(1, auth.refreshCalls)
        assertEquals(2, transport.calls.size)
        assertEquals("Bearer access-old", transport.calls[0].headers["Authorization"])
        assertEquals("Bearer access-new", transport.calls[1].headers["Authorization"])
        assertEquals(transport.calls[0].body, transport.calls[1].body)
    }

    @Test
    fun `second 401 is returned as sanitized failure without another refresh`() = runTest {
        val auth = FakeAuthRepository(
            session = session("secret-old"),
            refreshResult = AuthResult.Success(session("secret-new")),
        )
        val transport = RecordingTransport(
            HttpResponse(401, "server leaked secret-old"),
            HttpResponse(401, "server leaked secret-new"),
        )

        val failure = captureFailure { gateway(auth, transport).getBriefing() }

        assertEquals("UNAUTHORIZED", failure.code)
        assertFalse(failure.message.orEmpty().contains("secret"))
        assertEquals(1, auth.refreshCalls)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `refresh failure does not expose authentication backend response`() = runTest {
        val auth = FakeAuthRepository(
            session = session("access-old"),
            refreshResult = AuthResult.Failure(
                NetworkError("RAW_AUTH_CODE", "refresh-secret backend detail", retryable = false),
            ),
        )
        val transport = RecordingTransport(HttpResponse(401, "access-old backend detail"))

        val failure = captureFailure { gateway(auth, transport).getBriefing() }

        assertEquals("AUTH_REFRESH_FAILED", failure.code)
        assertFalse(failure.message.orEmpty().contains("refresh-secret"))
        assertFalse(failure.message.orEmpty().contains("access-old"))
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `rejects missing message and non-HTTPS citations`() = runTest {
        val missingMessage = RecordingTransport(HttpResponse(200, "{\"citations\":[]}"))
        val nonStringMessage = RecordingTransport(HttpResponse(200, "{\"message\":123,\"citations\":[]}"))
        val unsafeCitation = RecordingTransport(
            HttpResponse(200, response("Tekst", "http://example.org/not-secure")),
        )

        val first = captureFailure { gateway(transport = missingMessage).getBriefing() }
        val second = captureFailure { gateway(transport = nonStringMessage).getBriefing() }
        val third = captureFailure { gateway(transport = unsafeCitation).getBriefing() }

        assertEquals("INVALID_COACH_RESPONSE", first.code)
        assertEquals("INVALID_COACH_RESPONSE", second.code)
        assertEquals("INVALID_COACH_RESPONSE", third.code)
    }

    @Test
    fun `does not send request without a session`() = runTest {
        val transport = RecordingTransport(HttpResponse(200, response("Nie powinno zostać użyte")))
        val auth = FakeAuthRepository(session = null)

        val failure = captureFailure { gateway(auth, transport).getBriefing() }

        assertEquals("NO_SESSION", failure.code)
        assertFalse(failure.retryable)
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `HTTP failure never exposes backend body or JWT`() = runTest {
        val transport = RecordingTransport(
            HttpResponse(500, "{\"error\":{\"message\":\"access-old raw-secret\"}}"),
        )

        val failure = captureFailure { gateway(transport = transport).getBriefing() }

        assertEquals("COACH_UNAVAILABLE", failure.code)
        assertFalse(failure.message.orEmpty().contains("access-old"))
        assertFalse(failure.message.orEmpty().contains("raw-secret"))
    }

    private fun gateway(
        auth: FakeAuthRepository = FakeAuthRepository(session("access-old")),
        transport: RecordingTransport,
    ) = SupabaseCoachGateway(
        functionBaseUrl = "https://functions.example.test/",
        authRepository = auth,
        http = transport,
        now = { FIXED_NOW },
        newId = { "local-id" },
    )

    private fun response(message: String, citationUrl: String? = null): String = JSONObject()
        .put("message", message)
        .put(
            "citations",
            if (citationUrl == null) {
                org.json.JSONArray()
            } else {
                org.json.JSONArray().put(
                    JSONObject()
                        .put("title", "Źródło")
                        .put("url", citationUrl)
                        .put("claim", "Twierdzenie"),
                )
            },
        )
        .toString()

    private suspend fun captureFailure(block: suspend () -> Unit): CoachGatewayException =
        try {
            block()
            throw AssertionError("Expected CoachGatewayException")
        } catch (failure: CoachGatewayException) {
            failure
        }

    private fun session(accessToken: String) = AuthSession(
        accessToken = accessToken,
        refreshToken = "refresh-secret",
        expiresAtEpochSeconds = 123L,
        userId = "user-1",
    )

    private class FakeAuthRepository(
        private var session: AuthSession?,
        private val refreshResult: AuthResult = AuthResult.Failure(
            NetworkError("NO_REFRESH", "raw backend auth error", retryable = false),
        ),
    ) : AuthRepository {
        var refreshCalls = 0

        override suspend fun signIn(email: String, password: CharArray): AuthResult =
            error("Not used")

        override suspend fun refreshSession(): AuthResult {
            refreshCalls++
            if (refreshResult is AuthResult.Success) session = refreshResult.session
            return refreshResult
        }

        override suspend fun signOut() {
            session = null
        }

        override suspend fun currentSession(): AuthSession? = session
    }

    private class RecordingTransport(vararg responses: HttpResponse) : HttpTransport {
        private val responses = ArrayDeque(responses.toList())
        val calls = mutableListOf<Call>()

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): HttpResponse {
            calls += Call(url, headers, body)
            return responses.removeFirst()
        }
    }

    private data class Call(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-08-01T10:15:30Z")
    }
}
