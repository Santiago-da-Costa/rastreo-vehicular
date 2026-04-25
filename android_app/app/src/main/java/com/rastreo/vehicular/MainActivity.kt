package com.rastreo.vehicular

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rastreo.vehicular.ui.AppViewModel
import com.rastreo.vehicular.ui.AppViewModelFactory
import com.rastreo.vehicular.ui.UiState
import kotlin.math.max
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

private val TRACKING_CATEGORIES = listOf("Trabajo", "Personal", "Mantenimiento", "Otro")

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
                            if (viewModel.validateTrackingSelection()) {
                                permissionsLauncher.launch(buildTrackingPermissionsRequest())
                            }
                        },
                        onDecreaseGpsObservationInterval = {
                            viewModel.setGpsObservationIntervalSeconds(
                                max(1L, uiState.gpsObservationIntervalMs / 1000L - 1L)
                            )
                        },
                        onIncreaseGpsObservationInterval = {
                            viewModel.setGpsObservationIntervalSeconds(
                                uiState.gpsObservationIntervalMs / 1000L + 1L
                            )
                        },
                        onDecreaseEvaluationInterval = {
                            viewModel.setEvaluationIntervalSeconds(
                                max(1L, uiState.evaluationIntervalMs / 1000L - 1L)
                            )
                        },
                        onIncreaseEvaluationInterval = {
                            viewModel.setEvaluationIntervalSeconds(
                                uiState.evaluationIntervalMs / 1000L + 1L
                            )
                        },
                        onResetTrackingConfigDefaults = viewModel::resetTrackingConfigDefaults,
                        onStopTracking = viewModel::stopTracking,
                        onRefreshVehicles = viewModel::loadVehicles,
                    )
                }
            }
        }
    }
}

private fun buildTrackingPermissionsRequest(): Array<String> {
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
    }
    return permissions.toTypedArray()
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
    onDecreaseGpsObservationInterval: () -> Unit,
    onIncreaseGpsObservationInterval: () -> Unit,
    onDecreaseEvaluationInterval: () -> Unit,
    onIncreaseEvaluationInterval: () -> Unit,
    onResetTrackingConfigDefaults: () -> Unit,
    onStopTracking: () -> Unit,
    onRefreshVehicles: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val currentTimeMillis by rememberCurrentTimeMillis()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.isBootstrapping) {
            LoadingCard("Restaurando sesion...")
        } else if (state.currentUser == null) {
            AppHeader(name = "OrdenaTrack", subtitle = "Inicia sesion para continuar")
            EnvironmentCard(
                state = state,
                onBaseUrlChanged = onBaseUrlChanged,
                onUseProductionUrl = onUseProductionUrl,
                onUseLocalUrl = onUseLocalUrl,
                onSaveBaseUrl = onSaveBaseUrl,
            )
            LoginCard(state = state, onLogin = onLogin)
        } else {
            val canViewDebugPanel = state.currentUser.permissions["view_debug_panel"] == true
            AppHeader(
                name = "OrdenaTrack",
                subtitle = "Bienvenido, ${resolveUserDisplayName(state)}",
                onLogout = onLogout,
            )
            TripSummaryCard(state = state)
            MainControlsCard(
                state = state,
                onVehicleSelected = onVehicleSelected,
                onCategoryChanged = onCategoryChanged,
                onStartTracking = onStartTracking,
                onStopTracking = onStopTracking,
                onRefreshVehicles = onRefreshVehicles,
            )
            OperationalStatusCard(state = state)
            if (canViewDebugPanel) {
                DeveloperDiagnosticsCard(
                    state = state,
                    currentTimeMillis = currentTimeMillis,
                    onDecreaseGpsObservationInterval = onDecreaseGpsObservationInterval,
                    onIncreaseGpsObservationInterval = onIncreaseGpsObservationInterval,
                    onDecreaseEvaluationInterval = onDecreaseEvaluationInterval,
                    onIncreaseEvaluationInterval = onIncreaseEvaluationInterval,
                    onResetTrackingConfigDefaults = onResetTrackingConfigDefaults,
                )
            }
        }
    }
}

@Composable
private fun AppHeader(
    name: String,
    subtitle: String,
    onLogout: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "OT",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            if (onLogout != null) {
                OutlinedButton(onClick = onLogout) {
                    Text("Salir")
                }
            }
        }
    }
}

