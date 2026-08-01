package com.fittrack.core.network

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SupabaseAuthRepository(
    private val projectUrl: String,
    private val anonKey: String,
    private val sessionStore: SessionStore,
    private val http: HttpTransport = UrlConnectionTransport,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
) : AuthRepository {
    override suspend fun signIn(email: String, password: CharArray): AuthResult {
        if (email.isBlank() || password.isEmpty()) {
            password.fill('\u0000')
            return AuthResult.Failure(NetworkError("INVALID_CREDENTIALS", "Podaj e-mail i hasło.", false))
        }
        return try {
            val body = JSONObject().put("email", email.trim()).put("password", password.concatToString()).toString()
            password.fill('\u0000')
            requestSession("/auth/v1/token?grant_type=password", body)
        } catch (_: Exception) {
            password.fill('\u0000')
            AuthResult.Failure(NetworkError("AUTH_UNAVAILABLE", "Nie można teraz się zalogować.", true))
        }
    }

    override suspend fun refreshSession(): AuthResult {
        val stored = sessionStore.read()
            ?: return AuthResult.Failure(NetworkError("NO_SESSION", "Zaloguj się ponownie.", false))
        return requestSession(
            "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", stored.refreshToken).toString(),
        )
    }

    override suspend fun signOut() = sessionStore.clear()

    override suspend fun currentSession(): AuthSession? = sessionStore.read()

    private suspend fun requestSession(path: String, body: String): AuthResult {
        val response = http.post(
            "${projectUrl.trimEnd('/')}$path",
            mapOf("apikey" to anonKey, "Content-Type" to "application/json"),
            body,
        )
        if (response.status !in 200..299) return AuthResult.Failure(response.toNetworkError())
        return try {
            val json = JSONObject(response.body)
            val access = json.getString("access_token")
            val refresh = json.getString("refresh_token")
            val expiresIn = json.optLong("expires_in", 0L)
            val userId = json.getJSONObject("user").getString("id")
            AuthSession(access, refresh, nowEpochSeconds() + expiresIn, userId).also(sessionStore::write)
                .let(AuthResult::Success)
        } catch (_: Exception) {
            AuthResult.Failure(NetworkError("INVALID_AUTH_RESPONSE", "Serwer zwrócił nieprawidłową odpowiedź.", true))
        }
    }
}

data class HttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

interface HttpTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}

object UrlConnectionTransport : HttpTransport {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
        val status = connection.responseCode
        val stream = if (status in 200..399) connection.inputStream else connection.errorStream
        val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val responseHeaders = connection.headerFields.entries.mapNotNull { (name, values) ->
            name?.let { it to values?.firstOrNull().orEmpty() }
        }.toMap()
        connection.disconnect()
        HttpResponse(status, responseBody, responseHeaders)
    }
}

internal fun HttpResponse.toNetworkError(): NetworkError {
    val error = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
    val retryAfter = error?.optLong("retryAfterSeconds", -1)?.takeIf { it >= 0 }
        ?: headers["Retry-After"]?.toLongOrNull()
    return NetworkError(
        code = error?.optString("code")?.takeIf(String::isNotBlank) ?: "HTTP_$status",
        userMessage = error?.optString("message")?.takeIf(String::isNotBlank) ?: "Nie można połączyć się z serwerem.",
        retryable = if (error?.has("retryable") == true) error.optBoolean("retryable") else (status == 408 || status == 429 || status >= 500),
        retryAfterSeconds = retryAfter,
    )
}
