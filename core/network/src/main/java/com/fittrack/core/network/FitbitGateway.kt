package com.fittrack.core.network

import java.net.URI
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

enum class FitbitOperation { OAUTH_START, OAUTH_CALLBACK, SYNC }

enum class FitbitSyncMode { DAILY_METRICS, WORKOUT_HR }

enum class FitbitRemoteSyncStatus { PENDING, PARTIAL, SYNCED, FAILED }

data class FitbitSyncRequest(
    val mode: FitbitSyncMode,
    val workoutSessionId: String? = null,
)

data class FitbitGatewayError(
    val code: String,
    val userMessage: String,
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
)

data class FitbitDailyMetric(
    val date: String,
    val sleepMinutes: Int?,
    val restingHeartRate: Int?,
    val steps: Int?,
    val source: String,
)

data class FitbitCardioActivity(
    val sourceId: String,
    val kind: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val durationSeconds: Long?,
    val distanceMeters: Double?,
    val steps: Int?,
    val averageHeartRate: Int?,
)

data class FitbitHeartRateSample(
    val workoutSessionId: String,
    val sampledAt: Instant,
    val bpm: Int,
)

data class FitbitExerciseHeartRateSummary(
    val exerciseSessionId: String,
    val averageBpm: Double?,
    val maximumBpm: Int?,
    val recoveryDeltaBpm: Int?,
    val sampleCount: Int,
)

sealed interface FitbitGatewayState {
    data object Disconnected : FitbitGatewayState
    data object StartingOAuth : FitbitGatewayState
    data object AwaitingOAuthCallback : FitbitGatewayState
    data object Connected : FitbitGatewayState
    data class Syncing(val mode: FitbitSyncMode) : FitbitGatewayState
    data class Failure(val operation: FitbitOperation, val error: FitbitGatewayError) : FitbitGatewayState
}

sealed interface FitbitOAuthStartResult {
    data class Success(val authorizeUrl: String) : FitbitOAuthStartResult
    data class Failure(val error: FitbitGatewayError) : FitbitOAuthStartResult
}

sealed interface FitbitSyncResult {
    data class Success(
        val status: FitbitRemoteSyncStatus,
        val importedCounts: Map<String, Int>,
        val retryAfterSeconds: Long?,
        val dailyMetrics: List<FitbitDailyMetric> = emptyList(),
        val cardioActivities: List<FitbitCardioActivity> = emptyList(),
        val heartRateSamples: List<FitbitHeartRateSample> = emptyList(),
        val exerciseHeartRateSummaries: List<FitbitExerciseHeartRateSummary> = emptyList(),
    ) : FitbitSyncResult

    data class Failure(val error: FitbitGatewayError) : FitbitSyncResult
}

interface FitbitGateway {
    /** Process-local UI state. Fitbit tokens remain exclusively on the backend. */
    val state: StateFlow<FitbitGatewayState>

    suspend fun startOAuth(redirectUri: String): FitbitOAuthStartResult
    fun handleOAuthCallback(success: Boolean, reason: String? = null)
    suspend fun sync(request: FitbitSyncRequest): FitbitSyncResult
}

