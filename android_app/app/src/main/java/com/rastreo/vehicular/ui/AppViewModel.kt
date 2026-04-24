package com.rastreo.vehicular.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rastreo.vehicular.BuildConfig
import com.rastreo.vehicular.data.RastreoRepository
import com.rastreo.vehicular.data.SessionStore
import com.rastreo.vehicular.data.TripPointRequest
import com.rastreo.vehicular.data.UserMeResponse
import com.rastreo.vehicular.data.Vehicle
import com.rastreo.vehicular.location.LocationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val repository: RastreoRepository,
    private val locationRepository: LocationRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        UiState(hasLocationPermission = locationRepository.hasLocationPermission())
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var trackingJob: Job? = null
    private var lastAcceptedPoint: AcceptedPoint? = null
    private var distanceMinimumDiscardCountSinceLastAccepted = 0

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
                _uiState.update {
                    it.copy(
                        isBootstrapping = false,
                        isSessionInvalid = false,
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
                        statusMessage = "Sesion restaurada.",
                        operationMessage = "Sesion restaurada.",
                        lastErrorMessage = "",
                    )
                }
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

            runCatching {
                val token = repository.login(baseUrl, username, password)
                sessionStore.saveBaseUrl(RastreoRepository.normalizeBaseUrl(baseUrl))
                sessionStore.saveToken(token.accessToken)
                val user = repository.me(baseUrl, token.accessToken)
                val vehicles = repository.listVehicles(baseUrl, token.accessToken)
                Triple(token.accessToken, user, vehicles)
            }.onSuccess { (_, user, vehicles) ->
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
                        statusMessage = "Sesion iniciada correctamente.",
                        operationMessage = "Sesion iniciada correctamente.",
                        lastErrorMessage = "",
                    )
                }
            }.onFailure {
                val message = it.message ?: "No se pudo iniciar sesion."
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
            lastAcceptedPoint = null
            distanceMinimumDiscardCountSinceLastAccepted = 0
            sessionStore.clearSession()
            _uiState.update {
                it.copy(
                    currentUser = null,
                    vehicles = emptyList(),
                    selectedVehicleId = null,
                    currentTripId = null,
                    isTracking = false,
                    isSessionInvalid = false,
                    pendingTripClose = false,
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
        if (uiState.value.isSessionInvalid) {
            val message = "Sesion expirada. El cierre del recorrido quedo pendiente."
            _uiState.update {
                it.copy(
                    pendingTripClose = true,
                    statusMessage = message,
                    operationMessage = "Cierre pendiente por sesion expirada.",
                    lastErrorMessage = message,
                )
            }
            return
        }

        trackingJob?.cancel()
        viewModelScope.launch {
            val token = sessionStore.sessionData.first().token
            _uiState.update {
                it.copy(
                    isBusy = true,
                    isTracking = false,
                    statusMessage = "Deteniendo trip $tripId...",
                )
            }

            val stopTripResult = runCatching {
                repository.stopTrip(uiState.value.baseUrl, token, tripId)
            }

            if (stopTripResult.isSuccess) {
                lastAcceptedPoint = null
                distanceMinimumDiscardCountSinceLastAccepted = 0
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
                if (isUnauthorizedError(error)) {
                    markSessionExpired(
                        statusMessage = "Sesion expirada. El cierre del recorrido quedo pendiente.",
                        operationMessage = "Cierre pendiente por sesion expirada.",
                        preserveTripId = tripId,
                        pendingTripClose = true,
                        clearUserContext = false,
                    )
                } else {
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
            val validation = validateLocationPoint(location, currentInstant)
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

            val sendPointResult = runCatching {
                repository.sendTripPoint(uiState.value.baseUrl, token, tripId, request)
            }

            if (sendPointResult.isSuccess) {
                val sentAt = Instant.now().toString()
                val sendType = if (shouldSendPermanencePoint) {
                    "Envio por permanencia"
                } else {
                    "Envio normal"
                }
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
                        pendingTripClose = false,
                        isSessionInvalid = false,
                        lastSendType = sendType,
                        lastErrorMessage = "",
                    )
                }
                lastAcceptedPoint = AcceptedPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    acceptedAt = currentInstant,
                )
                distanceMinimumDiscardCountSinceLastAccepted = 0
            } else {
                val error = sendPointResult.exceptionOrNull()
                val failedAt = Instant.now().toString()
                if (isUnauthorizedError(error)) {
                    markSessionExpired(
                        statusMessage = "Sesion expirada durante el tracking. Inicia sesion nuevamente para cerrar o continuar.",
                        operationMessage = "Tracking detenido por sesion expirada.",
                        preserveTripId = tripId,
                        pendingTripClose = true,
                        clearUserContext = false,
                        failedAt = failedAt,
                        lastLocationText = "${location.latitude}, ${location.longitude}",
                        incrementSendFailure = true,
                    )
                } else {
                    val message = if (isNetworkError(error)) {
                        "No se pudo enviar la ubicacion por problema de conexion."
                    } else {
                        error?.message ?: "No se pudo enviar la ubicacion."
                    }
                    _uiState.update { state ->
                        state.copy(
                            lastLocationText = "${location.latitude}, ${location.longitude}",
                            statusMessage = message,
                            operationMessage = "Fallo envio.",
                            lastFailedSendAt = failedAt,
                            sendFailureCount = state.sendFailureCount + 1,
                            lastErrorMessage = message,
                        )
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
        return error is IOException
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
        sessionStore.clearSession()
        lastAcceptedPoint = null
        distanceMinimumDiscardCountSinceLastAccepted = 0

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

    private fun chooseVehicle(visibleVehicleIds: List<Int>, vehicles: List<Vehicle>): Int? {
        return vehicles.firstOrNull { it.id in visibleVehicleIds }?.id ?: vehicles.firstOrNull()?.id
    }

    private fun validateLocationPoint(
        location: com.rastreo.vehicular.location.LocationSample,
        currentInstant: Instant,
    ): PointValidation {
        val accuracy = location.accuracy
        val requiredDistance = accuracy?.times(2)?.toDouble()
        val previous = lastAcceptedPoint ?: return PointValidation(
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

    private companion object {
        private const val MAX_ACCEPTED_ACCURACY_METERS = 100f
        private const val MIN_SECONDS_BETWEEN_ACCEPTED_POINTS = 5L
        private const val PERMANENCE_POINT_SECONDS = 120L
        private const val MIN_DISTANCE_DISCARDS_FOR_PERMANENCE = 2
    }
}

class AppViewModelFactory(
    private val context: Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppViewModel(
            sessionStore = SessionStore(context),
            repository = RastreoRepository(),
            locationRepository = LocationRepository(context),
        ) as T
    }
}