@Composable
private fun TripSummaryCard(state: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (state.currentTripId != null) "Recorrido activo" else "Recorrido desactivado",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            SummaryValueRow(
                label = "Posiciones registradas",
                value = state.sendSuccessCount.toString(),
            )
            SummaryValueRow(
                label = "Ultima latitud",
                value = formatCoordinate(state.lastAcceptedLatitude),
                show = state.lastAcceptedLatitude != null,
            )
            SummaryValueRow(
                label = "Ultima longitud",
                value = formatCoordinate(state.lastAcceptedLongitude),
                show = state.lastAcceptedLongitude != null,
            )
            SummaryValueRow(
                label = "Precision del ultimo punto",
                value = formatAccuracy(state.lastAcceptedAccuracy),
                show = state.lastAcceptedAccuracy != null,
            )
            SummaryValueRow(
                label = "Inicio",
                value = formatTimestamp(state.trackingStartedAt),
            )
            SummaryValueRow(
                label = "Categoria",
                value = state.category.ifBlank { "Sin categoria" },
            )
            SummaryValueRow(
                label = "Velocidad actual",
                value = formatSpeed(state.lastSpeed),
            )
        }
    }
}

@Composable
private fun SummaryValueRow(
    label: String,
    value: String,
    show: Boolean = true,
) {
    if (!show) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainControlsCard(
    state: UiState,
    onVehicleSelected: (Int?) -> Unit,
    onCategoryChanged: (String) -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onRefreshVehicles: () -> Unit,
) {
    val userCanTrack = state.currentUser?.permissions?.get("edit_trips") == true
    val isTrackingActive = state.currentTripId != null
    var vehicleExpanded by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Controles principales", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (isTrackingActive) {
                            onStopTracking()
                        } else {
                            onStartTracking()
                        }
                    },
                    enabled = if (isTrackingActive) !state.isBusy else userCanTrack && !state.isBusy,
                    modifier = Modifier.size(140.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTrackingActive) Color(0xFFB3261E) else Color(0xFF2E7D32),
                    ),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    Text(
                        text = if (isTrackingActive) "DETENER" else "INICIAR",
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ExposedDropdownMenuBox(
                        expanded = vehicleExpanded,
                        onExpandedChange = { vehicleExpanded = !vehicleExpanded },
                    ) {
                        OutlinedTextField(
                            value = selectedVehicleLabel(state),
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            readOnly = true,
                            singleLine = true,
                            label = { Text("Vehiculo") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = vehicleExpanded)
                            },
                        )
                        ExposedDropdownMenu(
                            expanded = vehicleExpanded,
                            onDismissRequest = { vehicleExpanded = false },
                        ) {
                            if (state.vehicles.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Sin vehiculos") },
                                    onClick = { vehicleExpanded = false },
                                )
                            } else {
                                state.vehicles.forEach { vehicle ->
                                    DropdownMenuItem(
                                        text = { Text("${vehicle.nombre} (${vehicle.id})") },
                                        onClick = {
                                            onVehicleSelected(vehicle.id)
                                            vehicleExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = categoryExpanded,
                        onExpandedChange = { categoryExpanded = !categoryExpanded },
                    ) {
                        OutlinedTextField(
                            value = state.category.ifBlank { "Selecciona una categoria" },
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            readOnly = true,
                            singleLine = true,
                            label = { Text("Categoria") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded)
                            },
                        )
                        ExposedDropdownMenu(
                            expanded = categoryExpanded,
                            onDismissRequest = { categoryExpanded = false },
                        ) {
                            TRACKING_CATEGORIES.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category) },
                                    onClick = {
                                        onCategoryChanged(category)
                                        categoryExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onRefreshVehicles, enabled = !state.isBusy) {
                    Text("Recargar vehiculos")
                }
                Text(
                    text = if (userCanTrack || isTrackingActive) {
                        buildSelectionHint(state)
                    } else {
                        "Este usuario no tiene permiso para iniciar recorridos."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OperationalStatusCard(state: UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Mensajes operativos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            StatusText(resolveOperationalMessage(state))
        }
    }
}

@Composable
private fun DeveloperDiagnosticsCard(
    state: UiState,
    currentTimeMillis: Long,
    onDecreaseGpsObservationInterval: () -> Unit,
    onIncreaseGpsObservationInterval: () -> Unit,
    onDecreaseEvaluationInterval: () -> Unit,
    onIncreaseEvaluationInterval: () -> Unit,
    onResetTrackingConfigDefaults: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Diagnostico avanzado", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            SummarySection(
                title = "Posiciones rechazadas",
                lines = listOf(
                    "Cantidad: ${state.rejectedPositionsCount}",
                    "Ultimo motivo: ${state.lastDiscardReason.takeIf { it != "Sin descarte" } ?: "Sin dato"}",
                    "Ultimo rechazo: ${formatTimestamp(state.lastRejectedAt)}",
                ),
            )
            SummarySection(
                title = "Posiciones en cola pendientes",
                lines = listOf(
                    "Puntos pendientes: ${state.pendingPointCount}",
                    "Estado de sync: ${state.lastSyncError}",
                ),
            )
            SummarySection(
                title = "Tiempos",
                lines = listOf(
                    "Desde ultimo punto aceptado: ${formatElapsedSince(state.lastSuccessfulSendAt, currentTimeMillis)}",
                    "Desde ultimo punto rechazado: ${formatElapsedSince(state.lastRejectedAt, currentTimeMillis)}",
                ),
            )
            SummarySection(
                title = "Diagnostico GPS",
                lines = listOf(
                    "Ultima muestra observada: ${formatTimestamp(state.lastLocationTimestamp)}",
                    "Muestras observadas: ${state.gpsReadCount}",
                    "Evaluaciones sin muestra disponible: ${state.gpsNullCount}",
                    "Evaluaciones lentas (>3 s): ${state.gpsSlowReadCount}",
                    "Evaluaciones muy lentas (>8 s): ${state.gpsVerySlowReadCount}",
                    "Ultima evaluacion finalizada: ${formatTimestamp(state.loopFinishedAt)}",
                    "Duracion ultima evaluacion: ${formatMillis(state.loopDurationMs)}",
                    "Provider: ${state.lastLocationProvider.ifBlank { "No disponible" }}",
                    "Edad estimada del fix: ${formatMillis(state.lastLocationAgeMs)}",
                    "Precision actual GPS: ${formatAccuracy(state.lastAccuracy)}",
                    "Velocidad actual GPS: ${formatSpeed(state.lastSpeed)}",
                ),
            )
            SummarySection(
                title = "Parametros de tracking",
                lines = listOf("Estos parametros son de prueba para el modo desarrollador."),
            )
            AdjustableTrackingParameterRow(
                label = "Frecuencia observacion GPS",
                value = formatSeconds(state.gpsObservationIntervalMs),
                onDecrease = onDecreaseGpsObservationInterval,
                onIncrease = onIncreaseGpsObservationInterval,
            )
            AdjustableTrackingParameterRow(
                label = "Frecuencia evaluacion/registro",
                value = formatSeconds(state.evaluationIntervalMs),
                onDecrease = onDecreaseEvaluationInterval,
                onIncrease = onIncreaseEvaluationInterval,
            )
            OutlinedButton(onClick = onResetTrackingConfigDefaults) {
                Text("Restaurar valores por defecto")
            }
            SummarySection(
                title = "Filtro GPS",
                lines = listOf(
                    "Distancia al ultimo punto aceptado: ${formatDistance(state.lastFilterActualDistanceMeters)}",
                    "Distancia necesaria para aceptar: ${formatRequiredDistance(state)}",
                    "Tiempo minimo para almacenar puntos: 5 s",
                    "Precision maxima exigida: 100 m",
                    "Precision actual GPS: ${formatAccuracy(state.lastAccuracy)}",
                    "Precision ultimo punto aceptado: ${formatAccuracy(state.lastAcceptedAccuracy)}",
                ),
            )
            SummarySection(
                title = "Estado del sistema",
                lines = listOf(
                    "serviceRunning: ${formatBoolean(state.serviceRunning)}",
                    "trackingActive: ${formatBoolean(state.isTracking)}",
                    "currentTripId: ${state.currentTripId?.toString() ?: "Sin dato"}",
                    "Ultimo error operativo: ${state.lastErrorMessage.ifBlank { "Sin dato" }}",
                    "Ultimo mensaje operativo: ${state.operationMessage.ifBlank { "Sin dato" }}",
                ),
            )
        }
    }
}

