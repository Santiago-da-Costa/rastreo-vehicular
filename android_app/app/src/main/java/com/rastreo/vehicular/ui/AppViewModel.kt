package com.rastreo.vehicular.ui

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rastreo.vehicular.data.ActiveTrackingRef
import com.rastreo.vehicular.data.LocalTrip
import com.rastreo.vehicular.data.PendingSyncState
import com.rastreo.vehicular.data.PendingSyncStore
import com.rastreo.vehicular.data.PendingTripPoint
import com.rastreo.vehicular.data.PendingTripPointDraft
import com.rastreo.vehicular.data.RefreshTransientException
import com.rastreo.vehicular.data.RastreoRepository
import com.rastreo.vehicular.data.SessionStore
import com.rastreo.vehicular.data.TrackingConfigStore
import com.rastreo.vehicular.data.TrackingStatus
import com.rastreo.vehicular.data.TrackingStatusStore
import com.rastreo.vehicular.data.TripPointRequest
import com.rastreo.vehicular.data.UserMeResponse
import com.rastreo.vehicular.data.Vehicle
import com.rastreo.vehicular.location.LocationRepository
import com.rastreo.vehicular.service.TrackingForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.util.UUID
import retrofit2.HttpException

data class UiState(
    val isBootstrapping: Boolean = true,
    val isBusy: Boolean = false,
    val currentUser: UserMeResponse? = null,
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicleId: Int? = null,
    val category: String = "",
    val currentTripId: Int? = null,
    val trackingStartedAt: String = "Sin dato",
    val gpsObservationIntervalMs: Long = TrackingConfigStore.DEFAULT_GPS_OBSERVATION_INTERVAL_MS,
    val evaluationIntervalMs: Long = TrackingConfigStore.DEFAULT_EVALUATION_INTERVAL_MS,
    val loopStartedAt: String = "Sin dato",
    val loopFinishedAt: String = "Sin dato",
    val loopDurationMs: Long? = null,
    val isTracking: Boolean = false,
    val serviceRunning: Boolean = false,
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
    val gpsReadStartedAt: String = "Sin dato",
    val gpsReadFinishedAt: String = "Sin dato",
    val gpsReadDurationMs: Long? = null,
    val gpsReadCount: Int = 0,
    val gpsNullCount: Int = 0,
    val gpsSlowReadCount: Int = 0,
    val gpsVerySlowReadCount: Int = 0,
    val lastLocationObtainedAt: String = "Sin dato",
    val lastSuccessfulSendAt: String = "Sin dato",
    val lastFailedSendAt: String = "Sin dato",
    val sendAttemptCount: Int = 0,
    val sendSuccessCount: Int = 0,
    val sendFailureCount: Int = 0,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val lastAccuracy: Float? = null,
    val lastLocationProvider: String = "No disponible",
    val lastLocationAgeMs: Long? = null,
    val lastAcceptedLatitude: Double? = null,
    val lastAcceptedLongitude: Double? = null,
    val lastAcceptedAccuracy: Float? = null,
    val lastSpeed: Float? = null,
    val lastLocationTimestamp: String = "Sin dato",
    val rejectedPositionsCount: Int = 0,
    val lastRejectedAt: String = "Sin dato",
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
    private val appContext: Context,
    private val sessionStore: SessionStore,
    private val pendingSyncStore: PendingSyncStore,
    private val trackingConfigStore: TrackingConfigStore,
    private val trackingStatusStore: TrackingStatusStore,
    private val repository: RastreoRepository,
    private val locationRepository: LocationRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        UiState(hasLocationPermission = locationRepository.hasLocationPermission())
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pendingCloseRetryJob: Job? = null
    private var lastAcceptedPoint: AcceptedPoint? = null
    private var distanceMinimumDiscardCountSinceLastAccepted = 0
    private val pendingCloseSyncMutex = Mutex()

    init {
        observeTrackingConfig()
        observePendingSyncState()
        observeTrackingStatus()
    }

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
                recoverTrackingServiceIfNeeded(appContext)
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

    private suspend fun recoverTrackingServiceIfNeeded(context: Context) {
        val session = sessionStore.sessionData.first()
        if (session.token.isBlank()) {
            return
        }
        if (!locationRepository.hasLocationPermission()) {
            return
        }

        val pendingSyncState = pendingSyncStore.getState()
        val activeTripId = pendingSyncState.activeTripId ?: return
        if (pendingSyncState.pendingClose != null) {
            return
        }

        ContextCompat.startForegroundService(
            context,
            TrackingForegroundService.createStartIntent(
                context = context,
                tripId = activeTripId,
                localTripId = pendingSyncState.activeLocalTripId,
                isRecovery = true,
            ),
        )
    }

    private fun observeTrackingStatus() {
        viewModelScope.launch {
            trackingStatusStore.trackingStatus.collect { status ->
                _uiState.update { state ->
                    state.copy(
                        currentTripId = state.currentTripId ?: status.currentTripId,
                        category = if (status.category.isNotBlank()) status.category else state.category,
                        trackingStartedAt = status.trackingStartedAt,
                        loopStartedAt = status.loopStartedAt,
                        loopFinishedAt = status.loopFinishedAt,
                        loopDurationMs = status.loopDurationMs,
                        isTracking = state.isTracking || status.trackingActive,
                        serviceRunning = status.serviceRunning,
                        lastLocationText = buildLastLocationText(status),
                        lastAttemptText = status.lastReadAttemptAt,
                        lastLocationReadAttemptAt = status.lastReadAttemptAt,
                        gpsReadStartedAt = status.gpsReadStartedAt,
                        gpsReadFinishedAt = status.gpsReadFinishedAt,
                        gpsReadDurationMs = status.gpsReadDurationMs,
                        gpsReadCount = status.gpsReadCount,
                        gpsNullCount = status.gpsNullCount,
                        gpsSlowReadCount = status.gpsSlowReadCount,
                        gpsVerySlowReadCount = status.gpsVerySlowReadCount,
                        lastLocationObtainedAt = status.lastLocationAt,
                        lastSuccessfulSendAt = status.lastSuccessfulSendAt,
                        lastFailedSendAt = status.lastSendFailureAt,
                        sendAttemptCount = status.sendAttemptsCount,
                        sendSuccessCount = status.successfulSendsCount,
                        sendFailureCount = status.failedSendsCount,
                        lastLatitude = status.lastLatitude,
                        lastLongitude = status.lastLongitude,
                        lastAccuracy = status.lastAccuracy,
                        lastLocationProvider = status.lastLocationProvider,
                        lastLocationAgeMs = status.lastLocationAgeMs,
                        lastAcceptedLatitude = status.lastAcceptedLatitude,
                        lastAcceptedLongitude = status.lastAcceptedLongitude,
                        lastAcceptedAccuracy = status.lastAcceptedAccuracy,
                        lastSpeed = status.lastSpeed,
                        lastLocationTimestamp = status.lastLocationAt,
                        rejectedPositionsCount = status.rejectedPositionsCount,
                        lastRejectedAt = status.lastRejectedAt,
                        lastDiscardReason = status.lastGpsDiscardReason,
                        lastFilterElapsedSeconds = status.lastFilterElapsedSeconds,
                        lastFilterRequiredDistanceMeters = status.lastFilterRequiredDistanceMeters,
                        lastFilterActualDistanceMeters = status.lastFilterActualDistanceMeters,
                        secondsSinceLastAcceptedPoint = status.secondsSinceLastAcceptedPoint,
                        distanceMinimumDiscardCountSinceLastAccepted =
                            status.distanceMinimumDiscardCountSinceLastAccepted,
                        lastSendType = status.lastSendType,
                        operationMessage = mergeOperationalMessage(state, status),
                    )
                }
            }
        }
    }

    private fun observeTrackingConfig() {
        viewModelScope.launch {
            trackingConfigStore.trackingConfig.collect { config ->
                _uiState.update { state ->
                    state.copy(
                        gpsObservationIntervalMs = config.gpsObservationIntervalMs,
                        evaluationIntervalMs = config.evaluationIntervalMs,
                    )
                }
            }
        }
    }

    private fun observePendingSyncState() {
        viewModelScope.launch {
            pendingSyncStore.pendingSyncState.collect { pendingSyncState ->
                val pendingQueueTripId = pendingSyncState.pendingPoints.firstOrNull()?.tripId
                val pendingCloseTripId = pendingSyncState.pendingClose?.tripId
                val hasActiveTrip = pendingSyncState.activeTripId != null && pendingCloseTripId == null
                val storedTripId = pendingCloseTripId
                    ?: pendingSyncState.activeTripId
                    ?: pendingQueueTripId

                _uiState.update { state ->
                    state.copy(
                        currentTripId = when {
                            state.isTracking -> state.currentTripId ?: storedTripId
                            storedTripId != null -> storedTripId
                            else -> state.currentTripId
                        },
                        isTracking = when {
                            state.isTracking -> true
                            hasActiveTrip -> true
                            else -> false
                        },
                        pendingTripClose = pendingSyncState.pendingClose != null,
                        pendingPointCount = pendingSyncState.pendingPoints.size,
                        pendingQueueTripId = pendingQueueTripId,
                        pendingCloseTripId = pendingCloseTripId,
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
        }
    }

    private fun buildLastLocationText(status: TrackingStatus): String {
        val latitude = status.lastLatitude
        val longitude = status.lastLongitude
        return if (latitude != null && longitude != null) {
            "$latitude, $longitude"
        } else {
            "Sin dato"
        }
    }

    private fun mergeOperationalMessage(
        state: UiState,
        status: TrackingStatus,
    ): String {
        return if (
            status.serviceRunning ||
            status.trackingActive ||
            status.currentTripId != null ||
            status.lastOperationalMessage != "Esperando accion."
        ) {
            status.lastOperationalMessage
        } else {
            state.operationMessage
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

    fun setGpsObservationIntervalSeconds(seconds: Long) {
        viewModelScope.launch {
            trackingConfigStore.setGpsObservationIntervalMs(seconds * 1_000L)
        }
    }

    fun setEvaluationIntervalSeconds(seconds: Long) {
        viewModelScope.launch {
            trackingConfigStore.setEvaluationIntervalMs(seconds * 1_000L)
        }
    }

    fun resetTrackingConfigDefaults() {
        viewModelScope.launch {
            trackingConfigStore.resetDefaults()
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
            pendingCloseRetryJob?.cancel()
            stopTrackingService(appContext)
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

    fun validateTrackingSelection(): Boolean {
        val state = uiState.value
        val isValid = state.selectedVehicleId != null && state.category.isNotBlank()
        if (!isValid) {
            val message = "Debes seleccionar un vehiculo y una categoria antes de iniciar."
            _uiState.update {
                it.copy(
                    statusMessage = message,
                    operationMessage = message,
                    lastErrorMessage = message,
                )
            }
        }
        return isValid
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

    fun startTrackingService(
        context: Context,
        tripId: Int,
        vehicleId: Int,
        category: String,
        localTripId: String? = null,
    ) {
        val serviceIntent = TrackingForegroundService.createStartIntent(
            context = context,
            tripId = tripId,
            vehicleId = vehicleId,
            category = category,
            localTripId = localTripId,
        )
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    fun stopTrackingService(context: Context) {
        val serviceIntent = TrackingForegroundService.createStopIntent(context)
        context.startService(serviceIntent)
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

        viewModelScope.launch {
            val token = sessionStore.sessionData.first().token
            val category = state.category.trim()
            val localTripId = UUID.randomUUID().toString()
            val clientTripId = UUID.randomUUID().toString()
            val localTrip = LocalTrip(
                localTripId = localTripId,
                clientTripId = clientTripId,
                remoteTripId = null,
                vehicleId = vehicleId,
                categoria = category,
                startTime = Instant.now().toString(),
                endTime = null,
                status = PendingSyncStore.LOCAL_TRIP_STATUS_ACTIVE,
                syncState = PendingSyncStore.LOCAL_TRIP_SYNC_LOCAL_CREATED,
            )
            _uiState.update { it.copy(isBusy = true, statusMessage = "Iniciando tracking...") }
            pendingSyncStore.upsertLocalTrip(localTrip)
            val startTripResult = runCatching {
                repository.startTrip(
                    baseUrl = uiState.value.baseUrl,
                    token = token,
                    vehicleId = vehicleId,
                    categoria = category,
                    clientTripId = clientTripId,
                )
            }

            val startedTrip = startTripResult.getOrNull()
            if (startedTrip != null) {
                val trip = startedTrip
                pendingSyncStore.updateLocalTripRemoteId(localTripId, trip.id)
                pendingSyncStore.setActiveTripId(trip.id)
                pendingSyncStore.clearPendingClose(trip.id)
                startTrackingService(
                    context = appContext,
                    tripId = trip.id,
                    vehicleId = vehicleId,
                    category = category,
                    localTripId = localTripId,
                )
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
            } else {
                pendingSyncStore.removeLocalTrip(localTripId)
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
                    stopTrackingService(appContext)
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
        viewModelScope.launch {
            val pendingSyncState = pendingSyncStore.getState()
            val activeTrackingRef = pendingSyncState.activeTrackingRef
                ?: uiState.value.currentTripId?.let { fallbackTripId ->
                    ActiveTrackingRef.Remote(
                        tripId = fallbackTripId,
                        localTripId = pendingSyncStore.getActiveLocalTrip()?.localTripId,
                    )
                }
                ?: run {
                    val message = "No hay trip activo."
                    _uiState.update {
                        it.copy(
                            statusMessage = message,
                            operationMessage = "No hay trip activo.",
                            lastErrorMessage = message,
                        )
                    }
                    return@launch
                }

            if (activeTrackingRef is ActiveTrackingRef.Local) {
                stopTrackingService(appContext)
                val closedLocalTrip = pendingSyncStore.markLocalTripClosedLocally(
                    localTripId = activeTrackingRef.localTripId,
                )
                if (closedLocalTrip == null) {
                    val message = "No se encontro el recorrido local activo."
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            isTracking = false,
                            currentTripId = null,
                            statusMessage = message,
                            operationMessage = "Cierre local no aplicado.",
                            lastErrorMessage = message,
                        )
                    }
                    return@launch
                }

                val message = "Recorrido local cerrado. Queda pendiente de sincronizacion."
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        isTracking = false,
                        currentTripId = null,
                        statusMessage = message,
                        operationMessage = "Cierre local pendiente de sincronizacion.",
                    )
                }
                return@launch
            }

            val tripId = (activeTrackingRef as ActiveTrackingRef.Remote).tripId
            stopTrackingService(appContext)
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
            savePendingClose(tripId)
            ensurePendingCloseRetry()
            when (runPendingCloseSyncAttempt(token, uiState.value.baseUrl)) {
                SyncResult.SUCCESS -> {
                    _uiState.update { state ->
                        state.copy(
                            isBusy = false,
                            isTracking = false,
                            isSessionInvalid = false,
                            statusMessage = "Trip $tripId detenido.",
                            operationMessage = "Tracking detenido.",
                        )
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
                            pendingTripClose = true,
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
        val hasActiveTrip = pendingSyncState.activeTripId != null && pendingCloseTripId == null
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
                isTracking = when {
                    state.isTracking -> true
                    hasActiveTrip -> true
                    else -> false
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
        val localTrip = preferredSyncLocalTrip(pendingSyncState)
        val tripId = localTrip?.remoteTripId
            ?: pendingSyncState.pendingClose?.tripId
            ?: pendingSyncState.activeTripId
            ?: pendingSyncState.pendingPoints.firstOrNull()?.tripId
            ?: return

        recordSyncAttempt()

        val pointsSyncResult = if (localTrip?.remoteTripId != null) {
            syncPendingPointsForLocalTrip(
                localTripId = localTrip.localTripId,
                remoteTripId = localTrip.remoteTripId,
                token = token,
                baseUrl = baseUrl,
            )
        } else {
            syncPendingPointsForTrip(tripId, token, baseUrl)
        }

        when (pointsSyncResult) {
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
        return syncPendingPoints(
            remoteTripId = tripId,
            pendingPoints = pendingSyncStore.getPendingPointsForTrip(tripId),
            token = token,
            baseUrl = baseUrl,
        )
    }

    private suspend fun syncPendingPointsForLocalTrip(
        localTripId: String,
        remoteTripId: Int,
        token: String,
        baseUrl: String,
    ): SyncResult {
        return syncPendingPoints(
            remoteTripId = remoteTripId,
            pendingPoints = getPendingPointsForLocalTripOrLegacy(localTripId, remoteTripId),
            token = token,
            baseUrl = baseUrl,
        )
    }

    private suspend fun syncPendingPoints(
        remoteTripId: Int,
        pendingPoints: List<PendingTripPoint>,
        token: String,
        baseUrl: String,
    ): SyncResult {
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
                    tripId = remoteTripId,
                    request = TripPointRequest(
                        clientPointId = point.clientPointId,
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
                refreshPendingSyncUi(forceTripId = remoteTripId)
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
        val pendingSyncState = pendingSyncStore.getState()
        val pendingClose = pendingSyncState.pendingClose ?: return SyncResult.SUCCESS
        val localTrip = preferredCloseLocalTrip(pendingSyncState, pendingClose.tripId)
        val tripId = localTrip?.remoteTripId ?: pendingClose.tripId

        recordSyncAttempt()

        val pointsSyncResult = if (localTrip?.remoteTripId != null) {
            syncPendingPointsForLocalTrip(
                localTripId = localTrip.localTripId,
                remoteTripId = localTrip.remoteTripId,
                token = token,
                baseUrl = baseUrl,
            )
        } else {
            syncPendingPointsForTrip(tripId, token, baseUrl)
        }

        when (pointsSyncResult) {
            SyncResult.UNAUTHORIZED -> return SyncResult.UNAUTHORIZED
            SyncResult.RETRY_LATER -> return SyncResult.RETRY_LATER
            SyncResult.SUCCESS -> Unit
        }

        val hasPendingPoints = if (localTrip?.remoteTripId != null) {
            hasPendingPointsForLocalTripOrLegacy(localTrip.localTripId, localTrip.remoteTripId)
        } else {
            pendingSyncStore.hasPendingPointsForTrip(tripId)
        }
        if (hasPendingPoints) {
            refreshPendingSyncUi(forceTripId = tripId)
            return SyncResult.RETRY_LATER
        }

        val stopTripResult = runCatching {
            repository.stopTrip(baseUrl, token, tripId)
        }

        if (stopTripResult.isSuccess) {
            val closedAt = Instant.now().toString()
            if (localTrip != null) {
                pendingSyncStore.updateLocalTrip(localTrip.localTripId) {
                    it.copy(
                        endTime = it.endTime ?: closedAt,
                        status = PendingSyncStore.LOCAL_TRIP_STATUS_CLOSED,
                    )
                }
            } else {
                pendingSyncStore.markActiveLocalTripClosed(
                    remoteTripId = tripId,
                    endTime = closedAt,
                )
            }
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

    private suspend fun getPendingPointsForLocalTripOrLegacy(
        localTripId: String,
        remoteTripId: Int,
    ): List<PendingTripPoint> {
        val localTripPoints = pendingSyncStore.getPendingPointsForLocalTrip(localTripId)
        val legacyTripPoints = pendingSyncStore.getPendingPointsForTrip(remoteTripId)
            .filter { it.localTripId == null }
        return (localTripPoints + legacyTripPoints)
            .distinctBy { it.sequence }
            .sortedWith(compareBy<PendingTripPoint>({ it.timestamp }, { it.sequence }))
    }

    private suspend fun hasPendingPointsForLocalTripOrLegacy(
        localTripId: String,
        remoteTripId: Int,
    ): Boolean {
        return pendingSyncStore.hasPendingPointsForLocalTrip(localTripId) ||
            pendingSyncStore.getPendingPointsForTrip(remoteTripId).any { it.localTripId == null }
    }

    private fun preferredSyncLocalTrip(pendingSyncState: PendingSyncState): LocalTrip? {
        return pendingSyncState.activeLocalTripId?.let { activeLocalTripId ->
            pendingSyncState.localTrips.firstOrNull {
                it.localTripId == activeLocalTripId && it.remoteTripId != null
            }
        } ?: pendingSyncState.activeTripId?.let { activeTripId ->
            pendingSyncState.localTrips.firstOrNull {
                it.remoteTripId == activeTripId &&
                    it.status == PendingSyncStore.LOCAL_TRIP_STATUS_ACTIVE
            }
        }
    }

    private fun preferredCloseLocalTrip(
        pendingSyncState: PendingSyncState,
        pendingCloseTripId: Int,
    ): LocalTrip? {
        return preferredSyncLocalTrip(pendingSyncState)?.takeIf {
            it.remoteTripId == pendingCloseTripId
        } ?: pendingSyncState.localTrips.firstOrNull {
            it.remoteTripId == pendingCloseTripId &&
                it.status == PendingSyncStore.LOCAL_TRIP_STATUS_CLOSED
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
        val visibleVehicles = vehicles.filter { it.id in visibleVehicleIds }
        return when {
            visibleVehicles.size == 1 -> visibleVehicles.first().id
            visibleVehicleIds.isEmpty() && vehicles.size == 1 -> vehicles.first().id
            else -> null
        }
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

    private data class AcceptedPoint(
        val latitude: Double,
        val longitude: Double,
        val acceptedAt: Instant,
    )

    private enum class SyncResult {
        SUCCESS,
        RETRY_LATER,
        UNAUTHORIZED,
    }

    private companion object {
        private const val PENDING_CLOSE_RETRY_INTERVAL_MS = 15_000L
    }
}

class AppViewModelFactory(
    private val context: Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val sessionStore = SessionStore(context)
        return AppViewModel(
            appContext = context.applicationContext,
            sessionStore = sessionStore,
            pendingSyncStore = PendingSyncStore(context),
            trackingConfigStore = TrackingConfigStore(context),
            trackingStatusStore = TrackingStatusStore(context),
            repository = RastreoRepository(sessionStore),
            locationRepository = LocationRepository(context),
        ) as T
    }
}
