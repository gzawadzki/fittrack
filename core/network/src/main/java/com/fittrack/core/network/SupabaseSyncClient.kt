package com.fittrack.core.network

import org.json.JSONObject

class SupabaseSyncClient(
    private val functionBaseUrl: String,
    private val authRepository: AuthRepository,
    private val http: HttpTransport = UrlConnectionTransport,
) : SyncClient {
    override suspend fun sync(request: SyncBatchRequest): SyncResult {
        var session = authRepository.currentSession()
            ?: return SyncResult.Failure(NetworkError("NO_SESSION", "Zaloguj się ponownie.", false))
        var response = post(session, request)
        if (response.status == 401) {
            when (val refresh = authRepository.refreshSession()) {
                is AuthResult.Failure -> return SyncResult.Failure(refresh.error)
                is AuthResult.Success -> session = refresh.session
            }
            response = post(session, request) // same batchId makes this retry idempotent server-side
        }
        if (response.status !in 200..299) return SyncResult.Failure(response.toNetworkError())
        return runCatching { SyncResult.Success(parseSyncResponse(response.body)) }.getOrElse {
            SyncResult.Failure(NetworkError("INVALID_SYNC_RESPONSE", "Serwer zwrócił nieprawidłową odpowiedź.", true))
        }
    }

    private suspend fun post(session: AuthSession, request: SyncBatchRequest) = http.post(
        "${functionBaseUrl.trimEnd('/')}/sync-batch",
        mapOf("Authorization" to "Bearer ${session.accessToken}", "Content-Type" to "application/json"),
        request.toJson(),
    )
}