@Composable
private fun SummarySection(
    title: String,
    lines: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AdjustableTrackingParameterRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onDecrease, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Text("-")
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onIncrease, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Text("+")
        }
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
                Button(
                    onClick = onUseProductionUrl,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("Produccion")
                }
                Button(
                    onClick = onUseLocalUrl,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("Local emulador")
                }
                Button(
                    onClick = onSaveBaseUrl,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("Guardar")
                }
            }
            Text(
                text = "URL activa: ${state.baseUrl.ifBlank { "sin configurar" }}",
                style = MaterialTheme.typography.bodyMedium,
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
private fun LoadingCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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

@Composable
private fun rememberCurrentTimeMillis() = produceState(initialValue = System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1_000L)
    }
}

private fun resolveUserDisplayName(state: UiState): String {
    val user = state.currentUser ?: return "Usuario"
    return user.fullName.ifBlank { user.email ?: user.username }
}

private fun selectedVehicleLabel(state: UiState): String {
    val selectedVehicle = state.vehicles.firstOrNull { it.id == state.selectedVehicleId }
    return selectedVehicle?.let { "${it.nombre} (${it.id})" } ?: "Selecciona un vehiculo"
}

private fun formatCoordinate(value: Double?): String {
    return value?.let { String.format("%.6f", it) } ?: "Sin dato"
}

private fun formatAccuracy(value: Float?): String {
    return value?.let { "${it.toInt()} m" } ?: "Sin dato"
}

