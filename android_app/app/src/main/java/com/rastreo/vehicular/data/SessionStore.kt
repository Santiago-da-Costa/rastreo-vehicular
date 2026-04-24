package com.rastreo.vehicular.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "rastreo_session")

data class SessionData(
    val token: String = "",
    val refreshToken: String = "",
    val baseUrl: String = "",
)

class SessionStore(private val context: Context) {
    private object Keys {
        val token = stringPreferencesKey("token")
        val refreshToken = stringPreferencesKey("refresh_token")
        val baseUrl = stringPreferencesKey("base_url")
    }

    val sessionData: Flow<SessionData> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            SessionData(
                token = preferences[Keys.token].orEmpty(),
                refreshToken = preferences[Keys.refreshToken].orEmpty(),
                baseUrl = preferences[Keys.baseUrl].orEmpty(),
            )
        }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.token] = token
        }
    }

    suspend fun saveRefreshToken(refreshToken: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.refreshToken] = refreshToken
        }
    }

    suspend fun saveAuthTokens(
        accessToken: String,
        refreshToken: String?,
    ) {
        context.dataStore.edit { preferences ->
            preferences[Keys.token] = accessToken
            if (refreshToken.isNullOrBlank()) {
                preferences.remove(Keys.refreshToken)
            } else {
                preferences[Keys.refreshToken] = refreshToken
            }
        }
    }

    suspend fun saveBaseUrl(baseUrl: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.baseUrl] = baseUrl
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(Keys.token)
            preferences.remove(Keys.refreshToken)
        }
    }
}
