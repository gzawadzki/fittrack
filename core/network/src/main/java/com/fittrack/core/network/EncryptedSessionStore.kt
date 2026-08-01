package com.fittrack.core.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Uses a key held by Android Keystore. The APK contains no token, password, or server secret.
 * Keystore invalidation deliberately signs the user out instead of trying to recover credentials.
 */
class EncryptedSessionStore(context: Context) : SessionStore {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun read(): AuthSession? = runCatching {
        val access = preferences.getString(ACCESS_TOKEN, null) ?: return null
        val refresh = preferences.getString(REFRESH_TOKEN, null) ?: return null
        val userId = preferences.getString(USER_ID, null) ?: return null
        AuthSession(access, refresh, preferences.getLong(EXPIRES_AT, 0L), userId)
    }.getOrElse {
        clear()
        null
    }

    override fun write(session: AuthSession) {
        preferences.edit()
            .putString(ACCESS_TOKEN, session.accessToken)
            .putString(REFRESH_TOKEN, session.refreshToken)
            .putLong(EXPIRES_AT, session.expiresAtEpochSeconds)
            .putString(USER_ID, session.userId)
            .commit()
    }

    override fun clear() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val FILE_NAME = "fittrack.secure.session"
        const val ACCESS_TOKEN = "access_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val EXPIRES_AT = "expires_at"
        const val USER_ID = "user_id"
    }
}
