package com.rastreo.vehicular.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rastreo.vehicular.data.PendingSyncStore
import com.rastreo.vehicular.data.PendingTripPointDraft
import com.rastreo.vehicular.BuildConfig
import com.rastreo.vehicular.data.RefreshTransientException
import com.rastreo.vehicular.data.RastreoRepository
import com.rastreo.vehicular.data.SessionStore
import com.rastreo.vehicular.data.TripPointRequest
import com.rastreo.vehicular.data.UserMeResponse
import com.rastreo.vehicular.data.Vehicle
import com.rastreo.vehicular.location.LocationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import retrofit2.HttpException

data class UiState(
    val isBootstrapping: Boolean = true,
    val isBusy: Boolean = false,
    val currentUser: UserMeResponse? = null,
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicleId: Int? = null,
    val category: String = "trabajo",
    val currentTripId: Int? = null,
    val isTracking: Boolean = false,
    val isSessionInvalid: Boolean = false,
    val pendingTripClose: Boolean = false,
    val pendingPointCount: Int = 0,
    val pendingQueueTripId: Int? = null,
    val pendingCloseTripId: Int? = null,
    val lastSyncAttemptAt: String = "Sin intentos",
    val lastSyncError: String = "Sin error",
    val queuedPointsUploadedCount: Int = 0,
    val syncFailureCount: Int = 0,
    val autoCloseRetryActive: Boolean = false,
    val autoCloseRetryStatus: String = "Sin cierre pendiente",
    val autoCloseRetryAttemptCount: Int = 0,
    val refreshAvailable: Boolean = false,
    val lastLocationText: String = "Sin dato",
    val lastAttemptText: String = "Sin intento",
    val lastLocationReadAttemptAt: String = "Sin intento",
    val lastLocationObtainedAt: String = "Sin dato",
    val lastSuccessfulSendAt: String = "Sin dato",
    val lastFailedSendAt: String = "Sin dato",
    val sendAttemptCount: Int = 0,
    val sendSuccessCount: Int = 0,
    val sendFailureCount: Int = 0,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val lastAccuracy: Float? = null,
    val lastSpeed: Float? = null,
    val lastLocationTimestamp: String = "Sin dato",
    val lastDiscardReason: String = "Sin descarte",
    val lastFilterElapsedSeconds: Long? = null,
    val lastFilterRequiredDistanceMeters: Double? = null,
    val lastFilterActualDistanceMeters: Double? = null,
    val secondsSinceLastAcceptedPoint: Long? = null,
    val distanceMinimumDiscardCountSinceLastAccepted: Int = 0,
    val lastSendType: String = "Sin envio",
    val operationMessage: String = "Esperando accion.",
    val lastErrorMessage: String = "",
    val hasLocationPermission: Boolean = false,
    val statusMessage: String = "",
    val baseUrl: String = RastreoRepository.productionUrl(),
    val baseUrlDraft: String = RastreoRepository.productionUrl(),
)

