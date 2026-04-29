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
import java.time.Instant

private val Context.pendingSyncDataStore by preferencesDataStore(name = "pending_sync")

data class PendingTripPointDraft(
    val tripId: Int,
    val localTripId: String? = null,
    val clientPointId: String? = null,
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
    val localTripId: String? = null,
    val clientPointId: String? = null,
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

data class LocalTrip(
    val localTripId: String,
    val clientTripId: String,
    val remoteTripId: Int?,
    val vehicleId: Int,
    val categoria: String,
    val startTime: String,
    val endTime: String?,
    val status: String,
    val syncState: String,
)

sealed class ActiveTrackingRef {
    data class Remote(val tripId: Int, val localTripId: String?) : ActiveTrackingRef()
    data class Local(val localTripId: String) : ActiveTrackingRef()
}

data class PendingSyncState(
    val pendingPoints: List<PendingTripPoint> = emptyList(),
    val pendingClose: PendingTripClose? = null,
    val activeTripId: Int? = null,
    val localTrips: List<LocalTrip> = emptyList(),
    val activeLocalTripId: String? = null,
    val activeTrackingRef: ActiveTrackingRef? = null,
)

private data class StoredPendingSyncState(
    val pendingPoints: List<PendingTripPoint> = emptyList(),
    val pendingClose: PendingTripClose? = null,
    val activeTripId: Int? = null,
    val localTrips: List<LocalTrip> = emptyList(),
    val activeLocalTripId: String? = null,
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
            state.copy(
                activeTripId = tripId,
                activeLocalTripId = if (tripId == null) null else state.activeLocalTripId,
            )
        }
    }

    suspend fun upsertLocalTrip(localTrip: LocalTrip, makeActive: Boolean = true) {
        editState { state ->
            state.copy(
                localTrips = (state.localTrips.filterNot {
                    it.localTripId == localTrip.localTripId
                } + localTrip).sortedLocalTrips(),
                activeLocalTripId = if (makeActive) localTrip.localTripId else state.activeLocalTripId,
            )
        }
    }

    suspend fun updateLocalTripRemoteId(
        localTripId: String,
        remoteTripId: Int,
        syncState: String = LOCAL_TRIP_SYNC_CREATED_REMOTE,
    ) {
        editState { state ->
            state.copy(
                localTrips = state.localTrips.map { localTrip ->
                    if (localTrip.localTripId == localTripId) {
                        localTrip.copy(
                            remoteTripId = remoteTripId,
                            syncState = syncState,
                        )
                    } else {
                        localTrip
                    }
                }.sortedLocalTrips(),
                activeLocalTripId = localTripId,
            )
        }
    }

    suspend fun getLocalTrip(localTripId: String): LocalTrip? {
        return getState().localTrips.firstOrNull { it.localTripId == localTripId }
    }

    suspend fun getActiveLocalTrip(): LocalTrip? {
        val state = getState()
        return state.activeLocalTripId?.let { localTripId ->
            state.localTrips.firstOrNull { it.localTripId == localTripId }
        } ?: state.activeTripId?.let { tripId ->
            state.localTrips.firstOrNull {
                it.remoteTripId == tripId && it.status == LOCAL_TRIP_STATUS_ACTIVE
            }
        }
    }

    suspend fun updateLocalTrip(
        localTripId: String,
        transform: (LocalTrip) -> LocalTrip,
    ): LocalTrip? {
        var updatedLocalTrip: LocalTrip? = null
        editState { state ->
            state.copy(
                localTrips = state.localTrips.map { localTrip ->
                    if (localTrip.localTripId == localTripId) {
                        transform(localTrip).also { updatedLocalTrip = it }
                    } else {
                        localTrip
                    }
                }.sortedLocalTrips(),
            )
        }
        return updatedLocalTrip
    }

    suspend fun markActiveLocalTripClosed(remoteTripId: Int?, endTime: String) {
        editState { state ->
            val activeLocalTrip = state.findActiveLocalTrip(remoteTripId)
            state.copy(
                localTrips = state.localTrips.map { localTrip ->
                    if (localTrip.localTripId == activeLocalTrip?.localTripId) {
                        localTrip.copy(
                            endTime = endTime,
                            status = LOCAL_TRIP_STATUS_CLOSED,
                        )
                    } else {
                        localTrip
                    }
                }.sortedLocalTrips(),
                activeLocalTripId = null,
            )
        }
    }

    suspend fun markLocalTripClosedLocally(
        localTripId: String,
        endTime: String = Instant.now().toString(),
    ): LocalTrip? {
        var closedLocalTrip: LocalTrip? = null
        editState { state ->
            state.copy(
                localTrips = state.localTrips.map { localTrip ->
                    if (localTrip.localTripId == localTripId) {
                        localTrip.copy(
                            endTime = endTime,
                            status = LOCAL_TRIP_STATUS_CLOSED_LOCAL,
                            syncState = if (localTrip.remoteTripId == null) {
                                LOCAL_TRIP_SYNC_PENDING_CREATE
                            } else {
                                LOCAL_TRIP_SYNC_PENDING_CLOSE
                            },
                        ).also { closedLocalTrip = it }
                    } else {
                        localTrip
                    }
                }.sortedLocalTrips(),
                activeLocalTripId = state.activeLocalTripId.takeIf { it != localTripId },
            )
        }
        return closedLocalTrip
    }

    suspend fun removeLocalTrip(localTripId: String) {
        editState { state ->
            state.copy(
                localTrips = state.localTrips.filterNot { it.localTripId == localTripId }.sortedLocalTrips(),
                activeLocalTripId = state.activeLocalTripId.takeIf { it != localTripId },
            )
        }
    }

    suspend fun enqueuePoint(point: PendingTripPointDraft): PendingTripPoint {
        var storedPoint: PendingTripPoint? = null
        editState { state ->
            val nextPoint = PendingTripPoint(
                sequence = state.nextSequence,
                tripId = point.tripId,
                localTripId = point.localTripId,
                clientPointId = point.clientPointId,
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

    suspend fun getPendingPointsForLocalTrip(localTripId: String): List<PendingTripPoint> {
        return getState().pendingPoints
            .filter { it.localTripId == localTripId }
            .sortedPendingPoints()
    }

    suspend fun getLastPendingPointForTrip(tripId: Int): PendingTripPoint? {
        return getPendingPointsForTrip(tripId).lastOrNull()
    }

    suspend fun getLastPendingPointForLocalTrip(localTripId: String): PendingTripPoint? {
        return getPendingPointsForLocalTrip(localTripId).lastOrNull()
    }

    suspend fun hasPendingPointsForTrip(tripId: Int): Boolean {
        return getState().pendingPoints.any { it.tripId == tripId }
    }

    suspend fun hasPendingPointsForLocalTrip(localTripId: String): Boolean {
        return getState().pendingPoints.any { it.localTripId == localTripId }
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
            preferences[Keys.state] = gson.toJson(
                transform(currentState.normalized()),
                stateType,
            )
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
        val normalizedState = normalized()
        return PendingSyncState(
            pendingPoints = normalizedState.pendingPoints.sortedPendingPoints(),
            pendingClose = normalizedState.pendingClose,
            activeTripId = normalizedState.activeTripId,
            localTrips = normalizedState.localTrips.sortedLocalTrips(),
            activeLocalTripId = normalizedState.activeLocalTripId,
            activeTrackingRef = normalizedState.toActiveTrackingRef(),
        )
    }

    private fun StoredPendingSyncState.toActiveTrackingRef(): ActiveTrackingRef? {
        activeTripId?.let { tripId ->
            return ActiveTrackingRef.Remote(
                tripId = tripId,
                localTripId = activeLocalTripId,
            )
        }

        val activeLocalTrip = activeLocalTripId?.let { localTripId ->
            localTrips.orEmpty().firstOrNull {
                it.localTripId == localTripId &&
                    it.status == LOCAL_TRIP_STATUS_ACTIVE &&
                    it.remoteTripId == null
            }
        }

        return activeLocalTrip?.let { ActiveTrackingRef.Local(it.localTripId) }
    }

    private fun StoredPendingSyncState.normalized(): StoredPendingSyncState {
        val safePendingPoints = pendingPoints.orEmpty().sortedPendingPoints()
        val safeLocalTrips = localTrips.orEmpty().sortedLocalTrips()
        val safeState = copy(
            pendingPoints = safePendingPoints,
            localTrips = safeLocalTrips,
        )
        if (activeTripId == null) {
            return safeState
        }

        val activeLocalTrip = safeState.findActiveLocalTrip(activeTripId)
        if (activeLocalTrip != null) {
            return safeState.copy(
                activeLocalTripId = activeLocalTrip.localTripId,
            )
        }

        val migratedTrip = createMigratedLocalTrip(activeTripId)
        return safeState.copy(
            localTrips = (safeState.localTrips + migratedTrip).sortedLocalTrips(),
            activeLocalTripId = migratedTrip.localTripId,
        )
    }

    private fun StoredPendingSyncState.findActiveLocalTrip(remoteTripId: Int?): LocalTrip? {
        return localTrips.orEmpty().firstOrNull { it.localTripId == activeLocalTripId }
            ?: remoteTripId?.let { tripId ->
                localTrips.orEmpty().firstOrNull {
                    it.remoteTripId == tripId && it.status == LOCAL_TRIP_STATUS_ACTIVE
                }
            }
    }

    private fun List<PendingTripPoint>.sortedPendingPoints(): List<PendingTripPoint> {
        return sortedWith(compareBy<PendingTripPoint>({ it.timestamp }, { it.sequence }))
    }

    private fun List<LocalTrip>.sortedLocalTrips(): List<LocalTrip> {
        return sortedBy { it.startTime }
    }

    private fun createMigratedLocalTrip(activeTripId: Int): LocalTrip {
        val migratedId = "remote-$activeTripId"
        return LocalTrip(
            localTripId = migratedId,
            clientTripId = migratedId,
            remoteTripId = activeTripId,
            vehicleId = 0,
            categoria = "",
            startTime = Instant.EPOCH.toString(),
            endTime = null,
            status = LOCAL_TRIP_STATUS_ACTIVE,
            syncState = LOCAL_TRIP_SYNC_CREATED_REMOTE,
        )
    }

    companion object {
        const val LOCAL_TRIP_STATUS_ACTIVE = "ACTIVE"
        const val LOCAL_TRIP_STATUS_CLOSED = "CLOSED"
        const val LOCAL_TRIP_STATUS_CLOSED_LOCAL = "CLOSED_LOCAL"
        const val LOCAL_TRIP_SYNC_LOCAL_CREATED = "LOCAL_CREATED"
        const val LOCAL_TRIP_SYNC_CREATED_REMOTE = "CREATED_REMOTE"
        const val LOCAL_TRIP_SYNC_PENDING_CREATE = "PENDING_CREATE"
        const val LOCAL_TRIP_SYNC_PENDING_CLOSE = "PENDING_CLOSE"
        const val LOCAL_TRIP_SYNC_SYNCED = "SYNCED"
    }
}
