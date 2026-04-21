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
import java.time.Instant

data class UiState(
    val isBootstrapping: Boolean = true,
    val isBusy: Boolean = false,
    val currentUser: UserMeResponse? = null,
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicleId: Int? = null,
    val category: String = "trabajo",
    val currentTripId: Int? = null,
    val isTracking: Boolean = false,
    val lastLocationText: String = "Sin dato",
    val lastAttemptText: String = "Sin intento",
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
                        statusMessage = "Sesion no iniciada.",
                    )
                }
                return@launch
            }

            runCatching {
                val user = repository.me(baseUrl, session.token)
                val vehicles = repository.listVehicles(baseUrl, session.token)
                _uiState.update {
                    it.copy(
                        isBootstrapping = false,
                        currentUser = user,
                        vehicles = vehicles,
                        selectedVehicleId = chooseVehicle(user.vehicleIds, vehicles),
                        statusMessage = "Sesion restaurada.",
                    )
                }
            }.onFailure {
                sessionStore.clearSession()
                _uiState.update {
                    it.copy(
                        isBootstrapping = false,
                        currentUser = null,
                        vehicles = emptyList(),
                        selectedVehicleId = null,
                        statusMessage = "No se pudo restaurar la sesion. Vuelve a iniciar.",
                    )
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
                    )
                }
            }.onFailure {
                _uiState.update { state ->
                    state.copy(statusMessage = it.message ?: "Base URL invalida.")
                }
            }
        }
    }

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Username y password son obligatorios.") }
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
                        statusMessage = "Sesion iniciada correctamente.",
                    )
                }
            }.onFailure {
                _uiState.update { state ->
                    state.copy(
                        isBusy = false,
                        isBootstrapping = false,
                        statusMessage = it.message ?: "No se pudo iniciar sesion.",
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            trackingJob?.cancel()
            sessionStore.clearSession()
            _uiState.update {
                it.copy(
                    currentUser = null,
                    vehicles = emptyList(),
                    selectedVehicleId = null,
                    currentTripId = null,
                    isTracking = false,
                    lastLocationText = "Sin dato",
                    lastAttemptText = "Sin intento",
                    statusMessage = "Sesion cerrada.",
                )
            }
        }
    }

    fun loadVehicles() {
        val user = uiState.value.currentUser ?: return

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
                    )
                }
            }.onFailure {
                _uiState.update { state ->
                    state.copy(
                        isBusy = false,
                        statusMessage = it.message ?: "No se pudieron cargar vehiculos.",
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
            _uiState.update { it.copy(statusMessage = "Permiso de ubicacion denegado.") }
            return
        }
        startTracking()
    }

    private fun startTracking() {
        val state = uiState.value
        val vehicleId = state.selectedVehicleId
        val user = state.currentUser
        if (user == null) return
        if (user.permissions["edit_trips"] != true) {
            _uiState.update {
                it.copy(statusMessage = "El backend actual no permite tracking para este usuario.")
            }
            return
        }
        if (vehicleId == null) {
            _uiState.update { it.copy(statusMessage = "Selecciona un vehiculo primero.") }
            return
        }
        if (state.category.isBlank()) {
            _uiState.update { it.copy(statusMessage = "La categoria es obligatoria.") }
            return
        }

        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            val token = sessionStore.sessionData.first().token
            _uiState.update { it.copy(isBusy = true, statusMessage = "Iniciando tracking...") }
            runCatching {
                repository.startTrip(uiState.value.baseUrl, token, vehicleId, state.category.trim())
            }.onSuccess { trip ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        currentTripId = trip.id,
                        isTracking = true,
                        statusMessage = "Trip ${trip.id} iniciado. Enviando ubicacion...",
                    )
                }
                trackingLoop(trip.id, token)
            }.onFailure {
                _uiState.update { current ->
                    current.copy(
                        isBusy = false,
                        isTracking = false,
                        currentTripId = null,
                        statusMessage = it.message ?: "No se pudo iniciar el trip.",
                    )
                }
            }
        }
    }

    fun stopTracking() {
        val tripId = uiState.value.currentTripId ?: run {
            _uiState.update { it.copy(statusMessage = "No hay trip activo.") }
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

            runCatching {
                repository.stopTrip(uiState.value.baseUrl, token, tripId)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        currentTripId = null,
                        isTracking = false,
                        statusMessage = "Trip $tripId detenido.",
                    )
                }
            }.onFailure {
                _uiState.update { state ->
                    state.copy(
                        isBusy = false,
                        currentTripId = tripId,
                        statusMessage = it.message ?: "No se pudo cerrar el trip en backend.",
                    )
                }
            }
        }
    }

    private suspend fun trackingLoop(tripId: Int, token: String) {
        while (uiState.value.isTracking) {
            val timestamp = Instant.now().toString()
            _uiState.update { it.copy(lastAttemptText = timestamp) }

            val location = locationRepository.getCurrentLocation()
            if (location == null) {
                _uiState.update {
                    it.copy(statusMessage = "No se pudo obtener ubicacion GPS en este intento.")
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

            runCatching {
                repository.sendTripPoint(uiState.value.baseUrl, token, tripId, request)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        lastLocationText = "${location.latitude}, ${location.longitude}",
                        statusMessage = "Ubicacion enviada correctamente.",
                    )
                }
            }.onFailure {
                _uiState.update { state ->
                    state.copy(
                        lastLocationText = "${location.latitude}, ${location.longitude}",
                        statusMessage = it.message ?: "No se pudo enviar la ubicacion.",
                    )
                }
            }

            kotlinx.coroutines.delay(BuildConfig.TRACKING_INTERVAL_MS)
        }
    }

    private fun chooseVehicle(visibleVehicleIds: List<Int>, vehicles: List<Vehicle>): Int? {
        return vehicles.firstOrNull { it.id in visibleVehicleIds }?.id ?: vehicles.firstOrNull()?.id
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