class AppViewModel(
    private val sessionStore: SessionStore,
    private val pendingSyncStore: PendingSyncStore,
    private val repository: RastreoRepository,
    private val locationRepository: LocationRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        UiState(hasLocationPermission = locationRepository.hasLocationPermission())
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var trackingJob: Job? = null
    private var pendingCloseRetryJob: Job? = null
    private var lastAcceptedPoint: AcceptedPoint? = null
    private var distanceMinimumDiscardCountSinceLastAccepted = 0
    private val pendingCloseSyncMutex = Mutex()

    fun bootstrap() {
        viewModelScope.launch {
            val session = sessionStore.sessionData.first()
            val baseUrl = session.baseUrl.ifBlank { RastreoRepository.productionUrl() }
            _uiState.update {
                it.copy(
                    isBootstrapping = true,
                    baseUrl = baseUrl,
                    baseUrlDraft = baseUrl,
                    hasLocationPermission = locationRepository.hasLocationPermission(),
                )
            }

            if (session.token.isBlank()) {
                refreshPendingSyncUi()
                _uiState.update {
                    it.copy(
                        isBootstrapping = false,
                        isSessionInvalid = false,
                        refreshAvailable = session.refreshToken.isNotBlank(),
                        statusMessage = "Sesion no iniciada.",
                        operationMessage = "Sesion no iniciada.",
                    )
                }
                return@launch
            }

            val restoreResult = runCatching {
                val user = repository.me(baseUrl, session.token)
                val vehicles = repository.listVehicles(baseUrl, session.token)
                user to vehicles
            }

            val restoredSession = restoreResult.getOrNull()
            if (restoredSession != null) {
                val (user, vehicles) = restoredSession
                _uiState.update {
                    it.copy(
                        isBootstrapping = false,
                        currentUser = user,
                        vehicles = vehicles,
                        selectedVehicleId = chooseVehicle(user.vehicleIds, vehicles),
                        isSessionInvalid = false,
                        refreshAvailable = session.refreshToken.isNotBlank(),
                        statusMessage = "Sesion restaurada.",
                        operationMessage = "Sesion restaurada.",
                        lastErrorMessage = "",
                    )
                }
                refreshPendingSyncUi()
                syncPendingStateAfterAuthenticatedSession(baseUrl, session.token)
                ensurePendingCloseRetry()
            } else {
                val error = restoreResult.exceptionOrNull()
                if (isUnauthorizedError(error)) {
                    markSessionExpired(
                        statusMessage = "Sesion expirada. Inicia sesion nuevamente.",
                        operationMessage = "Sesion expirada.",
                        clearUserContext = true,
                    )
                } else {
                    val message = if (isNetworkError(error)) {
                        "No se pudo validar la sesion por problema de conexion."
                    } else {
                        error?.message ?: "No se pudo validar la sesion."
                    }
                    _uiState.update {
                        it.copy(
                            isBootstrapping = false,
                            currentUser = null,
                            vehicles = emptyList(),
                            selectedVehicleId = null,
                            isSessionInvalid = false,
                            statusMessage = message,
                            operationMessage = "Fallo validando sesion.",
                            lastErrorMessage = message,
                        )
                    }
                }
            }
        }
    }

    fun updateBaseUrlDraft(value: String) {
        _uiState.update { it.copy(baseUrlDraft = value) }
    }

    fun useProductionUrl() {
        _uiState.update { it.copy(baseUrlDraft = RastreoRepository.productionUrl()) }
    }

    fun useLocalUrl() {
        _uiState.update { it.copy(baseUrlDraft = RastreoRepository.localUrl()) }
    }

    fun saveBaseUrl() {
        viewModelScope.launch {
            runCatching {
                val normalized = RastreoRepository.normalizeBaseUrl(uiState.value.baseUrlDraft)
                sessionStore.saveBaseUrl(normalized)
                _uiState.update {
                    it.copy(
                        baseUrl = normalized,
                        baseUrlDraft = normalized,
                        statusMessage = "Base URL guardada.",
                        operationMessage = "Base URL guardada.",
                    )
                }
            }.onFailure {
                val message = it.message ?: "Base URL invalida."
                _uiState.update { state ->
                    state.copy(
                        statusMessage = message,
                        operationMessage = "Fallo guardando Base URL.",
                        lastErrorMessage = message,
                    )
                }
            }
        }
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            val message = "Username y password son obligatorios."
            _uiState.update {
                it.copy(
                    statusMessage = message,
                    operationMessage = "Login incompleto.",
                    lastErrorMessage = message,
                )
            }
            return
        }

        viewModelScope.launch {
            val baseUrl = uiState.value.baseUrlDraft
            _uiState.update { it.copy(isBusy = true, statusMessage = "Iniciando sesion...") }

            val loginResult = runCatching {
                val token = repository.login(baseUrl, username, password)
                sessionStore.saveBaseUrl(RastreoRepository.normalizeBaseUrl(baseUrl))
                sessionStore.saveAuthTokens(token.accessToken, token.refreshToken)
                val user = repository.me(baseUrl, token.accessToken)
                val vehicles = repository.listVehicles(baseUrl, token.accessToken)
                Triple(token, user, vehicles)
            }

            val loginData = loginResult.getOrNull()
            if (loginData != null) {
                val (tokenResponse, user, vehicles) = loginData
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isBootstrapping = false,
                        currentUser = user,
                        vehicles = vehicles,
                        selectedVehicleId = chooseVehicle(user.vehicleIds, vehicles),
                        baseUrl = RastreoRepository.normalizeBaseUrl(baseUrl),
                        baseUrlDraft = RastreoRepository.normalizeBaseUrl(baseUrl),
                        isSessionInvalid = false,
                        refreshAvailable = tokenResponse.refreshToken?.isNotBlank() == true,
                        statusMessage = "Sesion iniciada correctamente.",
                        operationMessage = "Sesion iniciada correctamente.",
                        lastErrorMessage = "",
                    )
                }
                refreshPendingSyncUi()
                syncPendingStateAfterAuthenticatedSession(
                    baseUrl = RastreoRepository.normalizeBaseUrl(baseUrl),
                    token = tokenResponse.accessToken,
                )
                ensurePendingCloseRetry()
            } else {
                val error = loginResult.exceptionOrNull()
                val message = error?.message ?: "No se pudo iniciar sesion."
                _uiState.update { state ->
                    state.copy(
                        isBusy = false,
                        isBootstrapping = false,
                        statusMessage = message,
                        operationMessage = "Fallo inicio de sesion.",
                        lastErrorMessage = message,
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            trackingJob?.cancel()
            pendingCloseRetryJob?.cancel()
            lastAcceptedPoint = null
            distanceMinimumDiscardCountSinceLastAccepted = 0
            sessionStore.clearSession()
            val pendingSyncState = pendingSyncStore.getState()
            _uiState.update {
                it.copy(
                    currentUser = null,
                    vehicles = emptyList(),
                    selectedVehicleId = null,
                    currentTripId = pendingSyncState.activeTripId ?: pendingSyncState.pendingClose?.tripId,
                    isTracking = false,
                    isSessionInvalid = false,
                    pendingTripClose = pendingSyncState.pendingClose != null,
                    pendingPointCount = pendingSyncState.pendingPoints.size,
                    pendingQueueTripId = pendingSyncState.pendingPoints.firstOrNull()?.tripId,
                    pendingCloseTripId = pendingSyncState.pendingClose?.tripId,
                    autoCloseRetryActive = false,
                    autoCloseRetryStatus = "Sin cierre pendiente",
                    refreshAvailable = false,
                    lastLocationText = "Sin dato",
                    lastAttemptText = "Sin intento",
                    lastLocationReadAttemptAt = "Sin intento",
                    lastLocationObtainedAt = "Sin dato",
                    lastSuccessfulSendAt = "Sin dato",
                    lastFailedSendAt = "Sin dato",
                    sendAttemptCount = 0,
                    sendSuccessCount = 0,
                    sendFailureCount = 0,
                    lastLatitude = null,
                    lastLongitude = null,
                    lastAccuracy = null,
                    lastSpeed = null,
                    lastLocationTimestamp = "Sin dato",
                    lastDiscardReason = "Sin descarte",
                    lastFilterElapsedSeconds = null,
                    lastFilterRequiredDistanceMeters = null,
                    lastFilterActualDistanceMeters = null,
                    secondsSinceLastAcceptedPoint = null,
                    distanceMinimumDiscardCountSinceLastAccepted = 0,
                    lastSendType = "Sin envio",
                    statusMessage = "Sesion cerrada.",
                    operationMessage = "Sesion cerrada.",
                    lastErrorMessage = "",
                )
            }
        }
    }

    fun loadVehicles() {
        val user = uiState.value.currentUser ?: return
        if (uiState.value.isSessionInvalid) {
            val message = "Sesion expirada. Inicia sesion nuevamente."
            _uiState.update {
                it.copy(
                    statusMessage = message,
                    operationMessage = "Sesion expirada.",
                    lastErrorMessage = message,
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, statusMessage = "Recargando vehiculos...") }
            val session = sessionStore.sessionData.first()
            runCatching {
                repository.listVehicles(uiState.value.baseUrl, session.token)
            }.onSuccess { vehicles ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        vehicles = vehicles,
                        selectedVehicleId = chooseVehicle(user.vehicleIds, vehicles),
                        statusMessage = "Vehiculos actualizados.",
                        operationMessage = "Vehiculos actualizados.",
                    )
                }
            }.onFailure {
                val message = it.message ?: "No se pudieron cargar vehiculos."
                _uiState.update { state ->
                    state.copy(
                        isBusy = false,
                        statusMessage = message,
                        operationMessage = "Fallo recargando vehiculos.",
                        lastErrorMessage = message,
                    )
                }
            }
        }
    }

    fun selectVehicle(vehicleId: Int?) {
        _uiState.update { it.copy(selectedVehicleId = vehicleId) }
    }

    fun updateCategory(value: String) {
        _uiState.update { it.copy(category = value) }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasLocationPermission = granted) }
        if (!granted) {
            val message = "Permiso de ubicacion denegado."
            _uiState.update {
                it.copy(
                    statusMessage = message,
                    operationMessage = "Permiso de ubicacion denegado.",
                    lastErrorMessage = message,
                )
            }
            return
        }
        startTracking()
    }

    private fun startTracking() {
        val state = uiState.value
        val vehicleId = state.selectedVehicleId
        val user = state.currentUser
        if (user == null) return
        if (state.isSessionInvalid) {
            val message = "Sesion expirada. Inicia sesion nuevamente."
            _uiState.update {
                it.copy(
                    statusMessage = message,
                    operationMessage = "Sesion expirada.",
                    lastErrorMessage = message,
                )
            }
            return
        }
        if (state.currentTripId != null && !state.isTracking) {
            val message = "Hay un recorrido pendiente de sincronizar o cerrar antes de iniciar uno nuevo."
            _uiState.update {
                it.copy(
                    statusMessage = message,
                    operationMessage = "Hay un recorrido pendiente.",
                    lastErrorMessage = message,
                )
            }
            return
        }
        if (user.permissions["edit_trips"] != true) {
            val message = "El backend actual no permite tracking para este usuario."
            _uiState.update {
                it.copy(
                    statusMessage = message,
                    operationMessage = "Tracking no permitido.",
                    lastErrorMessage = message,
                )
            }
            return
        }
        if (vehicleId == null) {
            val message = "Selecciona un vehiculo primero."
            _uiState.update {
                it.copy(
                    statusMessage = message,
                    operationMessage = "Falta seleccionar vehiculo.",
                    lastErrorMessage = message,
                )
            }
            return
        }
        if (state.category.isBlank()) {
            val message = "La categoria es obligatoria."
            _uiState.update {
                it.copy(
                    statusMessage = message,
                    operationMessage = "Categoria vacia.",
                    lastErrorMessage = message,
                )
            }
            return
        }

        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            val token = sessionStore.sessionData.first().token
            _uiState.update { it.copy(isBusy = true, statusMessage = "Iniciando tracking...") }
            val startTripResult = runCatching {
                repository.startTrip(uiState.value.baseUrl, token, vehicleId, state.category.trim())
            }

            val startedTrip = startTripResult.getOrNull()
            if (startedTrip != null) {
                val trip = startedTrip
                pendingSyncStore.setActiveTripId(trip.id)
                pendingSyncStore.clearPendingClose(trip.id)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        currentTripId = trip.id,
                        isTracking = true,
                        isSessionInvalid = false,
                        pendingTripClose = false,
                        statusMessage = "Trip ${trip.id} iniciado. Enviando ubicacion...",
                        operationMessage = "Trip ${trip.id} iniciado.",
                        lastErrorMessage = "",
                        lastLocationReadAttemptAt = "Sin intento",
                        lastLocationObtainedAt = "Sin dato",
                        lastSuccessfulSendAt = "Sin dato",
                        lastFailedSendAt = "Sin dato",
                        sendAttemptCount = 0,
                        sendSuccessCount = 0,
                        sendFailureCount = 0,
                        lastLatitude = null,
                        lastLongitude = null,
                        lastAccuracy = null,
                        lastSpeed = null,
                        lastLocationTimestamp = "Sin dato",
                        lastDiscardReason = "Sin descarte",
                        lastFilterElapsedSeconds = null,
                        lastFilterRequiredDistanceMeters = null,
                        lastFilterActualDistanceMeters = null,
                        secondsSinceLastAcceptedPoint = null,
                        distanceMinimumDiscardCountSinceLastAccepted = 0,
                        pendingPointCount = 0,
                        lastSendType = "Sin envio",
                    )
                }
                lastAcceptedPoint = null
                distanceMinimumDiscardCountSinceLastAccepted = 0
                trackingLoop(trip.id, token)
            } else {
                val error = startTripResult.exceptionOrNull()
                if (isUnauthorizedError(error)) {
                    markSessionExpired(
                        statusMessage = "Sesion expirada. Inicia sesion nuevamente.",
                        operationMessage = "Sesion expirada antes de iniciar tracking.",
                        clearUserContext = true,
                    )
                } else {
                    val message = if (isNetworkError(error)) {
                        "No se pudo iniciar el recorrido por problema de conexion."
                    } else {
                        error?.message ?: "No se pudo iniciar el trip."
                    }
                    _uiState.update { current ->
                        current.copy(
                            isBusy = false,
                            isTracking = false,
                            currentTripId = null,
                            statusMessage = message,
                            operationMessage = "Fallo inicio de tracking.",
                            lastErrorMessage = message,
                        )
                    }
                }
            }
        }
    }

    fun stopTracking() {
        val tripId = uiState.value.currentTripId ?: run {
            val message = "No hay trip activo."
            _uiState.update {
                it.copy(
                    statusMessage = message,
                    operationMessage = "No hay trip activo.",
                    lastErrorMessage = message,
                )
            }
            return
        }
        trackingJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    isTracking = false,
                    statusMessage = "Deteniendo trip $tripId...",
                )
            }

            if (uiState.value.isSessionInvalid) {
                savePendingClose(tripId)
                refreshPendingSyncUi(forceTripId = tripId)
                ensurePendingCloseRetry()
                val message = "Sesion expirada. El cierre del recorrido quedo pendiente."
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = message,
                        operationMessage = "Cierre pendiente por sesion expirada.",
                        lastErrorMessage = message,
                    )
                }
                return@launch
            }

            val token = sessionStore.sessionData.first().token
            val hasPendingCloseForTrip = pendingSyncStore.getState().pendingClose?.tripId == tripId
            if (pendingSyncStore.hasPendingPointsForTrip(tripId) || hasPendingCloseForTrip) {
                savePendingClose(tripId)
                ensurePendingCloseRetry()
                when (runPendingCloseSyncAttempt(token, uiState.value.baseUrl)) {
                    SyncResult.SUCCESS -> {
                        _uiState.update { state ->
                            state.copy(isBusy = false)
                        }
                    }

                    SyncResult.UNAUTHORIZED -> {
                        markSessionExpired(
                            statusMessage = "Sesion expirada. El cierre del recorrido quedo pendiente.",
                            operationMessage = "Cierre pendiente por sesion expirada.",
                            preserveTripId = tripId,
                            pendingTripClose = true,
                            clearUserContext = false,
                        )
                    }

                    SyncResult.RETRY_LATER -> {
                        refreshPendingSyncUi(forceTripId = tripId)
                        val pointsStillPending = pendingSyncStore.hasPendingPointsForTrip(tripId)
                        val message = if (pointsStillPending) {
                            "Cierre pendiente: no se pudieron sincronizar los puntos."
                        } else {
                            "Cierre pendiente por problema de conexion."
                        }
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                currentTripId = tripId,
                                statusMessage = message,
                                operationMessage = if (pointsStillPending) {
                                    "Cierre pendiente por puntos pendientes."
                                } else {
                                    "Cierre pendiente por conexion."
                                },
                                lastErrorMessage = message,
                            )
                        }
                    }
                }
                return@launch
            }

            val stopTripResult = runCatching {
                repository.stopTrip(uiState.value.baseUrl, token, tripId)
            }

            if (stopTripResult.isSuccess) {
                lastAcceptedPoint = null
                distanceMinimumDiscardCountSinceLastAccepted = 0
                pendingSyncStore.clearPendingClose(tripId)
                pendingSyncStore.setActiveTripId(null)
                refreshPendingSyncUi(preserveCurrentTripId = false)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        currentTripId = null,
                        isTracking = false,
                        pendingTripClose = false,
                        isSessionInvalid = false,
                        statusMessage = "Trip $tripId detenido.",
                        operationMessage = "Tracking detenido.",
                    )
                }
            } else {
                val error = stopTripResult.exceptionOrNull()
                savePendingClose(tripId)
                ensurePendingCloseRetry()
                if (isUnauthorizedError(error)) {
                    markSessionExpired(
                        statusMessage = "Sesion expirada. El cierre del recorrido quedo pendiente.",
                        operationMessage = "Cierre pendiente por sesion expirada.",
                        preserveTripId = tripId,
                        pendingTripClose = true,
                        clearUserContext = false,
                    )
                } else {
                    refreshPendingSyncUi(forceTripId = tripId)
                    val message = if (isNetworkError(error)) {
                        "No se pudo cerrar el recorrido por problema de conexion. El cierre quedo pendiente."
                    } else {
                        error?.message ?: "No se pudo cerrar el trip en backend."
                    }
                    _uiState.update { state ->
                        state.copy(
                            isBusy = false,
                            currentTripId = tripId,
                            pendingTripClose = true,
                            statusMessage = message,
                            operationMessage = "Fallo deteniendo tracking.",
                            lastErrorMessage = message,
                        )
                    }
                }
            }
        }
    }

    private suspend fun trackingLoop(tripId: Int, token: String) {
        while (uiState.value.isTracking) {
            val timestamp = Instant.now().toString()
            _uiState.update {
                it.copy(
                    lastAttemptText = timestamp,
                    lastLocationReadAttemptAt = timestamp,
                    operationMessage = "Intentando obtener ubicacion.",
                )
            }

            val location = locationRepository.getCurrentLocation()
            if (location == null) {
                val message = "No se pudo obtener ubicacion GPS en este intento."
                _uiState.update {
                    it.copy(
                        statusMessage = message,
                        operationMessage = "Ubicacion no disponible.",
                        lastErrorMessage = message,
                    )
                }
                kotlinx.coroutines.delay(BuildConfig.TRACKING_INTERVAL_MS)
                continue
            }

            val currentInstant = Instant.parse(timestamp)
            val validation = validateLocationPoint(
                tripId = tripId,
                location = location,
                currentInstant = currentInstant,
            )
            _uiState.update {
                it.copy(
                    lastLocationText = "${location.latitude}, ${location.longitude}",
                    lastLocationObtainedAt = timestamp,
                    lastLatitude = location.latitude,
                    lastLongitude = location.longitude,
                    lastAccuracy = location.accuracy,
                    lastSpeed = location.speed,
                    lastLocationTimestamp = timestamp,
                    lastFilterElapsedSeconds = validation.elapsedSeconds,
                    lastFilterRequiredDistanceMeters = validation.requiredDistanceMeters,
                    lastFilterActualDistanceMeters = validation.actualDistanceMeters,
                    operationMessage = "Ubicacion obtenida.",
                )
            }

            if (validation.discardType == DiscardType.MINIMUM_DISTANCE) {
                distanceMinimumDiscardCountSinceLastAccepted += 1
            }

            val shouldSendPermanencePoint = shouldSendPermanencePoint(validation)
            if (!validation.isAccepted && !shouldSendPermanencePoint) {
                val reason = validation.discardReason ?: "Punto descartado."
                _uiState.update {
                    it.copy(
                        secondsSinceLastAcceptedPoint = validation.elapsedSeconds,
                        distanceMinimumDiscardCountSinceLastAccepted =
                            distanceMinimumDiscardCountSinceLastAccepted,
                        lastDiscardReason = reason,
                        operationMessage = reason,
                        statusMessage = reason,
                    )
                }
                kotlinx.coroutines.delay(BuildConfig.TRACKING_INTERVAL_MS)
                continue
            }

            val request = TripPointRequest(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = timestamp,
                accuracy = location.accuracy,
                speed = location.speed,
            )
            val sendType = if (shouldSendPermanencePoint) {
                "permanencia"
            } else {
                "normal"
            }
            val pendingPointDraft = PendingTripPointDraft(
                tripId = tripId,
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = timestamp,
                accuracy = location.accuracy,
                speed = location.speed,
                sendType = sendType,
            )

            _uiState.update {
                it.copy(
                    sendAttemptCount = it.sendAttemptCount + 1,
                    secondsSinceLastAcceptedPoint = validation.elapsedSeconds,
                    distanceMinimumDiscardCountSinceLastAccepted =
                        distanceMinimumDiscardCountSinceLastAccepted,
                    operationMessage = if (shouldSendPermanencePoint) {
                        "Enviando punto por permanencia."
                    } else {
                        "Enviando punto."
                    },
                )
            }

            when (syncPendingPointsForTrip(tripId, token, uiState.value.baseUrl)) {
                SyncResult.UNAUTHORIZED -> {
                    enqueuePendingPoint(pendingPointDraft)
                    val hasPendingClose = pendingSyncStore.getState().pendingClose != null
                    markSessionExpired(
                        statusMessage = "Sesion expirada durante el tracking. Inicia sesion nuevamente para cerrar o continuar.",
                        operationMessage = "Tracking detenido por sesion expirada.",
                        preserveTripId = tripId,
                        pendingTripClose = hasPendingClose,
                        clearUserContext = false,
                        failedAt = Instant.now().toString(),
                        lastLocationText = "${location.latitude}, ${location.longitude}",
                        incrementSendFailure = true,
                    )
                }

                SyncResult.RETRY_LATER -> {
                    enqueuePendingPoint(pendingPointDraft)
                    val message = "Punto guardado en cola por problema de conexion."
                    _uiState.update { state ->
                        state.copy(
                            lastLocationText = "${location.latitude}, ${location.longitude}",
                            statusMessage = message,
                            operationMessage = "Punto agregado a cola offline.",
                            lastFailedSendAt = Instant.now().toString(),
                            sendFailureCount = state.sendFailureCount + 1,
                            lastErrorMessage = message,
                        )
                    }
                }

                SyncResult.SUCCESS -> {
                    if (pendingSyncStore.hasPendingPointsForTrip(tripId)) {
                        enqueuePendingPoint(pendingPointDraft)
                        val message = "Punto guardado en cola hasta completar la sincronizacion pendiente."
                        _uiState.update { state ->
                            state.copy(
                                lastLocationText = "${location.latitude}, ${location.longitude}",
                                statusMessage = message,
                                operationMessage = "Punto agregado a cola offline.",
                                lastFailedSendAt = Instant.now().toString(),
                                sendFailureCount = state.sendFailureCount + 1,
                                lastErrorMessage = message,
                            )
                        }
                    } else {
                        val sendPointResult = runCatching {
                            repository.sendTripPoint(uiState.value.baseUrl, token, tripId, request)
                        }

                        if (sendPointResult.isSuccess) {
                            val sentAt = Instant.now().toString()
                            val uiSendType = if (shouldSendPermanencePoint) {
                                "Envio por permanencia"
                            } else {
                                "Envio normal"
                            }
                            val pendingSyncState = pendingSyncStore.getState()
                            _uiState.update {
                                it.copy(
                                    lastLocationText = "${location.latitude}, ${location.longitude}",
                                    statusMessage = if (shouldSendPermanencePoint) {
                                        "Punto de permanencia enviado correctamente."
                                    } else {
                                        "Ubicacion enviada correctamente."
                                    },
                                    operationMessage = if (shouldSendPermanencePoint) {
                                        "Punto de permanencia enviado."
                                    } else {
                                        "Punto enviado."
                                    },
                                    lastSuccessfulSendAt = sentAt,
                                    sendSuccessCount = it.sendSuccessCount + 1,
                                    lastDiscardReason = "Sin descarte",
                                    secondsSinceLastAcceptedPoint = 0,
                                    distanceMinimumDiscardCountSinceLastAccepted = 0,
                                    pendingTripClose = pendingSyncState.pendingClose != null,
                                    pendingPointCount = pendingSyncState.pendingPoints.size,
                                    isSessionInvalid = false,
                                    lastSendType = uiSendType,
                                    lastErrorMessage = "",
                                )
                            }
                            lastAcceptedPoint = AcceptedPoint(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                acceptedAt = currentInstant,
                            )
                            distanceMinimumDiscardCountSinceLastAccepted = 0
                            runPendingCloseSyncAttempt(token, uiState.value.baseUrl)
                        } else {
                            val error = sendPointResult.exceptionOrNull()
                            val failedAt = Instant.now().toString()
                            enqueuePendingPoint(pendingPointDraft)
                            if (isUnauthorizedError(error)) {
                                val hasPendingClose = pendingSyncStore.getState().pendingClose != null
                                markSessionExpired(
                                    statusMessage = "Sesion expirada durante el tracking. Inicia sesion nuevamente para cerrar o continuar.",
                                    operationMessage = "Tracking detenido por sesion expirada.",
                                    preserveTripId = tripId,
                                    pendingTripClose = hasPendingClose,
                                    clearUserContext = false,
                                    failedAt = failedAt,
                                    lastLocationText = "${location.latitude}, ${location.longitude}",
                                    incrementSendFailure = true,
                                )
                            } else {
                                val message = if (isNetworkError(error)) {
                                    "No se pudo enviar la ubicacion por problema de conexion. Punto guardado en cola."
                                } else {
                                    "No se pudo enviar la ubicacion. Punto guardado en cola."
                                }
                                _uiState.update { state ->
                                    state.copy(
                                        lastLocationText = "${location.latitude}, ${location.longitude}",
                                        statusMessage = message,
                                        operationMessage = "Punto agregado a cola offline.",
                                        lastFailedSendAt = failedAt,
                                        sendFailureCount = state.sendFailureCount + 1,
                                        lastErrorMessage = message,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!uiState.value.isTracking) {
                break
            }

            kotlinx.coroutines.delay(BuildConfig.TRACKING_INTERVAL_MS)
        }
    }

    private fun isUnauthorizedError(error: Throwable?): Boolean {
        return error is HttpException && error.code() == 401
    }

    private fun isNetworkError(error: Throwable?): Boolean {
        return error is IOException || error is RefreshTransientException
    }

    private suspend fun markSessionExpired(
        statusMessage: String,
        operationMessage: String,
        preserveTripId: Int? = uiState.value.currentTripId,
        pendingTripClose: Boolean = false,
        clearUserContext: Boolean = false,
        failedAt: String? = null,
        lastLocationText: String? = null,
        incrementSendFailure: Boolean = false,
    ) {
        pendingCloseRetryJob?.cancel()
        sessionStore.clearSession()
        lastAcceptedPoint = null
        distanceMinimumDiscardCountSinceLastAccepted = 0
        val pendingSyncState = pendingSyncStore.getState()

        _uiState.update { state ->
            state.copy(
                isBootstrapping = false,
                isBusy = false,
                currentUser = if (clearUserContext) null else state.currentUser,
                vehicles = if (clearUserContext) emptyList() else state.vehicles,
                selectedVehicleId = if (clearUserContext) null else state.selectedVehicleId,
                currentTripId = preserveTripId,
                isTracking = false,
                isSessionInvalid = true,
                pendingTripClose = pendingTripClose,
                pendingPointCount = pendingSyncState.pendingPoints.size,
                pendingQueueTripId = pendingSyncState.pendingPoints.firstOrNull()?.tripId,
                pendingCloseTripId = pendingSyncState.pendingClose?.tripId,
                autoCloseRetryActive = false,
                autoCloseRetryStatus = if (pendingTripClose) {
                    "Sesion invalida"
                } else {
                    "Sin cierre pendiente"
                },
                refreshAvailable = false,
                lastLocationText = lastLocationText ?: state.lastLocationText,
                lastFailedSendAt = failedAt ?: state.lastFailedSendAt,
                sendFailureCount = if (incrementSendFailure) {
                    state.sendFailureCount + 1
                } else {
                    state.sendFailureCount
                },
                statusMessage = statusMessage,
                operationMessage = operationMessage,
                lastErrorMessage = statusMessage,
            )
        }
    }

    private suspend fun refreshPendingSyncUi(
        preserveCurrentTripId: Boolean = true,
        forceTripId: Int? = null,
    ) {
        val pendingSyncState = pendingSyncStore.getState()
        val session = sessionStore.sessionData.first()
        val pendingQueueTripId = pendingSyncState.pendingPoints.firstOrNull()?.tripId
        val pendingCloseTripId = pendingSyncState.pendingClose?.tripId
        val storedTripId = forceTripId
            ?: pendingCloseTripId
            ?: pendingSyncState.activeTripId
            ?: pendingQueueTripId

        _uiState.update { state ->
            state.copy(
                currentTripId = when {
                    state.isTracking -> state.currentTripId
                    storedTripId != null -> storedTripId
                    preserveCurrentTripId -> state.currentTripId
                    else -> null
                },
                pendingTripClose = pendingSyncState.pendingClose != null,
                pendingPointCount = pendingSyncState.pendingPoints.size,
                pendingQueueTripId = pendingQueueTripId,
                pendingCloseTripId = pendingCloseTripId,
                refreshAvailable = session.refreshToken.isNotBlank(),
                autoCloseRetryStatus = if (pendingCloseTripId == null &&
                    pendingCloseRetryJob?.isActive != true
                ) {
                    "Sin cierre pendiente"
                } else {
                    state.autoCloseRetryStatus
                },
            )
        }
    }

    private suspend fun enqueuePendingPoint(point: PendingTripPointDraft) {
        pendingSyncStore.enqueuePoint(point)
        updateLastAcceptedPoint(
            latitude = point.latitude,
            longitude = point.longitude,
            timestamp = point.timestamp,
        )
        refreshPendingSyncUi(forceTripId = point.tripId)
    }

    private suspend fun savePendingClose(tripId: Int) {
        pendingSyncStore.savePendingClose(
            tripId = tripId,
            stopRequestedAt = Instant.now().toString(),
        )
        refreshPendingSyncUi(forceTripId = tripId)
    }

    private suspend fun syncPendingStateAfterAuthenticatedSession(
        baseUrl: String,
        token: String,
    ) {
        refreshPendingSyncUi()
        val pendingSyncState = pendingSyncStore.getState()
        val tripId = pendingSyncState.pendingClose?.tripId
            ?: pendingSyncState.activeTripId
            ?: pendingSyncState.pendingPoints.firstOrNull()?.tripId
            ?: return

        recordSyncAttempt()

        when (syncPendingPointsForTrip(tripId, token, baseUrl)) {
            SyncResult.SUCCESS -> runPendingCloseSyncAttempt(token, baseUrl)
            SyncResult.UNAUTHORIZED -> {
                val hasPendingClose = pendingSyncStore.getState().pendingClose != null
                markSessionExpired(
                    statusMessage = "Sesion expirada. Inicia sesion nuevamente.",
                    operationMessage = "Sesion expirada.",
                    preserveTripId = tripId,
                    pendingTripClose = hasPendingClose,
                    clearUserContext = false,
                )
            }
            SyncResult.RETRY_LATER -> refreshPendingSyncUi(forceTripId = tripId)
        }
    }

    private suspend fun runPendingCloseSyncAttempt(
        token: String,
        baseUrl: String,
    ): SyncResult {
        return pendingCloseSyncMutex.withLock {
            _uiState.update { state ->
                state.copy(
                    autoCloseRetryStatus = "Intentando sync",
                    autoCloseRetryAttemptCount = state.autoCloseRetryAttemptCount + 1,
                )
            }
            val result = trySyncPendingClose(token, baseUrl)
            _uiState.update { state ->
                state.copy(
                    autoCloseRetryActive = when (result) {
                        SyncResult.SUCCESS -> false
                        SyncResult.RETRY_LATER -> true
                        SyncResult.UNAUTHORIZED -> false
                    },
                    autoCloseRetryStatus = when (result) {
                        SyncResult.SUCCESS -> "Cierre completado"
                        SyncResult.RETRY_LATER -> "Retry later"
                        SyncResult.UNAUTHORIZED -> "Unauthorized"
                    },
                )
            }
            result
        }
    }

    private suspend fun syncPendingPointsForTrip(
        tripId: Int,
        token: String,
        baseUrl: String,
    ): SyncResult {
        val pendingPoints = pendingSyncStore.getPendingPointsForTrip(tripId)
        if (pendingPoints.isEmpty()) {
            return SyncResult.SUCCESS
        }

        recordSyncAttempt()
        _uiState.update {
            it.copy(operationMessage = "Sincronizando puntos pendientes.")
        }

        for (point in pendingPoints) {
            val syncResult = runCatching {
                repository.sendTripPoint(
                    baseUrl = baseUrl,
                    token = token,
                    tripId = tripId,
                    request = TripPointRequest(
                        latitude = point.latitude,
                        longitude = point.longitude,
                        timestamp = point.timestamp,
                        accuracy = point.accuracy,
                        speed = point.speed,
                    ),
                )
            }

            if (syncResult.isSuccess) {
                pendingSyncStore.removePoint(point.sequence)
                updateLastAcceptedPoint(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    timestamp = point.timestamp,
                )
                recordQueuedPointUploaded()
                clearSyncError()
                refreshPendingSyncUi(forceTripId = tripId)
            } else {
                val error = syncResult.exceptionOrNull()
                recordSyncFailure(
                    if (isUnauthorizedError(error)) {
                        "Sesion expirada durante sincronizacion."
                    } else if (isNetworkError(error)) {
                        "No se pudieron sincronizar puntos por problema de conexion."
                    } else {
                        error?.message ?: "No se pudieron sincronizar puntos pendientes."
                    }
                )
                return if (isUnauthorizedError(error)) {
                    SyncResult.UNAUTHORIZED
                } else {
                    SyncResult.RETRY_LATER
                }
            }
        }

        return SyncResult.SUCCESS
    }

    private suspend fun trySyncPendingClose(
        token: String,
        baseUrl: String,
    ): SyncResult {
        val pendingClose = pendingSyncStore.getState().pendingClose ?: return SyncResult.SUCCESS
        val tripId = pendingClose.tripId

        recordSyncAttempt()

        when (syncPendingPointsForTrip(tripId, token, baseUrl)) {
            SyncResult.UNAUTHORIZED -> return SyncResult.UNAUTHORIZED
            SyncResult.RETRY_LATER -> return SyncResult.RETRY_LATER
            SyncResult.SUCCESS -> Unit
        }

        if (pendingSyncStore.hasPendingPointsForTrip(tripId)) {
            refreshPendingSyncUi(forceTripId = tripId)
            return SyncResult.RETRY_LATER
        }

        val stopTripResult = runCatching {
            repository.stopTrip(baseUrl, token, tripId)
        }

        if (stopTripResult.isSuccess) {
            pendingSyncStore.clearPendingClose(tripId)
            pendingSyncStore.setActiveTripId(null)
            lastAcceptedPoint = null
            distanceMinimumDiscardCountSinceLastAccepted = 0
            clearSyncError()
            refreshPendingSyncUi(preserveCurrentTripId = false)
            _uiState.update { state ->
                state.copy(
                    isBusy = false,
                    currentTripId = null,
                    pendingTripClose = false,
                    autoCloseRetryActive = false,
                    autoCloseRetryStatus = "Cierre completado",
                    statusMessage = "Recorrido cerrado correctamente.",
                    operationMessage = "Recorrido cerrado.",
                    lastErrorMessage = "",
                )
            }
            return SyncResult.SUCCESS
        }

        val stopError = stopTripResult.exceptionOrNull()
        recordSyncFailure(
            if (isUnauthorizedError(stopError)) {
                "Sesion expirada al sincronizar cierre pendiente."
            } else if (isNetworkError(stopError)) {
                "No se pudo sincronizar el cierre pendiente por problema de conexion."
            } else {
                stopError?.message ?: "No se pudo sincronizar el cierre pendiente."
            }
        )
        return if (isUnauthorizedError(stopError)) {
            SyncResult.UNAUTHORIZED
        } else {
            refreshPendingSyncUi(forceTripId = tripId)
            SyncResult.RETRY_LATER
        }
    }

    private fun ensurePendingCloseRetry() {
        if (pendingCloseRetryJob?.isActive == true) {
            _uiState.update { state ->
                state.copy(
                    autoCloseRetryActive = true,
                    autoCloseRetryStatus = "Auto cierre activo",
                )
            }
            return
        }

        _uiState.update { state ->
            state.copy(
                autoCloseRetryActive = true,
                autoCloseRetryStatus = "Auto cierre activo",
            )
        }
        pendingCloseRetryJob = viewModelScope.launch {
            while (true) {
                val pendingClose = pendingSyncStore.getState().pendingClose
                val session = sessionStore.sessionData.first()
                val currentState = uiState.value

                if (pendingClose == null || currentState.isSessionInvalid || session.token.isBlank()) {
                    _uiState.update { state ->
                        state.copy(
                            autoCloseRetryActive = false,
                            autoCloseRetryStatus = when {
                                pendingClose == null -> "Sin cierre pendiente"
                                currentState.isSessionInvalid -> "Sesion invalida"
                                session.token.isBlank() -> "Sin token"
                                else -> state.autoCloseRetryStatus
                            },
                        )
                    }
                    break
                }

                if (currentState.isTracking) {
                    _uiState.update { state ->
                        state.copy(
                            autoCloseRetryActive = true,
                            autoCloseRetryStatus = "Tracking activo",
                        )
                    }
                    delay(PENDING_CLOSE_RETRY_INTERVAL_MS)
                    continue
                }

                if (currentState.isBusy) {
                    _uiState.update { state ->
                        state.copy(
                            autoCloseRetryActive = true,
                            autoCloseRetryStatus = "App ocupada",
                        )
                    }
                    delay(PENDING_CLOSE_RETRY_INTERVAL_MS)
                    continue
                }

                _uiState.update { state ->
                    state.copy(
                        operationMessage = "Reintentando cierre pendiente.",
                        autoCloseRetryActive = true,
                        autoCloseRetryStatus = "Intentando sync",
                    )
                }

                when (
                    runPendingCloseSyncAttempt(
                        token = session.token,
                        baseUrl = currentState.baseUrl,
                    )
                ) {
                    SyncResult.SUCCESS -> {
                        _uiState.update { state ->
                            state.copy(
                                autoCloseRetryActive = false,
                                autoCloseRetryStatus = "Cierre completado",
                            )
                        }
                        break
                    }
                    SyncResult.UNAUTHORIZED -> {
                        _uiState.update { state ->
                            state.copy(
                                autoCloseRetryActive = false,
                                autoCloseRetryStatus = "Unauthorized",
                            )
                        }
                        val hasPendingClose = pendingSyncStore.getState().pendingClose != null
                        markSessionExpired(
                            statusMessage = "Sesion expirada. El cierre del recorrido quedo pendiente.",
                            operationMessage = "Cierre pendiente por sesion expirada.",
                            preserveTripId = pendingClose.tripId,
                            pendingTripClose = hasPendingClose,
                            clearUserContext = false,
                        )
                        break
                    }
                    SyncResult.RETRY_LATER -> {
                        _uiState.update { state ->
                            state.copy(
                                autoCloseRetryActive = true,
                                autoCloseRetryStatus = "Retry later",
                            )
                        }
                        delay(PENDING_CLOSE_RETRY_INTERVAL_MS)
                    }
                }
            }

            pendingCloseRetryJob = null
        }
    }

    override fun onCleared() {
        trackingJob?.cancel()
        pendingCloseRetryJob?.cancel()
        _uiState.update { state ->
            state.copy(
                autoCloseRetryActive = false,
                autoCloseRetryStatus = "Sin cierre pendiente",
            )
        }
        super.onCleared()
    }

    private fun recordSyncAttempt(at: String = Instant.now().toString()) {
        _uiState.update { state ->
            state.copy(lastSyncAttemptAt = at)
        }
    }

    private fun recordSyncFailure(message: String) {
        _uiState.update { state ->
            state.copy(
                lastSyncError = message,
                syncFailureCount = state.syncFailureCount + 1,
            )
        }
    }

    private fun clearSyncError() {
        _uiState.update { state ->
            state.copy(lastSyncError = "Sin error")
        }
    }

    private fun recordQueuedPointUploaded() {
        _uiState.update { state ->
            state.copy(queuedPointsUploadedCount = state.queuedPointsUploadedCount + 1)
        }
    }

    private fun chooseVehicle(visibleVehicleIds: List<Int>, vehicles: List<Vehicle>): Int? {
        return vehicles.firstOrNull { it.id in visibleVehicleIds }?.id ?: vehicles.firstOrNull()?.id
    }

    private suspend fun validateLocationPoint(
        tripId: Int,
        location: com.rastreo.vehicular.location.LocationSample,
        currentInstant: Instant,
    ): PointValidation {
        val accuracy = location.accuracy
        val requiredDistance = accuracy?.times(2)?.toDouble()
        val previous = getLocalAcceptedPoint(tripId) ?: return PointValidation(
            isAccepted = accuracy == null || accuracy <= MAX_ACCEPTED_ACCURACY_METERS,
            discardReason = if (accuracy != null && accuracy > MAX_ACCEPTED_ACCURACY_METERS) {
                "Descartado por precision."
            } else {
                null
            },
            discardType = if (accuracy != null && accuracy > MAX_ACCEPTED_ACCURACY_METERS) {
                DiscardType.PRECISION
            } else {
                null
            },
            elapsedSeconds = null,
            requiredDistanceMeters = requiredDistance,
            actualDistanceMeters = null,
        )

        val elapsedSeconds = Duration.between(previous.acceptedAt, currentInstant).seconds
        val actualDistance = distanceMeters(
            previous.latitude,
            previous.longitude,
            location.latitude,
            location.longitude,
        )

        return when {
            accuracy != null && accuracy > MAX_ACCEPTED_ACCURACY_METERS -> PointValidation(
                isAccepted = false,
                discardReason = "Descartado por precision.",
                discardType = DiscardType.PRECISION,
                elapsedSeconds = elapsedSeconds,
                requiredDistanceMeters = requiredDistance,
                actualDistanceMeters = actualDistance,
            )
            elapsedSeconds < MIN_SECONDS_BETWEEN_ACCEPTED_POINTS -> PointValidation(
                isAccepted = false,
                discardReason = "Descartado por tiempo minimo.",
                discardType = DiscardType.MINIMUM_TIME,
                elapsedSeconds = elapsedSeconds,
                requiredDistanceMeters = requiredDistance,
                actualDistanceMeters = actualDistance,
            )
            requiredDistance != null && actualDistance < requiredDistance -> PointValidation(
                isAccepted = false,
                discardReason = "Descartado por distancia minima.",
                discardType = DiscardType.MINIMUM_DISTANCE,
                elapsedSeconds = elapsedSeconds,
                requiredDistanceMeters = requiredDistance,
                actualDistanceMeters = actualDistance,
            )
            else -> PointValidation(
                isAccepted = true,
                discardReason = null,
                discardType = null,
                elapsedSeconds = elapsedSeconds,
                requiredDistanceMeters = requiredDistance,
                actualDistanceMeters = actualDistance,
            )
        }
    }

    private suspend fun getLocalAcceptedPoint(tripId: Int): AcceptedPoint? {
        val pendingPoint = pendingSyncStore.getLastPendingPointForTrip(tripId)
        return pendingPoint?.toAcceptedPoint() ?: lastAcceptedPoint
    }

    private fun updateLastAcceptedPoint(
        latitude: Double,
        longitude: Double,
        timestamp: String,
    ) {
        val acceptedAt = runCatching { Instant.parse(timestamp) }.getOrNull() ?: return
        lastAcceptedPoint = AcceptedPoint(
            latitude = latitude,
            longitude = longitude,
            acceptedAt = acceptedAt,
        )
        distanceMinimumDiscardCountSinceLastAccepted = 0
    }

    private fun distanceMeters(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double,
    ): Double {
        val earthRadiusMeters = 6_371_000.0
        val startLatRad = Math.toRadians(startLatitude)
        val endLatRad = Math.toRadians(endLatitude)
        val deltaLatRad = Math.toRadians(endLatitude - startLatitude)
        val deltaLonRad = Math.toRadians(endLongitude - startLongitude)
        val a = sin(deltaLatRad / 2) * sin(deltaLatRad / 2) +
            cos(startLatRad) * cos(endLatRad) *
            sin(deltaLonRad / 2) * sin(deltaLonRad / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }

    private fun shouldSendPermanencePoint(validation: PointValidation): Boolean {
        return validation.discardType == DiscardType.MINIMUM_DISTANCE &&
            validation.elapsedSeconds != null &&
            validation.elapsedSeconds >= PERMANENCE_POINT_SECONDS &&
            distanceMinimumDiscardCountSinceLastAccepted >= MIN_DISTANCE_DISCARDS_FOR_PERMANENCE
    }

    private data class AcceptedPoint(
        val latitude: Double,
        val longitude: Double,
        val acceptedAt: Instant,
    )

    private fun com.rastreo.vehicular.data.PendingTripPoint.toAcceptedPoint(): AcceptedPoint? {
        val acceptedAt = runCatching { Instant.parse(timestamp) }.getOrNull() ?: return null
        return AcceptedPoint(
            latitude = latitude,
            longitude = longitude,
            acceptedAt = acceptedAt,
        )
    }

    private data class PointValidation(
        val isAccepted: Boolean,
        val discardReason: String?,
        val discardType: DiscardType?,
        val elapsedSeconds: Long?,
        val requiredDistanceMeters: Double?,
        val actualDistanceMeters: Double?,
    )

    private enum class DiscardType {
        PRECISION,
        MINIMUM_TIME,
        MINIMUM_DISTANCE,
    }

    private enum class SyncResult {
        SUCCESS,
        RETRY_LATER,
        UNAUTHORIZED,
    }

    private companion object {
        private const val MAX_ACCEPTED_ACCURACY_METERS = 100f
        private const val MIN_SECONDS_BETWEEN_ACCEPTED_POINTS = 5L
        private const val PERMANENCE_POINT_SECONDS = 120L
        private const val MIN_DISTANCE_DISCARDS_FOR_PERMANENCE = 2
        private const val PENDING_CLOSE_RETRY_INTERVAL_MS = 15_000L
    }
}

class AppViewModelFactory(
    private val context: Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val sessionStore = SessionStore(context)
        return AppViewModel(
            sessionStore = sessionStore,
            pendingSyncStore = PendingSyncStore(context),
            repository = RastreoRepository(sessionStore),
            locationRepository = LocationRepository(context),
        ) as T
    }
}
