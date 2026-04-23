package com.rastreo.vehicular

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rastreo.vehicular.data.Vehicle
import com.rastreo.vehicular.ui.AppViewModel
import com.rastreo.vehicular.ui.AppViewModelFactory
import com.rastreo.vehicular.ui.UiState

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        AppViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val permissionsLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { result ->
                        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                        viewModel.onLocationPermissionResult(granted)
                    }

                    LaunchedEffect(Unit) {
                        viewModel.bootstrap()
                    }

                    AppScreen(
                        state = uiState,
                        onLogin = viewModel::login,
                        onLogout = viewModel::logout,
                        onBaseUrlChanged = viewModel::updateBaseUrlDraft,
                        onUseProductionUrl = viewModel::useProductionUrl,
                        onUseLocalUrl = viewModel::useLocalUrl,
                        onSaveBaseUrl = viewModel::saveBaseUrl,
                        onVehicleSelected = viewModel::selectVehicle,
                        onCategoryChanged = viewModel::updateCategory,
                        onStartTracking = {
                            permissionsLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        },
                        onStopTracking = viewModel::stopTracking,
                        onRefreshVehicles = viewModel::loadVehicles,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppScreen(
    state: UiState,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onUseProductionUrl: () -> Unit,
    onUseLocalUrl: () -> Unit,
    onSaveBaseUrl: () -> Unit,
    onVehicleSelected: (Int?) -> Unit,
    onCategoryChanged: (String) -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onRefreshVehicles: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Rastreo Vehicular V1",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        EnvironmentCard(
            state = state,
            onBaseUrlChanged = onBaseUrlChanged,
            onUseProductionUrl = onUseProductionUrl,
            onUseLocalUrl = onUseLocalUrl,
            onSaveBaseUrl = onSaveBaseUrl,
        )
        DiagnosticsCard(state = state)

        if (state.isBootstrapping) {
            LoadingCard("Restaurando sesion...")
        } else if (state.currentUser == null) {
            LoginCard(state = state, onLogin = onLogin)
        } else {
            SessionCard(state = state, onLogout = onLogout)
            VehicleCard(
                vehicles = state.vehicles,
                selectedVehicleId = state.selectedVehicleId,
                onVehicleSelected = onVehicleSelected,
                onRefreshVehicles = onRefreshVehicles,
            )
            TrackingCard(
                state = state,
                onCategoryChanged = onCategoryChanged,
                onStartTracking = onStartTracking,
                onStopTracking = onStopTracking,
            )
        }
    }
}

@Composable
private fun DiagnosticsCard(state: UiState) {
    val user = state.currentUser
    val selectedVehicle = state.vehicles.firstOrNull { it.id == state.selectedVehicleId }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Diagnostico", style = MaterialTheme.typography.titleMedium)
            Text("Estado general", fontWeight = FontWeight.Bold)
            DiagnosticRow("Sesion iniciada", if (user != null) "si" else "no")
            DiagnosticRow("Usuario", user?.let { it.fullName.ifBlank { it.username } } ?: "sin sesion")
            DiagnosticRow(
                "Vehiculo",
                selectedVehicle?.let { "${it.nombre} (${it.id})" }
                    ?: state.selectedVehicleId?.toString()
                    ?: "ninguno",
            )
            DiagnosticRow("Trip activo", if (state.currentTripId != null) "si" else "no")
            DiagnosticRow("Trip ID", state.currentTripId?.toString() ?: "sin iniciar")
            DiagnosticRow("Categoria", state.category.ifBlank { "sin categoria" })

            Text("Tracking", fontWeight = FontWeight.Bold)
            DiagnosticRow("Trackeando", if (state.isTracking) "si" else "no")
            DiagnosticRow("Ultimo intento lectura", state.lastLocationReadAttemptAt)
            DiagnosticRow("Ultima ubicacion obtenida", state.lastLocationObtainedAt)
            DiagnosticRow("Ultimo envio exitoso", state.lastSuccessfulSendAt)
            DiagnosticRow("Ultimo fallo de envio", state.lastFailedSendAt)
            DiagnosticRow("Intentos de envio", state.sendAttemptCount.toString())
            DiagnosticRow("Envios exitosos", state.sendSuccessCount.toString())
            DiagnosticRow("Fallos de envio", state.sendFailureCount.toString())

            Text("Ultima ubicacion", fontWeight = FontWeight.Bold)
            DiagnosticRow("Latitud", state.lastLatitude?.toString() ?: "sin dato")
            DiagnosticRow("Longitud", state.lastLongitude?.toString() ?: "sin dato")
            DiagnosticRow("Accuracy", state.lastAccuracy?.toString() ?: "sin dato")
            DiagnosticRow("Speed", state.lastSpeed?.toString() ?: "sin dato")
            DiagnosticRow("Timestamp", state.lastLocationTimestamp)

            Text("Filtro GPS", fontWeight = FontWeight.Bold)
            DiagnosticRow("Ultimo tipo de envio", state.lastSendType)
            DiagnosticRow(
                "Tiempo desde aceptado",
                state.secondsSinceLastAcceptedPoint?.toString() ?: "sin dato",
            )
            DiagnosticRow(
                "Descartes distancia desde aceptado",
                state.distanceMinimumDiscardCountSinceLastAccepted.toString(),
            )
            DiagnosticRow("Ultimo descarte", state.lastDiscardReason)
            DiagnosticRow(
                "Segundos desde aceptado",
                state.lastFilterElapsedSeconds?.toString() ?: "sin dato",
            )
            DiagnosticRow(
                "Distancia requerida",
                state.lastFilterRequiredDistanceMeters?.let { "${it.toInt()} m" } ?: "sin dato",
            )
            DiagnosticRow(
                "Distancia real",
                state.lastFilterActualDistanceMeters?.let { "${it.toInt()} m" } ?: "sin dato",
            )

            Text("Resultado operativo", fontWeight = FontWeight.Bold)
            Text(state.operationMessage, style = MaterialTheme.typography.bodyMedium)
            if (state.lastErrorMessage.isNotBlank()) {
                Text("Ultimo error", fontWeight = FontWeight.Bold)
                Text(state.lastErrorMessage, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EnvironmentCard(
    state: UiState,
    onBaseUrlChanged: (String) -> Unit,
    onUseProductionUrl: () -> Unit,
    onUseLocalUrl: () -> Unit,
    onSaveBaseUrl: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Backend", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.baseUrlDraft,
                onValueChange = onBaseUrlChanged,
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUseProductionUrl, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("Produccion")
                }
                Button(onClick = onUseLocalUrl, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("Local emulador")
                }
                Button(onClick = onSaveBaseUrl, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("Guardar")
                }
            }
            Text(
                text = "URL activa: ${state.baseUrl.ifBlank { "sin configurar" }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Render: usa la URL https. Emulador: http://10.0.2.2:8000/. Celular real: usa la IP LAN del backend, por ejemplo http://192.168.1.50:8000/.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LoginCard(
    state: UiState,
    onLogin: (String, String) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Login", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = { onLogin(username.trim(), password) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isBusy,
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text("Iniciar sesion")
                }
            }
            StatusText(state.statusMessage)
        }
    }
}

