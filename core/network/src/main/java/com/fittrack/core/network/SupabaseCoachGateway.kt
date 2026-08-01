package com.fittrack.core.network

import com.fittrack.core.model.Citation
import com.fittrack.core.model.CoachBriefing
import com.fittrack.core.model.CoachReply
import com.fittrack.core.model.repository.CoachGateway
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Authenticated boundary for the private coach Edge Function.
 *
 * Prompt construction and Gemini access stay on the backend. This client sends
 * only the requested operation and its minimum input, then converts a validated
 * structured response into domain values. Server response bodies, JWTs and
 * refresh tokens are never included in thrown messages.
 */
class SupabaseCoachGateway(
    private val functionBaseUrl: String,
    private val authRepository: AuthRepository,
    private val http: HttpTransport = UrlConnectionTransport,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : CoachGateway {

    init {
        require(functionBaseUrl.isNotBlank()) { "Coach function URL is required" }
    }

    override suspend fun getBriefing(): CoachBriefing {
        val response = request(JSONObject().put(MODE, BRIEFING))
        return CoachBriefing(
            text = response.message,
            generatedAt = now(),
            citations = response.citations,
        )
    }

    override suspend fun sendMessage(message: String): CoachReply {
        val normalized = message.trim()
        require(normalized.isNotEmpty()) { "Wiadomość nie może być pusta." }
        require(normalized.length <= MAX_INPUT_LENGTH) { "Wiadomość jest zbyt długa." }
        return request(JSONObject().put(MODE, CHAT).put(MESSAGE, normalized)).toReply()
    }

    override suspend fun getDebrief(workoutSessionId: String): CoachReply {
        val normalized = workoutSessionId.trim()
        require(normalized.isNotEmpty()) { "Identyfikator treningu jest wymagany." }
        require(normalized.length <= MAX_ID_LENGTH) { "Nieprawidłowy identyfikator treningu." }
        return request(
            JSONObject()
                .put(MODE, DEBRIEF)
                .put(WORKOUT_SESSION_ID, normalized),
        ).toReply()
    }

    private fun ValidatedResponse.toReply() = CoachReply(
        id = newId(),
        text = message,
        generatedAt = now(),
        citations = citations,
    )

    private suspend fun request(body: JSONObject): ValidatedResponse {
        var session = try {
            authRepository.currentSession()
        } catch (_: Exception) {
            throw CoachGatewayException(
                code = "AUTH_UNAVAILABLE",
                userMessage = "Nie można odczytać sesji. Zaloguj się ponownie.",
                retryable = true,
            )
        }
            ?: throw CoachGatewayException(
                code = "NO_SESSION",
                userMessage = "Zaloguj się, aby skorzystać z trenera.",
                retryable = false,
            )

        var response = post(session, body)
        if (response.status == 401) {
            val refresh = try {
                authRepository.refreshSession()
            } catch (_: Exception) {
                throw CoachGatewayException(
                    code = "AUTH_REFRESH_FAILED",
                    userMessage = "Sesja wygasła. Zaloguj się ponownie.",
                    retryable = true,
                )
            }
            session = when (refresh) {
                is AuthResult.Success -> refresh.session
                is AuthResult.Failure -> throw CoachGatewayException(
                    code = "AUTH_REFRESH_FAILED",
                    userMessage = "Sesja wygasła. Zaloguj się ponownie.",
                    retryable = refresh.error.retryable,
                )
            }
            response = post(session, body)
        }

        if (response.status !in 200..299) throw sanitizedHttpFailure(response)
        return parseResponse(response.body)
    }

    private suspend fun post(session: AuthSession, body: JSONObject): HttpResponse = try {
        http.post(
            url = "${functionBaseUrl.trimEnd('/')}/coach",
            headers = mapOf(
                "Authorization" to "Bearer ${session.accessToken}",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            ),
            body = body.toString(),
        )
    } catch (failure: CoachGatewayException) {
        throw failure
    } catch (_: Exception) {
        throw CoachGatewayException(
            code = "COACH_UNAVAILABLE",
            userMessage = "Nie można teraz połączyć się z trenerem.",
            retryable = true,
        )
    }

    private fun sanitizedHttpFailure(response: HttpResponse): CoachGatewayException {
        val retryAfter = response.headers.entries
            .firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.toLongOrNull()
        return when {
            response.status == 401 -> CoachGatewayException(
                code = "UNAUTHORIZED",
                userMessage = "Sesja wygasła. Zaloguj się ponownie.",
                retryable = false,
            )
            response.status == 429 -> CoachGatewayException(
                code = "RATE_LIMITED",
                userMessage = "Trener jest chwilowo zajęty. Spróbuj ponownie później.",
                retryable = true,
                retryAfterSeconds = retryAfter,
            )
            response.status == 408 || response.status >= 500 -> CoachGatewayException(
                code = "COACH_UNAVAILABLE",
                userMessage = "Trener jest chwilowo niedostępny.",
                retryable = true,
                retryAfterSeconds = retryAfter,
            )
            else -> CoachGatewayException(
                code = "COACH_REQUEST_FAILED",
                userMessage = "Nie udało się pobrać odpowiedzi trenera.",
                retryable = false,
            )
        }
    }

    private fun parseResponse(rawBody: String): ValidatedResponse {
        val root = try {
            JSONObject(rawBody)
        } catch (_: Exception) {
            throw invalidResponse()
        }
        val message = root.requiredString(MESSAGE, MAX_RESPONSE_LENGTH)

        val citations = when {
            !root.has(CITATIONS) -> emptyList()
            root.isNull(CITATIONS) -> throw invalidResponse()
            else -> parseCitations(root.optJSONArray(CITATIONS) ?: throw invalidResponse())
        }
        return ValidatedResponse(message, citations)
    }

    private fun parseCitations(array: JSONArray): List<Citation> {
        if (array.length() > MAX_CITATIONS) throw invalidResponse()
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: throw invalidResponse()
            val title = item.requiredString(TITLE, MAX_CITATION_TEXT_LENGTH)
            val url = item.requiredString(URL, MAX_URL_LENGTH)
            val claim = item.requiredString(CLAIM, MAX_CITATION_TEXT_LENGTH)
            if (!url.isSafeHttpsUrl()) throw invalidResponse()
            Citation(title = title, url = url, claim = claim)
        }
    }

    private fun JSONObject.requiredString(name: String, maxLength: Int): String {
        val raw = opt(name)
        if (raw !is String) throw invalidResponse()
        return raw.trim().also { value ->
            if (value.isEmpty() || value.length > maxLength) throw invalidResponse()
        }
    }

    private fun String.isSafeHttpsUrl(): Boolean = runCatching {
        val parsed = URI(this)
        parsed.scheme.equals("https", ignoreCase = true) &&
            !parsed.host.isNullOrBlank() &&
            parsed.userInfo == null
    }.getOrDefault(false)

    private fun invalidResponse() = CoachGatewayException(
        code = "INVALID_COACH_RESPONSE",
        userMessage = "Serwer trenera zwrócił nieprawidłową odpowiedź.",
        retryable = true,
    )

    private data class ValidatedResponse(
        val message: String,
        val citations: List<Citation>,
    )

    private companion object {
        const val MODE = "mode"
        const val MESSAGE = "message"
        const val CITATIONS = "citations"
        const val TITLE = "title"
        const val URL = "url"
        const val CLAIM = "claim"
        const val WORKOUT_SESSION_ID = "workoutSessionId"
        const val BRIEFING = "briefing"
        const val CHAT = "chat"
        const val DEBRIEF = "debrief"
        const val MAX_INPUT_LENGTH = 4_000
        const val MAX_RESPONSE_LENGTH = 20_000
        const val MAX_CITATION_TEXT_LENGTH = 1_000
        const val MAX_URL_LENGTH = 2_048
        const val MAX_CITATIONS = 20
        const val MAX_ID_LENGTH = 128
    }
}

/** Sanitized failure safe to surface or report without backend response data. */
class CoachGatewayException(
    val code: String,
    userMessage: String,
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
) : Exception(userMessage)
