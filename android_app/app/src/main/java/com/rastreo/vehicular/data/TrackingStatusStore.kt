package com.rastreo.vehicular.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.trackingStatusDataStore by preferencesDataStore(name = "tracking_status")

data class TrackingStatus(
    val serviceRunning: Boolean = false,
    val trackingActive: Boolean = false,
    val currentTripId: Int? = null,
    val category: String = "",
    val trackingStartedAt: String = "Sin dato",
    val loopStartedAt: String = "Sin dato",
    val loopFinishedAt: String = "Sin dato",
    val loopDurationMs: Long? = null,
    val lastReadAttemptAt: String = "Sin intento",
    val gpsReadStartedAt: String = "Sin dato",
    val gpsReadFinishedAt: String = "Sin dato",
    val gpsReadDurationMs: Long? = null,
    val gpsReadCount: Int = 0,
    val gpsNullCount: Int = 0,
    val gpsSlowReadCount: Int = 0,
    val gpsVerySlowReadCount: Int = 0,
    val lastLocationAt: String = "Sin dato",
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val lastAccuracy: Float? = null,
    val lastSpeed: Float? = null,
    val lastLocationProvider: String = "No disponible",
    val lastLocationAgeMs: Long? = null,
    val lastAcceptedLatitude: Double? = null,
    val lastAcceptedLongitude: Double? = null,
    val lastAcceptedAccuracy: Float? = null,
    val lastSendAttemptAt: String = "Sin intento",
    val lastSuccessfulSendAt: String = "Sin dato",
    val lastSendFailureAt: String = "Sin dato",
    val sendAttemptsCount: Int = 0,
    val successfulSendsCount: Int = 0,
    val failedSendsCount: Int = 0,
    val rejectedPositionsCount: Int = 0,
    val lastRejectedAt: String = "Sin dato",
    val lastGpsDiscardReason: String = "Sin descarte",
    val lastSendType: String = "Sin envio",
    val lastOperationalMessage: String = "Esperando accion.",
    val lastFilterElapsedSeconds: Long? = null,
    val lastFilterRequiredDistanceMeters: Double? = null,
    val lastFilterActualDistanceMeters: Double? = null,
    val secondsSinceLastAcceptedPoint: Long? = null,
    val distanceMinimumDiscardCountSinceLastAccepted: Int = 0,
)

class TrackingStatusStore(private val context: Context) {
    private object Keys {
        val state = stringPreferencesKey("tracking_status_state")
    }

    private val gson = Gson()
    private val stateType = object : TypeToken<TrackingStatus>() {}.type

    val trackingStatus: Flow<TrackingStatus> = context.trackingStatusDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            decodeState(preferences[Keys.state])
        }

    suspend fun getState(): TrackingStatus = trackingStatus.first()

    suspend fun updateStatus(transform: (TrackingStatus) -> TrackingStatus) {
        context.trackingStatusDataStore.edit { preferences ->
            val currentState = decodeState(preferences[Keys.state])
            preferences[Keys.state] = gson.toJson(transform(currentState), stateType)
        }
    }

    suspend fun replaceStatus(state: TrackingStatus) {
        context.trackingStatusDataStore.edit { preferences ->
            preferences[Keys.state] = gson.toJson(state, stateType)
        }
    }

    private fun decodeState(serialized: String?): TrackingStatus {
        if (serialized.isNullOrBlank()) {
            return TrackingStatus()
        }

        return runCatching {
            gson.fromJson<TrackingStatus>(serialized, stateType) ?: TrackingStatus()
        }.getOrDefault(TrackingStatus())
    }
}