class SupabaseFitbitGateway(
    functionBaseUrl: String,
    private val authRepository: AuthRepository,
    private val http: HttpTransport = UrlConnectionTransport,
) : FitbitGateway {
    private val baseUrl = functionBaseUrl.trimEnd('/')
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow<FitbitGatewayState>(FitbitGatewayState.Disconnected)

    override val state: StateFlow<FitbitGatewayState> = _state.asStateFlow()

    init {
        require(baseUrl.isSafeHttpsUrl()) { "functionBaseUrl must be an absolute HTTPS URL" }
    }

    override suspend fun startOAuth(redirectUri: String): FitbitOAuthStartResult = operationMutex.withLock {
        if (!redirectUri.isValidRedirectUri()) {
            return@withLock oauthFailure(
                FitbitGatewayError(
                    code = "INVALID_REDIRECT_URI",
                    userMessage = "Nieprawidłowy adres powrotu autoryzacji Fitbit.",
                    retryable = false,
                ),
            )
        }

        val previousState = _state.value
        _state.value = FitbitGatewayState.StartingOAuth
        try {
            val response = when (
                val result = authenticatedPost(
                    path = "/fitbit-oauth-start",
                    body = JSONObject().put("redirectUri", redirectUri).toString(),
                )
            ) {
                is AuthenticatedPostResult.Failure -> return@withLock oauthFailure(result.error)
                is AuthenticatedPostResult.Success -> result.response
            }
            if (response.status !in 200..299) return@withLock oauthFailure(response.toFitbitError())

            val authorizeUrl = runCatching { parseFitbitAuthorizeUrl(response.body) }.getOrElse {
                return@withLock oauthFailure(invalidResponseError())
            }
            _state.value = FitbitGatewayState.AwaitingOAuthCallback
            FitbitOAuthStartResult.Success(authorizeUrl)
        } catch (cancelled: CancellationException) {
            _state.value = previousState
            throw cancelled
        } catch (_: Exception) {
            oauthFailure(unavailableError())
        }
    }

    override fun handleOAuthCallback(success: Boolean, reason: String?) {
        _state.value = if (success) {
            FitbitGatewayState.Connected
        } else {
            FitbitGatewayState.Failure(
                operation = FitbitOperation.OAUTH_CALLBACK,
                error = callbackError(reason),
            )
        }
    }

    override suspend fun sync(request: FitbitSyncRequest): FitbitSyncResult = operationMutex.withLock {
        validate(request)?.let { return@withLock syncFailure(it) }

        val previousState = _state.value
        _state.value = FitbitGatewayState.Syncing(request.mode)
        try {
            val body = JSONObject().put("mode", request.mode.name).apply {
                if (request.mode == FitbitSyncMode.WORKOUT_HR) {
                    put("workoutSessionId", request.workoutSessionId)
                }
            }.toString()
            val response = when (val result = authenticatedPost("/fitbit-sync", body)) {
                is AuthenticatedPostResult.Failure -> return@withLock syncFailure(result.error)
                is AuthenticatedPostResult.Success -> result.response
            }
            if (response.status !in 200..299) return@withLock syncFailure(response.toFitbitError())

            val parsed = runCatching { parseFitbitSyncResponse(response.body) }.getOrElse {
                return@withLock syncFailure(invalidResponseError())
            }
            _state.value = if (parsed.status == FitbitRemoteSyncStatus.FAILED) {
                FitbitGatewayState.Failure(
                    FitbitOperation.SYNC,
                    FitbitGatewayError(
                        code = "FITBIT_SYNC_FAILED",
                        userMessage = "Synchronizacja Fitbit nie została ukończona.",
                        retryable = parsed.retryAfterSeconds != null,
                        retryAfterSeconds = parsed.retryAfterSeconds,
                    ),
                )
            } else {
                FitbitGatewayState.Connected
            }
            parsed
        } catch (cancelled: CancellationException) {
            _state.value = previousState
            throw cancelled
        } catch (_: Exception) {
            syncFailure(unavailableError())
        }
    }

    private suspend fun authenticatedPost(path: String, body: String): AuthenticatedPostResult {
        var session = authRepository.currentSession()
            ?: return AuthenticatedPostResult.Failure(authRequiredError())
        var response = post(path, body, session)
        if (response.status == 401) {
            session = when (val refresh = authRepository.refreshSession()) {
                is AuthResult.Success -> refresh.session
                is AuthResult.Failure -> return AuthenticatedPostResult.Failure(
                    FitbitGatewayError(
                        code = "AUTH_REFRESH_FAILED",
                        userMessage = "Sesja wygasła. Zaloguj się ponownie.",
                        retryable = refresh.error.retryable,
                        retryAfterSeconds = refresh.error.retryAfterSeconds,
                    ),
                )
            }
            response = post(path, body, session)
        }
        return AuthenticatedPostResult.Success(response)
    }

    private suspend fun post(path: String, body: String, session: AuthSession): HttpResponse = http.post(
        url = "$baseUrl$path",
        headers = mapOf(
            "Authorization" to "Bearer ${session.accessToken}",
            "Content-Type" to "application/json",
        ),
        body = body,
    )

    private fun oauthFailure(error: FitbitGatewayError): FitbitOAuthStartResult.Failure {
        _state.value = FitbitGatewayState.Failure(FitbitOperation.OAUTH_START, error)
        return FitbitOAuthStartResult.Failure(error)
    }

    private fun syncFailure(error: FitbitGatewayError): FitbitSyncResult.Failure {
        _state.value = FitbitGatewayState.Failure(FitbitOperation.SYNC, error)
        return FitbitSyncResult.Failure(error)
    }

    private sealed interface AuthenticatedPostResult {
        data class Success(val response: HttpResponse) : AuthenticatedPostResult
        data class Failure(val error: FitbitGatewayError) : AuthenticatedPostResult
    }
}

