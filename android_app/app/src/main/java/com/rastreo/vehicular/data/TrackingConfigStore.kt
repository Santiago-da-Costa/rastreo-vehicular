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

private val Context.trackingConfigDataStore by preferencesDataStore(name = "tracking_config")

data class TrackingConfig(
    val gpsObservationIntervalMs: Long = TrackingConfigStore.DEFAULT_GPS_OBSERVATION_INTERVAL_MS,
    val evaluationIntervalMs: Long = TrackingConfigStore.DEFAULT_EVALUATION_INTERVAL_MS,
)

class TrackingConfigStore(private val context: Context) {
    private object Keys {
        val state = stringPreferencesKey("tracking_config_state")
    }

    private val gson = Gson()
    private val stateType = object : TypeToken<TrackingConfig>() {}.type

    val trackingConfig: Flow<TrackingConfig> = context.trackingConfigDataStore.data
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

    suspend fun getState(): TrackingConfig = trackingConfig.first()

    suspend fun setGpsObservationIntervalMs(value: Long) {
        updateConfig { state ->
            state.copy(gpsObservationIntervalMs = normalizeInterval(value))
        }
    }

    suspend fun setEvaluationIntervalMs(value: Long) {
        updateConfig { state ->
            state.copy(evaluationIntervalMs = normalizeInterval(value))
        }
    }

    suspend fun resetDefaults() {
        replaceConfig(TrackingConfig())
    }

    private suspend fun updateConfig(transform: (TrackingConfig) -> TrackingConfig) {
        context.trackingConfigDataStore.edit { preferences ->
            val currentState = decodeState(preferences[Keys.state])
            preferences[Keys.state] = gson.toJson(transform(currentState), stateType)
        }
    }

    private suspend fun replaceConfig(config: TrackingConfig) {
        context.trackingConfigDataStore.edit { preferences ->
            preferences[Keys.state] = gson.toJson(config.normalized(), stateType)
        }
    }

    private fun decodeState(serialized: String?): TrackingConfig {
        if (serialized.isNullOrBlank()) {
            return TrackingConfig()
        }

        return runCatching {
            (gson.fromJson<TrackingConfig>(serialized, stateType) ?: TrackingConfig()).normalized()
        }.getOrDefault(TrackingConfig())
    }

    private fun TrackingConfig.normalized(): TrackingConfig {
        return copy(
            gpsObservationIntervalMs = normalizeInterval(gpsObservationIntervalMs),
            evaluationIntervalMs = normalizeInterval(evaluationIntervalMs),
        )
    }

    private fun normalizeInterval(value: Long): Long {
        return value.coerceAtLeast(MIN_INTERVAL_MS)
    }

    companion object {
        const val DEFAULT_GPS_OBSERVATION_INTERVAL_MS = 5_000L
        const val DEFAULT_EVALUATION_INTERVAL_MS = 5_000L
        const val MIN_INTERVAL_MS = 1_000L
    }
}