private fun formatSpeed(value: Float?): String {
    val kmh = value?.times(3.6f)
    return kmh?.let { String.format("%.1f km/h", it) } ?: "Sin dato"
}

private fun formatDistance(value: Double?): String {
    return value?.let { "${it.toInt()} m" } ?: "Sin dato"
}

private fun formatMillis(value: Long?): String {
    return value?.let { "${it} ms" } ?: "No disponible"
}

private fun formatSeconds(value: Long): String {
    return String.format("%.1f s", value / 1000.0)
}

private fun formatRequiredDistance(state: UiState): String {
    val requiredDistance = state.lastFilterRequiredDistanceMeters ?: state.lastAccuracy?.times(2)?.toDouble()
    return formatDistance(requiredDistance)
}

private fun formatBoolean(value: Boolean): String {
    return if (value) "si" else "no"
}

private fun buildSelectionHint(state: UiState): String {
    return when {
        state.currentTripId != null -> "Recorrido en curso."
        state.selectedVehicleId == null && state.category.isBlank() -> "Seleccione vehiculo y categoria."
        state.selectedVehicleId == null -> "Seleccione un vehiculo."
        state.category.isBlank() -> "Seleccione una categoria."
        else -> "Listo para iniciar el recorrido."
    }
}

private fun resolveOperationalMessage(state: UiState): String {
    val statusMessage = state.statusMessage.trim()
    val lastErrorMessage = state.lastErrorMessage.trim()

    if (state.isSessionInvalid) {
        return "Sesion expirada. Inicie sesion nuevamente."
    }
    if (state.isBusy) {
        return if (state.currentTripId != null && !state.isTracking) {
            "Deteniendo recorrido..."
        } else {
            "Iniciando recorrido..."
        }
    }
    if (lastErrorMessage.isNotBlank()) {
        return simplifyOperationalMessage(lastErrorMessage)
    }
    if (statusMessage.contains("Recorrido cerrado", ignoreCase = true)) {
        return "Recorrido detenido."
    }
    if (
        statusMessage.contains("trip", ignoreCase = true) &&
        statusMessage.contains("iniciado", ignoreCase = true)
    ) {
        return "Recorrido iniciado."
    }
    if (state.currentTripId != null) {
        return "Recorrido iniciado."
    }
    if (statusMessage.contains("Sesion restaurada", ignoreCase = true)) {
        return buildSelectionHint(state)
    }
    return simplifyOperationalMessage(statusMessage.ifBlank { buildSelectionHint(state) })
}

private fun simplifyOperationalMessage(message: String): String {
    if (message.isBlank()) {
        return "Seleccione vehiculo y categoria."
    }
    return when {
        message.contains("conexion", ignoreCase = true) ->
            "Problema de conexion. Intente nuevamente."
        message.contains("sesion expirada", ignoreCase = true) ->
            "Sesion expirada. Inicie sesion nuevamente."
        message.contains("sesion no iniciada", ignoreCase = true) ->
            "Inicie sesion para continuar."
        message.contains("tracking para este usuario", ignoreCase = true) ||
            message.contains("no tiene permiso", ignoreCase = true) ->
            "Este usuario no tiene permiso para iniciar recorridos."
        message.contains("vehiculo", ignoreCase = true) &&
            message.contains("categoria", ignoreCase = true) ->
            "Seleccione vehiculo y categoria."
        message.contains("vehiculo", ignoreCase = true) ->
            "Seleccione un vehiculo."
        message.contains("categoria", ignoreCase = true) ->
            "Seleccione una categoria."
        message.contains("cierre pendiente", ignoreCase = true) ->
            "El recorrido se detuvo y queda pendiente la sincronizacion."
        message.contains("base url", ignoreCase = true) ->
            "Configuracion actualizada."
        message.contains("login", ignoreCase = true) ->
            "Complete sus credenciales para continuar."
        else -> message
    }
}

private fun formatTimestamp(timestamp: String): String {
    if (timestamp.isBlank() || timestamp == "Sin dato" || timestamp == "Sin intento") {
        return "Sin dato"
    }
    return runCatching {
        Instant.parse(timestamp).toString().replace("T", " ").removeSuffix("Z")
    }.getOrElse { timestamp }
}

private fun formatElapsedSince(timestamp: String, currentTimeMillis: Long): String {
    if (timestamp.isBlank() || timestamp == "Sin dato" || timestamp == "Sin intento") {
        return "Sin dato"
    }
    val instant = try {
        Instant.parse(timestamp)
    } catch (_: DateTimeParseException) {
        return timestamp
    }
    val duration = Duration.between(instant, Instant.ofEpochMilli(currentTimeMillis))
    if (duration.isNegative) {
        return "0m 0s"
    }
    val minutes = duration.toMinutes()
    val seconds = duration.seconds % 60
    return "${minutes}m ${seconds}s"
}