internal fun parseFitbitAuthorizeUrl(body: String): String {
    val url = JSONObject(body).getString("authorizeUrl")
    require(url.isSafeHttpsUrl()) { "authorizeUrl must be HTTPS" }
    return url
}

internal fun parseFitbitSyncResponse(body: String): FitbitSyncResult.Success {
    val root = JSONObject(body)
    val status = FitbitRemoteSyncStatus.valueOf(root.getString("status"))
    val countsJson = root.getJSONObject("importedCounts")
    val counts = buildMap {
        val keys = countsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val count = countsJson.getInt(key)
            require(count >= 0) { "Imported count cannot be negative" }
            put(key, count)
        }
    }
    val retryAfter = if (!root.has("retryAfter") || root.isNull("retryAfter")) {
        null
    } else {
        root.getLong("retryAfter").also { require(it >= 0) }
    }
    val data = root.optJSONObject("data")
    return FitbitSyncResult.Success(
        status = status,
        importedCounts = counts,
        retryAfterSeconds = retryAfter,
        dailyMetrics = data?.objectList("dailyMetrics") { item ->
            FitbitDailyMetric(
                date = item.getString("date").also { require(DATE_PATTERN.matches(it)) },
                sleepMinutes = item.nullableInt("sleepMinutes")?.also { require(it >= 0) },
                restingHeartRate = item.nullableInt("restingHeartRate")?.also { require(it > 0) },
                steps = item.nullableInt("steps")?.also { require(it >= 0) },
                source = item.getString("source").also { require(it == "FITBIT") },
            )
        }.orEmpty(),
        cardioActivities = data?.objectList("cardioActivities") { item ->
            FitbitCardioActivity(
                sourceId = item.requiredNonBlankString("sourceId"),
                kind = item.requiredNonBlankString("kind"),
                startedAt = item.requiredInstant("startedAt"),
                endedAt = item.nullableInstant("endedAt"),
                durationSeconds = item.nullableLong("durationSeconds")?.also { require(it >= 0) },
                distanceMeters = item.nullableDouble("distanceMeters")?.also { require(it >= 0) },
                steps = item.nullableInt("steps")?.also { require(it >= 0) },
                averageHeartRate = item.nullableInt("averageHeartRate")?.also { require(it > 0) },
            )
        }.orEmpty(),
        heartRateSamples = data?.objectList("heartRateSamples") { item ->
            FitbitHeartRateSample(
                workoutSessionId = item.requiredNonBlankString("workoutSessionId"),
                sampledAt = item.requiredInstant("sampledAt"),
                bpm = item.getInt("bpm").also { require(it > 0) },
            )
        }.orEmpty(),
        exerciseHeartRateSummaries = data?.objectList("exerciseHeartRateSummaries") { item ->
            FitbitExerciseHeartRateSummary(
                exerciseSessionId = item.requiredNonBlankString("exerciseSessionId"),
                averageBpm = item.nullableDouble("averageBpm")?.also { require(it > 0) },
                maximumBpm = item.nullableInt("maximumBpm")?.also { require(it > 0) },
                recoveryDeltaBpm = item.nullableInt("recoveryDeltaBpm"),
                sampleCount = item.getInt("sampleCount").also { require(it >= 0) },
            )
        }.orEmpty(),
    )
}

private inline fun <T> JSONObject.objectList(name: String, transform: (JSONObject) -> T): List<T> {
    if (!has(name) || isNull(name)) return emptyList()
    val array = getJSONArray(name)
    return List(array.length()) { index -> transform(array.getJSONObject(index)) }
}

private fun JSONObject.requiredNonBlankString(name: String): String =
    getString(name).also { require(it.isNotBlank()) }

private fun JSONObject.nullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else getInt(name)

private fun JSONObject.nullableLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else getLong(name)

private fun JSONObject.nullableDouble(name: String): Double? =
    if (!has(name) || isNull(name)) null else getDouble(name)

private fun JSONObject.requiredInstant(name: String): Instant = Instant.parse(getString(name))

private fun JSONObject.nullableInstant(name: String): Instant? =
    if (!has(name) || isNull(name)) null else requiredInstant(name)

