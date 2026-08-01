package com.fittrack.core.network

/** A Supabase access token. Never write this value to logs or analytics. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val userId: String,
)

sealed interface AuthResult {
    data class Success(val session: AuthSession) : AuthResult
    data class Failure(val error: NetworkError) : AuthResult
}

data class NetworkError(
    val code: String,
    val userMessage: String,
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
)

interface AuthRepository {
    suspend fun signIn(email: String, password: CharArray): AuthResult
    suspend fun refreshSession(): AuthResult
    suspend fun signOut()
    suspend fun currentSession(): AuthSession?
}

interface SessionStore {
    fun read(): AuthSession?
    fun write(session: AuthSession)
    fun clear()
}