@Composable
private fun SessionCard(
    state: UiState,
    onLogout: () -> Unit,
) {
    val user = state.currentUser ?: return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Sesion", style = MaterialTheme.typography.titleMedium)
            Text("Usuario: ${user.fullName.ifBlank { user.username }}")
            Text("Rol: ${user.role}")
            Text("Puede editar trips: ${if (user.permissions["edit_trips"] == true) "si" else "no"}")
            Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text("Cerrar sesion")
            }
            StatusText(state.statusMessage)
        }
    }
}

@Composable
private fun VehicleCard(
    vehicles: List<Vehicle>,
    selectedVehicleId: Int?,
    onVehicleSelected: (Int?) -> Unit,
    onRefreshVehicles: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Vehiculos", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onRefreshVehicles, modifier = Modifier.fillMaxWidth()) {
                Text("Recargar vehiculos")
            }
            if (vehicles.isEmpty()) {
                Text("No hay vehiculos visibles para este usuario.")
            } else {
                vehicles.forEach { vehicle ->
                    val isSelected = vehicle.id == selectedVehicleId
                    Button(
                        onClick = { onVehicleSelected(vehicle.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (isSelected) {
                                "Seleccionado: ${vehicle.nombre} (${vehicle.id})"
                            } else {
                                "${vehicle.nombre} (${vehicle.id})"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackingCard(
    state: UiState,
    onCategoryChanged: (String) -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
) {
    val userCanTrack = state.currentUser?.permissions?.get("edit_trips") == true

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Tracking", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.category,
                onValueChange = onCategoryChanged,
                label = { Text("Categoria") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text("Vehiculo seleccionado: ${state.selectedVehicleId ?: "ninguno"}")
            Text("Tracking activo: ${if (state.isTracking) "si" else "no"}")
            Text("Trip actual: ${state.currentTripId ?: "sin iniciar"}")
            Text("Ultima ubicacion: ${state.lastLocationText}")
            Text("Ultimo intento: ${state.lastAttemptText}")
            Text("Permiso de ubicacion: ${if (state.hasLocationPermission) "otorgado" else "pendiente"}")
            if (!userCanTrack) {
                Text("Este usuario no tiene permiso edit_trips en el backend actual.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStartTracking,
                    enabled = userCanTrack && !state.isTracking && state.selectedVehicleId != null && !state.isBusy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("Iniciar")
                }
                Button(
                    onClick = onStopTracking,
                    enabled = state.currentTripId != null && !state.isBusy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("Detener")
                }
            }
            StatusText(state.statusMessage)
        }
    }
}

@Composable
private fun LoadingCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator()
            Text(message)
        }
    }
}

@Composable
private fun StatusText(message: String) {
    if (message.isBlank()) return
    Spacer(modifier = Modifier.height(4.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium)
}