private fun validate(request: FitbitSyncRequest): FitbitGatewayError? = when {
    request.mode == FitbitSyncMode.WORKOUT_HR && request.workoutSessionId.isNullOrBlank() ->
        badRequestError("Wybierz trening do synchronizacji tętna.")
    request.mode == FitbitSyncMode.DAILY_METRICS && request.workoutSessionId != null ->
        badRequestError("Synchronizacja danych dziennych nie przyjmuje identyfikatora treningu.")
    else -> null
}

private fun String.isSafeHttpsUrl(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

private fun String.isValidRedirectUri(): Boolean = runCatching {
    val uri = URI(this)
    uri.isAbsolute && uri.scheme.isNotBlank() && uri.userInfo == null
}.getOrDefault(false)

private fun HttpResponse.toFitbitError(): FitbitGatewayError {
    val errorJson = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
    val serverCode = errorJson?.optString("code").orEmpty().takeIf(KNOWN_FITBIT_ERROR_CODES::contains)
    val code = serverCode ?: "HTTP_$status"
    val retryAfter = errorJson?.optionalNonNegativeLong("retryAfterSeconds")
        ?: headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.toLongOrNull()?.takeIf { it >= 0 }
    val retryable = if (errorJson?.has("retryable") == true) {
        errorJson.optBoolean("retryable")
    } else {
        status == 408 || status == 429 || status >= 500
    }
    return FitbitGatewayError(code, safeUserMessage(code), retryable, retryAfter)
}

private fun JSONObject.optionalNonNegativeLong(name: String): Long? =
    if (!has(name) || isNull(name)) null else optLong(name, -1L).takeIf { it >= 0 }

private fun safeUserMessage(code: String): String = when (code) {
    "FITBIT_NOT_CONNECTED" -> "Połącz konto Fitbit, aby pobrać dane."
    "FITBIT_UNAUTHORIZED", "FITBIT_INVALID_OAUTH_STATE", "FITBIT_TOKEN_EXCHANGE_FAILED" ->
        "Autoryzacja Fitbit wygasła. Połącz konto ponownie."
    "FITBIT_RATE_LIMITED" -> "Fitbit ograniczył liczbę żądań. Synchronizacja zostanie ponowiona później."
    "FITBIT_BAD_REQUEST" -> "Nie można wykonać tej synchronizacji Fitbit."
    "FITBIT_UPSTREAM_ERROR" -> "Fitbit jest chwilowo niedostępny."
    else -> "Nie udało się połączyć z Fitbit."
}

private fun callbackError(reason: String?): FitbitGatewayError {
    val code = when (reason?.lowercase()) {
        "denied" -> "OAUTH_DENIED"
        "missing_params" -> "OAUTH_MISSING_PARAMS"
        "invalid_state" -> "OAUTH_INVALID_STATE"
        "state_expired" -> "OAUTH_STATE_EXPIRED"
        "internal" -> "OAUTH_INTERNAL"
        else -> "OAUTH_CALLBACK_FAILED"
    }
    val message = if (code == "OAUTH_DENIED") {
        "Autoryzacja Fitbit została anulowana."
    } else {
        "Nie udało się połączyć konta Fitbit. Spróbuj ponownie."
    }
    return FitbitGatewayError(code, message, retryable = code == "OAUTH_INTERNAL")
}

private fun invalidResponseError() = FitbitGatewayError(
    code = "INVALID_FITBIT_RESPONSE",
    userMessage = "Serwer zwrócił nieprawidłową odpowiedź Fitbit.",
    retryable = true,
)

private fun unavailableError() = FitbitGatewayError(
    code = "FITBIT_UNAVAILABLE",
    userMessage = "Nie można teraz połączyć się z Fitbit.",
    retryable = true,
)

private fun authRequiredError() = FitbitGatewayError(
    code = "AUTH_REQUIRED",
    userMessage = "Zaloguj się ponownie, aby połączyć Fitbit.",
    retryable = false,
)

private fun badRequestError(message: String) = FitbitGatewayError(
    code = "FITBIT_BAD_REQUEST",
    userMessage = message,
    retryable = false,
)

private val KNOWN_FITBIT_ERROR_CODES = setOf(
    "FITBIT_UNAUTHORIZED",
    "FITBIT_NOT_CONNECTED",
    "FITBIT_INVALID_OAUTH_STATE",
    "FITBIT_RATE_LIMITED",
    "FITBIT_UPSTREAM_ERROR",
    "FITBIT_BAD_REQUEST",
    "FITBIT_TOKEN_EXCHANGE_FAILED",
)

private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
