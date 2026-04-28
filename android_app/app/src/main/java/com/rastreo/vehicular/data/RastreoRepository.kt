package com.rastreo.vehicular.data

import com.rastreo.vehicular.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException

class RastreoRepository(
    private val sessionStore: SessionStore,
) {
    private val refreshMutex = Mutex()

    private fun buildApi(baseUrl: String, token: String?): RastreoApi {
        val authInterceptor = Interceptor { chain ->
            val requestBuilder = chain.request().newBuilder()
            if (!token.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }
            chain.proceed(requestBuilder.build())
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(RastreoApi::class.java)
    }

    suspend fun login(baseUrl: String, username: String, password: String): TokenResponse {
        return buildApi(baseUrl, token = null).login(LoginRequest(username, password))
    }

    suspend fun me(baseUrl: String, token: String): UserMeResponse {
        return executeAuthenticated(baseUrl, token) { api ->
            api.me()
        }
    }

    suspend fun listVehicles(baseUrl: String, token: String): List<Vehicle> {
        return executeAuthenticated(baseUrl, token) { api ->
            api.listVehicles()
        }
    }

    suspend fun startTrip(
        baseUrl: String,
        token: String,
        vehicleId: Int,
        categoria: String,
        clientTripId: String? = null,
    ): TripResponse {
        return executeAuthenticated(baseUrl, token) { api ->
            api.startTrip(TripStartRequest(vehicleId, categoria, clientTripId))
        }
    }

    suspend fun sendTripPoint(
        baseUrl: String,
        token: String,
        tripId: Int,
        request: TripPointRequest,
    ) {
        executeAuthenticated(baseUrl, token) { api ->
            api.sendTripPoint(tripId, request)
        }
    }

    suspend fun stopTrip(baseUrl: String, token: String, tripId: Int): TripResponse {
        return executeAuthenticated(baseUrl, token) { api ->
            api.stopTrip(tripId)
        }
    }

    private suspend fun <T> executeAuthenticated(
        baseUrl: String,
        token: String,
        call: suspend (RastreoApi) -> T,
    ): T {
        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
        val sessionToken = sessionStore.sessionData.first().token
        val initialToken = sessionToken.ifBlank { token }

        return try {
            call(buildApi(normalizedBaseUrl, initialToken))
        } catch (error: Throwable) {
            if (!isUnauthorizedError(error)) {
                throw error
            }

            val refreshed = refreshSessionIfPossible(
                baseUrl = normalizedBaseUrl,
                failedAccessToken = initialToken,
            )

            if (!refreshed) {
                throw error
            }

            val refreshedToken = sessionStore.sessionData.first().token
            if (refreshedToken.isBlank()) {
                throw error
            }

            call(buildApi(normalizedBaseUrl, refreshedToken))
        }
    }

    private suspend fun refreshSessionIfPossible(
        baseUrl: String,
        failedAccessToken: String,
    ): Boolean {
        return refreshMutex.withLock {
            val currentSession = sessionStore.sessionData.first()
            if (currentSession.token.isNotBlank() && currentSession.token != failedAccessToken) {
                return@withLock true
            }

            val refreshToken = currentSession.refreshToken
            if (refreshToken.isBlank()) {
                return@withLock false
            }

            val refreshResult = runCatching {
                buildApi(baseUrl, token = null).refresh(RefreshTokenRequest(refreshToken))
            }

            val refreshedTokens = refreshResult.getOrNull()
            if (refreshedTokens != null) {
                sessionStore.saveAuthTokens(
                    accessToken = refreshedTokens.accessToken,
                    refreshToken = refreshedTokens.refreshToken ?: refreshToken,
                )
                return@withLock true
            }

            val refreshError = refreshResult.exceptionOrNull()
            if (isUnauthorizedError(refreshError)) {
                sessionStore.clearSession()
                return@withLock false
            }

            throw RefreshTransientException(
                message = refreshError?.message ?: "No se pudo refrescar la sesion.",
                cause = refreshError,
            )
        }
    }

    private fun isUnauthorizedError(error: Throwable?): Boolean {
        return error is HttpException && error.code() == 401
    }

    companion object {
        fun productionUrl(): String = BuildConfig.DEFAULT_PRODUCTION_API_URL

        fun localUrl(): String = BuildConfig.DEFAULT_LOCAL_API_URL

        fun normalizeBaseUrl(url: String): String {
            val trimmed = url.trim()
            require(trimmed.isNotEmpty()) { "La base URL no puede quedar vacia" }
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }
    }
}

class RefreshTransientException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
