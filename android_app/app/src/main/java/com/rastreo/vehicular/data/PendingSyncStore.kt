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

private val Context.pendingSyncDataStore by preferencesDataStore(name = "pending_sync")

data class PendingTripPointDraft(
    val tripId: Int,
    val latitude: Double,
    val longitude: Double,
    val timestamp: String,
    val accuracy: Float?,
    val speed: Float?,
    val sendType: String? = null,
)

data class PendingTripPoint(
    val sequence: Long,
    val tripId: Int,
    val latitude: Double,
    val longitude: Double,
    val timestamp: String,
    val accuracy: Float?,
    val speed: Float?,
    val sendType: String? = null,
)

data class PendingTripClose(
    val tripId: Int,
    val stopRequestedAt: String,
)

data class PendingSyncState(
    val pendingPoints: List<PendingTripPoint> = emptyList(),
    val pendingClose: PendingTripClose? = null,
    val activeTripId: Int? = null,
)

private data class StoredPendingSyncState(
    val pendingPoints: List<PendingTripPoint> = emptyList(),
    val pendingClose: PendingTripClose? = null,
    val activeTripId: Int? = null,
    val nextSequence: Long = 0,
)

class PendingSyncStore(private val context: Context) {
    private object Keys {
        val state = stringPreferencesKey("pending_sync_state")
    }

    private val gson = Gson()
    private val stateType = object : TypeToken<StoredPendingSyncState>() {}.type

    val pendingSyncState: Flow<PendingSyncState> = context.pendingSyncDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            decodeState(preferences[Keys.state]).toPublicState()
        }

    suspend fun getState(): PendingSyncState = pendingSyncState.first()

    suspend fun setActiveTripId(tripId: Int?) {
        editState { state ->
            state.copy(activeTripId = tripId)
        }
    }

    suspend fun enqueuePoint(point: PendingTripPointDraft): PendingTripPoint {
        var storedPoint: PendingTripPoint? = null
        editState { state ->
            val nextPoint = PendingTripPoint(
                sequence = state.nextSequence,
                tripId = point.tripId,
                latitude = point.latitude,
                longitude = point.longitude,
                timestamp = point.timestamp,
                accuracy = point.accuracy,
                speed = point.speed,
                sendType = point.sendType,
            )
            storedPoint = nextPoint
            state.copy(
                pendingPoints = (state.pendingPoints + nextPoint).sortedPendingPoints(),
                activeTripId = state.activeTripId ?: point.tripId,
                nextSequence = state.nextSequence + 1,
            )
        }
        return requireNotNull(storedPoint)
    }

    suspend fun removePoint(sequence: Long) {
        editState { state ->
            state.copy(
                pendingPoints = state.pendingPoints
                    .filterNot { it.sequence == sequence }
                    .sortedPendingPoints(),
            )
        }
    }

    suspend fun getPendingPointsForTrip(tripId: Int): List<PendingTripPoint> {
        return getState().pendingPoints
            .filter { it.tripId == tripId }
            .sortedPendingPoints()
    }

    suspend fun hasPendingPointsForTrip(tripId: Int): Boolean {
        return getState().pendingPoints.any { it.tripId == tripId }
    }

    suspend fun savePendingClose(tripId: Int, stopRequestedAt: String) {
        editState { state ->
            state.copy(
                pendingClose = PendingTripClose(
                    tripId = tripId,
                    stopRequestedAt = stopRequestedAt,
                ),
                activeTripId = tripId,
            )
        }
    }

    suspend fun clearPendingClose(tripId: Int? = null) {
        editState { state ->
            if (tripId != null && state.pendingClose?.tripId != tripId) {
                state
            } else {
                state.copy(pendingClose = null)
            }
        }
    }

    private suspend fun editState(
        transform: (StoredPendingSyncState) -> StoredPendingSyncState,
    ) {
        context.pendingSyncDataStore.edit { preferences ->
            val currentState = decodeState(preferences[Keys.state])
            preferences[Keys.state] = gson.toJson(transform(currentState), stateType)
        }
    }

    private fun decodeState(serialized: String?): StoredPendingSyncState {
        if (serialized.isNullOrBlank()) {
            return StoredPendingSyncState()
        }

        return runCatching {
            gson.fromJson<StoredPendingSyncState>(serialized, stateType) ?: StoredPendingSyncState()
        }.getOrDefault(StoredPendingSyncState())
    }

    private fun StoredPendingSyncState.toPublicState(): PendingSyncState {
        return PendingSyncState(
            pendingPoints = pendingPoints.sortedPendingPoints(),
            pendingClose = pendingClose,
            activeTripId = activeTripId,
        )
    }

    private fun List<PendingTripPoint>.sortedPendingPoints(): List<PendingTripPoint> {
        return sortedWith(compareBy<PendingTripPoint>({ it.timestamp }, { it.sequence }))
    }
}
