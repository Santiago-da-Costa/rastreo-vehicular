package com.rastreo.vehicular.data

import com.rastreo.vehicular.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class RastreoRepository {
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
        return buildApi(baseUrl, token).me()
    }

    suspend fun listVehicles(baseUrl: String, token: String): List<Vehicle> {
        return buildApi(baseUrl, token).listVehicles()
    }

    suspend fun startTrip(
        baseUrl: String,
        token: String,
        vehicleId: Int,
        categoria: String,
    ): TripResponse {
        return buildApi(baseUrl, token).startTrip(TripStartRequest(vehicleId, categoria))
    }

    suspend fun sendTripPoint(
        baseUrl: String,
        token: String,
        tripId: Int,
        request: TripPointRequest,
    ) {
        buildApi(baseUrl, token).sendTripPoint(tripId, request)
    }

    suspend fun stopTrip(baseUrl: String, token: String, tripId: Int): TripResponse {
        return buildApi(baseUrl, token).stopTrip(tripId)
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
