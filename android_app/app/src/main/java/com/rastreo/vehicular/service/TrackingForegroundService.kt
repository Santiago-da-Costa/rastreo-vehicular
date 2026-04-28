package com.rastreo.vehicular.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rastreo.vehicular.BuildConfig
import com.rastreo.vehicular.MainActivity
import com.rastreo.vehicular.data.LocalTrip
import com.rastreo.vehicular.data.PendingSyncState
import com.rastreo.vehicular.data.PendingSyncStore
import com.rastreo.vehicular.data.PendingTripPoint
import com.rastreo.vehicular.data.PendingTripPointDraft
import com.rastreo.vehicular.data.RastreoRepository
import com.rastreo.vehicular.data.RefreshTransientException
import com.rastreo.vehicular.data.SessionStore
import com.rastreo.vehicular.data.TrackingConfigStore
import com.rastreo.vehicular.data.TrackingStatus
import com.rastreo.vehicular.data.TrackingStatusStore
import com.rastreo.vehicular.data.TripPointRequest
import com.rastreo.vehicular.location.LocationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking

class TrackingForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingCloseSyncMutex = Mutex()

    private lateinit var sessionStore: SessionStore
    private lateinit var pendingSyncStore: PendingSyncStore
    private lateinit var trackingConfigStore: TrackingConfigStore
    private lateinit var repository: RastreoRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var trackingStatusStore: TrackingStatusStore

    private var trackingJob: Job? = null
    private var locationObservationJob: Job? = null
    private var activeTripId: Int? = null
    private var lastAcceptedPoint: AcceptedPoint? = null
    private var latestObservedLocation: com.rastreo.vehicular.location.LocationSample? = null
    private var distanceMinimumDiscardCountSinceLastAccepted = 0

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(applicationContext)
        pendingSyncStore = PendingSyncStore(applicationContext)
        trackingConfigStore = TrackingConfigStore(applicationContext)
        repository = RastreoRepository(sessionStore)
        locationRepository = LocationRepository(applicationContext)
        trackingStatusStore = TrackingStatusStore(applicationContext)
        createNotificationChannel()
        updateTrackingStatusAsync {
            it.copy(
                serviceRunning = true,
                lastOperationalMessage = "Foreground service iniciado.",
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                val tripId = intent.getIntExtra(EXTRA_TRIP_ID, INVALID_TRIP_ID)
                val isRecovery = intent.getBooleanExtra(EXTRA_IS_RECOVERY, false)
                val category = intent.getStringExtra(EXTRA_CATEGORY)?.trim().orEmpty()
                val localTripId = intent.getStringExtra(EXTRA_LOCAL_TRIP_ID)
                if (tripId == INVALID_TRIP_ID) {
                    Log.w(TAG, "Ignoring start without valid trip id")
                    updateTrackingStatusAsync {
                        it.copy(
                            serviceRunning = true,
                            lastOperationalMessage = "Intento de inicio ignorado por trip invalido.",
                        )
                    }
                } else {
                    updateTrackingStatusAsync {
                        if (isRecovery) {
                            if (it.currentTripId == tripId) {
                                it.copy(
                                    serviceRunning = true,
                                    trackingActive = false,
                                    currentTripId = tripId,
                                    lastOperationalMessage = "Service listo para reanudar tracking.",
                                )
                            } else {
                                it.copy(
                                    serviceRunning = true,
                                    trackingActive = false,
                                    currentTripId = tripId,
                                    category = category.ifBlank { it.category },
                                    lastOperationalMessage = "Service listo para reanudar tracking.",
                                )
                            }
                        } else {
                            TrackingStatus(
                                serviceRunning = true,
                                trackingActive = false,
                                currentTripId = tripId,
                                category = category,
                                lastOperationalMessage = "Service listo para iniciar tracking.",
                            )
                        }
                    }
                    startTrackingLoop(
                        tripId = tripId,
                        isRecovery = isRecovery,
                        requestedCategory = category,
                        localTripId = localTripId,
                    )
                }
            }

            ACTION_STOP_TRACKING -> stopTrackingLoop()
            else -> {
                updateTrackingStatusAsync {
                    it.copy(
                        serviceRunning = true,
                        lastOperationalMessage = "Foreground service reanudado.",
                    )
                }
                serviceScope.launch {
                    resumeTrackingIfNeeded()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runBlocking {
            trackingStatusStore.updateStatus {
                it.copy(
                    serviceRunning = false,
                    trackingActive = false,
                    lastOperationalMessage = "Foreground service destruido.",
                )
            }
        }
        trackingJob?.cancel()
        locationObservationJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTrackingLoop(
        tripId: Int,
        isRecovery: Boolean = false,
        requestedCategory: String = "",
        localTripId: String? = null,
    ) {
        if (trackingJob?.isActive == true) {
            Log.i(TAG, "Tracking loop already active for trip $activeTripId. Ignoring new start for $tripId")
            updateTrackingStatusAsync {
                it.copy(
                    serviceRunning = true,
                    trackingActive = true,
                    currentTripId = activeTripId ?: tripId,
                    lastOperationalMessage = "Intento duplicado de inicio ignorado.",
                )
            }
            return
        }

        activeTripId = tripId
        lastAcceptedPoint = null
        latestObservedLocation = null
        distanceMinimumDiscardCountSinceLastAccepted = 0
        trackingJob = serviceScope.launch {
            try {
                Log.i(TAG, "Starting tracking loop for trip $tripId localTripId=$localTripId")
                val startedAt = Instant.now().toString()
                val config = trackingConfigStore.getState()
                updateTrackingStatus { previous ->
                    if (isRecovery && previous.currentTripId == tripId) {
                        previous.copy(
                            serviceRunning = true,
                            trackingActive = true,
                            currentTripId = tripId,
                            category = previous.category.ifBlank { requestedCategory },
                            lastOperationalMessage = "Tracking activo.",
                        )
                    } else {
                        TrackingStatus(
                            serviceRunning = true,
                            trackingActive = true,
                            currentTripId = tripId,
                            category = requestedCategory,
                            trackingStartedAt = startedAt,
                            lastOperationalMessage = "Tracking activo.",
                        )
                    }
                }
                startLocationObservation(
                    tripId = tripId,
                    observationIntervalMs = config.gpsObservationIntervalMs,
                )
                evaluationLoop(
                    tripId = tripId,
                    evaluationIntervalMs = config.evaluationIntervalMs,
                    localTripId = localTripId,
                )
            } catch (_: CancellationException) {
                Log.i(TAG, "Tracking loop cancelled for trip $tripId")
            } catch (error: Throwable) {
                Log.e(TAG, "Tracking loop failed for trip $tripId", error)
                updateTrackingStatus {
                    it.copy(
                        serviceRunning = true,
                        trackingActive = false,
                        currentTripId = tripId,
                        lastOperationalMessage = "Tracking detenido por error interno.",
                    )
                }
            } finally {
                stopLocationObservation()
                if (activeTripId == tripId) {
                    activeTripId = null
                }
                trackingJob = null
            }
        }
    }

    private fun stopTrackingLoop() {
        if (trackingJob?.isActive != true) {
            Log.i(TAG, "Stop requested with no active tracking loop")
        } else {
            Log.i(TAG, "Stopping tracking loop for trip $activeTripId")
        }
        trackingJob?.cancel()
        trackingJob = null
        serviceScope.launch {
            stopLocationObservation()
        }
        latestObservedLocation = null
        activeTripId = null
        lastAcceptedPoint = null
        distanceMinimumDiscardCountSinceLastAccepted = 0
        updateTrackingStatusAsync {
            it.copy(
                serviceRunning = false,
                trackingActive = false,
                currentTripId = null,
                lastOperationalMessage = "Tracking detenido.",
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun resumeTrackingIfNeeded() {
        if (trackingJob?.isActive == true) {
            return
        }

        val pendingSyncState = pendingSyncStore.getState()
        val resumableTripId = pendingSyncState.activeTripId
            ?.takeIf { pendingSyncState.pendingClose == null }

        if (resumableTripId == null) {
            Log.i(TAG, "No active trip to resume in foreground service")
            updateTrackingStatus {
                it.copy(
                    serviceRunning = false,
                    trackingActive = false,
                    lastOperationalMessage = "Sin tracking activo para reanudar.",
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        Log.i(TAG, "Resuming tracking loop for persisted trip $resumableTripId")
        startTrackingLoop(
            tripId = resumableTripId,
            isRecovery = true,
            localTripId = pendingSyncState.activeLocalTripId,
        )
    }

    private suspend fun startLocationObservation(
        tripId: Int,
        observationIntervalMs: Long,
    ) {
        stopLocationObservation()
        locationObservationJob = serviceScope.launch {
            locationRepository.observeLocationUpdates(observationIntervalMs).collect { location ->
                if (activeTripId != tripId) {
                    return@collect
                }
                latestObservedLocation = location
                val observationTimestamp = location.timestamp
                updateTrackingStatus {
                    it.copy(
                        serviceRunning = true,
                        trackingActive = true,
                        currentTripId = tripId,
                        lastReadAttemptAt = observationTimestamp,
                        gpsReadStartedAt = observationTimestamp,
                        gpsReadFinishedAt = observationTimestamp,
                        gpsReadDurationMs = null,
                        gpsReadCount = it.gpsReadCount + 1,
                        lastLocationAt = observationTimestamp,
                        lastLatitude = location.latitude,
                        lastLongitude = location.longitude,
                        lastAccuracy = location.accuracy,
                        lastSpeed = location.speed,
                        lastLocationProvider = location.provider ?: "No disponible",
                        lastLocationAgeMs = location.ageMs,
                        lastOperationalMessage = "Muestra GPS observada.",
                    )
                }
            }
        }
    }

    private suspend fun stopLocationObservation() {
        locationObservationJob?.cancelAndJoin()
        locationObservationJob = null
    }

    private suspend fun evaluationLoop(
        tripId: Int,
        evaluationIntervalMs: Long,
        localTripId: String?,
    ) {
        while (currentCoroutineContext().isActive && activeTripId == tripId) {
            val loopStartedAt = Instant.now().toString()
            val loopStartedAtMs = System.currentTimeMillis()
            updateTrackingStatus {
                it.copy(
                    serviceRunning = true,
                    trackingActive = true,
                    currentTripId = tripId,
                    loopStartedAt = loopStartedAt,
                )
            }
            val session = sessionStore.sessionData.first()
            val token = session.token
            val baseUrl = session.baseUrl.ifBlank { RastreoRepository.productionUrl() }
            if (token.isBlank()) {
                val loopFinishedAt = Instant.now().toString()
                val loopDurationMs = System.currentTimeMillis() - loopStartedAtMs
                updateTrackingStatus {
                    it.copy(
                        serviceRunning = true,
                        trackingActive = false,
                        currentTripId = tripId,
                        loopFinishedAt = loopFinishedAt,
                        loopDurationMs = loopDurationMs,
                        lastOperationalMessage = "Tracking detenido por sesion invalida.",
                    )
                }
                markSessionExpired()
                break
            }

            updateTrackingStatus {
                it.copy(
                    serviceRunning = true,
                    trackingActive = true,
                    currentTripId = tripId,
                    lastOperationalMessage = "Evaluando ultima muestra GPS.",
                )
            }
            val location = latestObservedLocation
            if (location == null) {
                val loopFinishedAt = Instant.now().toString()
                val loopDurationMs = System.currentTimeMillis() - loopStartedAtMs
                updateTrackingStatus {
                    it.copy(
                        serviceRunning = true,
                        trackingActive = true,
                        currentTripId = tripId,
                        loopFinishedAt = loopFinishedAt,
                        loopDurationMs = loopDurationMs,
                        gpsNullCount = it.gpsNullCount + 1,
                        lastOperationalMessage = "Sin muestra GPS disponible para evaluar.",
                    )
                }
                delay(evaluationIntervalMs)
                continue
            }

            val currentInstant = runCatching { Instant.parse(location.timestamp) }.getOrElse { Instant.now() }
            val validation = validateLocationPoint(
                tripId = tripId,
                location = location,
                currentInstant = currentInstant,
            )
            updateTrackingStatus {
                it.copy(
                    serviceRunning = true,
                    trackingActive = true,
                    currentTripId = tripId,
                    lastFilterElapsedSeconds = validation.elapsedSeconds,
                    lastFilterRequiredDistanceMeters = validation.requiredDistanceMeters,
                    lastFilterActualDistanceMeters = validation.actualDistanceMeters,
                    secondsSinceLastAcceptedPoint = validation.elapsedSeconds,
                    distanceMinimumDiscardCountSinceLastAccepted =
                        distanceMinimumDiscardCountSinceLastAccepted,
                    lastOperationalMessage = "Ubicacion obtenida.",
                )
            }

            if (validation.discardType == DiscardType.MINIMUM_DISTANCE) {
                distanceMinimumDiscardCountSinceLastAccepted += 1
                updateTrackingStatus {
                    it.copy(
                        distanceMinimumDiscardCountSinceLastAccepted =
                            distanceMinimumDiscardCountSinceLastAccepted,
                    )
                }
            }

            val shouldSendPermanencePoint = shouldSendPermanencePoint(validation)
            if (!validation.isAccepted && !shouldSendPermanencePoint) {
                val discardReason = validation.discardReason ?: "Punto descartado."
                val loopFinishedAt = Instant.now().toString()
                val loopDurationMs = System.currentTimeMillis() - loopStartedAtMs
                updateTrackingStatus {
                    it.copy(
                        serviceRunning = true,
                        trackingActive = true,
                        currentTripId = tripId,
                        loopFinishedAt = loopFinishedAt,
                        loopDurationMs = loopDurationMs,
                        lastGpsDiscardReason = discardReason,
                        rejectedPositionsCount = it.rejectedPositionsCount + 1,
                        lastRejectedAt = location.timestamp,
                        secondsSinceLastAcceptedPoint = validation.elapsedSeconds,
                        distanceMinimumDiscardCountSinceLastAccepted =
                            distanceMinimumDiscardCountSinceLastAccepted,
                        lastOperationalMessage = discardReason,
                    )
                }
                delay(evaluationIntervalMs)
                continue
            }

            val clientPointId = UUID.randomUUID().toString()
            val request = TripPointRequest(
                clientPointId = clientPointId,
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = location.timestamp,
                accuracy = location.accuracy,
                speed = location.speed,
            )
            val pendingPointDraft = PendingTripPointDraft(
                tripId = tripId,
                localTripId = localTripId,
                clientPointId = clientPointId,
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = location.timestamp,
                accuracy = location.accuracy,
                speed = location.speed,
                sendType = if (shouldSendPermanencePoint) "permanencia" else "normal",
            )
            updateTrackingStatus {
                it.copy(
                    serviceRunning = true,
                    trackingActive = true,
                    currentTripId = tripId,
                    lastSendAttemptAt = Instant.now().toString(),
                    sendAttemptsCount = it.sendAttemptsCount + 1,
                    lastSendType = if (shouldSendPermanencePoint) {
                        "Envio por permanencia"
                    } else {
                        "Envio normal"
                    },
                    secondsSinceLastAcceptedPoint = validation.elapsedSeconds,
                    distanceMinimumDiscardCountSinceLastAccepted =
                        distanceMinimumDiscardCountSinceLastAccepted,
                    lastOperationalMessage = if (shouldSendPermanencePoint) {
                        "Enviando punto por permanencia."
                    } else {
                        "Enviando punto."
                    },
                )
            }

            when (syncPendingPointsForCurrentTrip(tripId, localTripId, token, baseUrl)) {
                SyncResult.UNAUTHORIZED -> {
                    enqueuePendingPoint(pendingPointDraft)
                    updateTrackingStatus {
                        it.copy(
                            serviceRunning = true,
                            trackingActive = false,
                            currentTripId = tripId,
                            lastSendFailureAt = Instant.now().toString(),
                            failedSendsCount = it.failedSendsCount + 1,
                            lastOperationalMessage = "Sesion expirada durante tracking.",
                        )
                    }
                    markSessionExpired()
                    break
                }

                SyncResult.RETRY_LATER -> {
                    enqueuePendingPoint(pendingPointDraft)
                    updateTrackingStatus {
                        it.copy(
                            serviceRunning = true,
                            trackingActive = true,
                            currentTripId = tripId,
                            lastSendFailureAt = Instant.now().toString(),
                            failedSendsCount = it.failedSendsCount + 1,
                            lastOperationalMessage = "Punto agregado a cola offline.",
                        )
                    }
                }

                SyncResult.SUCCESS -> {
                    if (hasPendingPointsForCurrentTrip(tripId, localTripId)) {
                        enqueuePendingPoint(pendingPointDraft)
                        updateTrackingStatus {
                            it.copy(
                                serviceRunning = true,
                                trackingActive = true,
                                currentTripId = tripId,
                                lastSendFailureAt = Instant.now().toString(),
                                failedSendsCount = it.failedSendsCount + 1,
                                lastOperationalMessage = "Punto agregado a cola offline.",
                            )
                        }
                    } else {
                        val sendPointResult = runCatching {
                            repository.sendTripPoint(baseUrl, token, tripId, request)
                        }

                        if (sendPointResult.isSuccess) {
                            lastAcceptedPoint = AcceptedPoint(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                acceptedAt = currentInstant,
                            )
                            distanceMinimumDiscardCountSinceLastAccepted = 0
                            updateTrackingStatus {
                                it.copy(
                                    serviceRunning = true,
                                    trackingActive = true,
                                    currentTripId = tripId,
                                    lastSuccessfulSendAt = Instant.now().toString(),
                                    successfulSendsCount = it.successfulSendsCount + 1,
                                    lastAcceptedLatitude = location.latitude,
                                    lastAcceptedLongitude = location.longitude,
                                    lastAcceptedAccuracy = location.accuracy,
                                    lastGpsDiscardReason = "Sin descarte",
                                    lastFilterElapsedSeconds = validation.elapsedSeconds,
                                    lastFilterRequiredDistanceMeters =
                                        validation.requiredDistanceMeters,
                                    lastFilterActualDistanceMeters = validation.actualDistanceMeters,
                                    secondsSinceLastAcceptedPoint = 0,
                                    distanceMinimumDiscardCountSinceLastAccepted = 0,
                                    lastOperationalMessage = if (shouldSendPermanencePoint) {
                                        "Punto de permanencia enviado."
                                    } else {
                                        "Punto enviado."
                                    },
                                )
                            }
                            when (runPendingCloseSyncAttempt(token, baseUrl)) {
                                SyncResult.UNAUTHORIZED -> {
                                    updateTrackingStatus {
                                        it.copy(
                                            serviceRunning = true,
                                            trackingActive = false,
                                            currentTripId = tripId,
                                            lastOperationalMessage =
                                                "Sesion expirada al sincronizar cierre pendiente.",
                                        )
                                    }
                                    markSessionExpired()
                                    break
                                }

                                else -> Unit
                            }
                        } else {
                            val error = sendPointResult.exceptionOrNull()
                            enqueuePendingPoint(pendingPointDraft)
                            if (isUnauthorizedError(error)) {
                                updateTrackingStatus {
                                    it.copy(
                                        serviceRunning = true,
                                        trackingActive = false,
                                        currentTripId = tripId,
                                        lastSendFailureAt = Instant.now().toString(),
                                        failedSendsCount = it.failedSendsCount + 1,
                                        lastOperationalMessage = "Sesion expirada durante tracking.",
                                    )
                                }
                                markSessionExpired()
                                break
                            } else {
                                updateTrackingStatus {
                                    it.copy(
                                        serviceRunning = true,
                                        trackingActive = true,
                                        currentTripId = tripId,
                                        lastSendFailureAt = Instant.now().toString(),
                                        failedSendsCount = it.failedSendsCount + 1,
                                        lastOperationalMessage = "Punto agregado a cola offline.",
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!currentCoroutineContext().isActive || activeTripId != tripId) {
                val loopFinishedAt = Instant.now().toString()
                val loopDurationMs = System.currentTimeMillis() - loopStartedAtMs
                updateTrackingStatus {
                    it.copy(
                        serviceRunning = true,
                        trackingActive = true,
                        currentTripId = tripId,
                        loopFinishedAt = loopFinishedAt,
                        loopDurationMs = loopDurationMs,
                    )
                }
                break
            }

            val loopFinishedAt = Instant.now().toString()
            val loopDurationMs = System.currentTimeMillis() - loopStartedAtMs
            updateTrackingStatus {
                it.copy(
                    serviceRunning = true,
                    trackingActive = true,
                    currentTripId = tripId,
                    loopFinishedAt = loopFinishedAt,
                    loopDurationMs = loopDurationMs,
                    gpsSlowReadCount = it.gpsSlowReadCount + if (loopDurationMs > SLOW_GPS_READ_THRESHOLD_MS) 1 else 0,
                    gpsVerySlowReadCount = it.gpsVerySlowReadCount + if (loopDurationMs > VERY_SLOW_GPS_READ_THRESHOLD_MS) 1 else 0,
                )
            }
            delay(evaluationIntervalMs)
        }
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

    private fun shouldSendPermanencePoint(validation: PointValidation): Boolean {
        return validation.discardType == DiscardType.MINIMUM_DISTANCE &&
            validation.elapsedSeconds != null &&
            validation.elapsedSeconds >= PERMANENCE_POINT_SECONDS &&
            distanceMinimumDiscardCountSinceLastAccepted >= MIN_DISTANCE_DISCARDS_FOR_PERMANENCE
    }

    private suspend fun enqueuePendingPoint(point: PendingTripPointDraft) {
        pendingSyncStore.enqueuePoint(point)
        updateLastAcceptedPoint(
            latitude = point.latitude,
            longitude = point.longitude,
            timestamp = point.timestamp,
        )
        updateTrackingStatus {
            it.copy(
                serviceRunning = true,
                trackingActive = true,
                currentTripId = point.tripId,
                lastOperationalMessage = "Punto agregado a cola offline.",
            )
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

    private suspend fun syncPendingPointsForCurrentTrip(
        tripId: Int,
        localTripId: String?,
        token: String,
        baseUrl: String,
    ): SyncResult {
        val localTrip = localTripId?.let { pendingSyncStore.getLocalTrip(it) }
            ?.takeIf { it.remoteTripId == tripId }
        return if (localTrip?.remoteTripId != null) {
            syncPendingPointsForLocalTrip(
                localTripId = localTrip.localTripId,
                remoteTripId = localTrip.remoteTripId,
                token = token,
                baseUrl = baseUrl,
            )
        } else {
            syncPendingPointsForTrip(tripId, token, baseUrl)
        }
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
            } else {
                val error = syncResult.exceptionOrNull()
                return if (isUnauthorizedError(error)) {
                    SyncResult.UNAUTHORIZED
                } else {
                    SyncResult.RETRY_LATER
                }
            }
        }

        return SyncResult.SUCCESS
    }

    private suspend fun hasPendingPointsForCurrentTrip(
        tripId: Int,
        localTripId: String?,
    ): Boolean {
        val localTrip = localTripId?.let { pendingSyncStore.getLocalTrip(it) }
            ?.takeIf { it.remoteTripId == tripId }
        return if (localTrip?.remoteTripId != null) {
            hasPendingPointsForLocalTripOrLegacy(localTrip.localTripId, localTrip.remoteTripId)
        } else {
            pendingSyncStore.hasPendingPointsForTrip(tripId)
        }
    }

    private suspend fun runPendingCloseSyncAttempt(
        token: String,
        baseUrl: String,
    ): SyncResult {
        return pendingCloseSyncMutex.withLock {
            trySyncPendingClose(token, baseUrl)
        }
    }

    private suspend fun trySyncPendingClose(
        token: String,
        baseUrl: String,
    ): SyncResult {
        val pendingSyncState = pendingSyncStore.getState()
        val pendingClose = pendingSyncState.pendingClose ?: return SyncResult.SUCCESS
        val localTrip = preferredCloseLocalTrip(pendingSyncState, pendingClose.tripId)
        val tripId = localTrip?.remoteTripId ?: pendingClose.tripId

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
            return SyncResult.SUCCESS
        }

        val stopError = stopTripResult.exceptionOrNull()
        return if (isUnauthorizedError(stopError)) {
            SyncResult.UNAUTHORIZED
        } else {
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

    private suspend fun markSessionExpired() {
        sessionStore.clearSession()
        lastAcceptedPoint = null
        distanceMinimumDiscardCountSinceLastAccepted = 0
        updateTrackingStatus {
            it.copy(
                serviceRunning = false,
                trackingActive = false,
                lastOperationalMessage = "Tracking detenido por sesion expirada.",
            )
        }
        stopTrackingLoop()
    }

    private suspend fun updateTrackingStatus(
        transform: (TrackingStatus) -> TrackingStatus,
    ) {
        trackingStatusStore.updateStatus(transform)
    }

    private fun updateTrackingStatusAsync(
        transform: (TrackingStatus) -> TrackingStatus,
    ) {
        serviceScope.launch {
            trackingStatusStore.updateStatus(transform)
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

    private fun isUnauthorizedError(error: Throwable?): Boolean {
        return error is HttpException && error.code() == 401
    }

    private fun isNetworkError(error: Throwable?): Boolean {
        return error is IOException || error is RefreshTransientException
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OrdenaTrack")
            .setContentText("Tracking activo")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OrdenaTrack Tracking",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notificaciones del tracking activo"
        }
        manager.createNotificationChannel(channel)
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

    companion object {
        private const val TAG = "TrackingForegroundSvc"
        private const val CHANNEL_ID = "tracking_foreground_service"
        private const val NOTIFICATION_ID = 1001
        private const val INVALID_TRIP_ID = -1
        private const val MAX_ACCEPTED_ACCURACY_METERS = 100f
        private const val MIN_SECONDS_BETWEEN_ACCEPTED_POINTS = 5L
        private const val PERMANENCE_POINT_SECONDS = 120L
        private const val MIN_DISTANCE_DISCARDS_FOR_PERMANENCE = 2
        private const val SLOW_GPS_READ_THRESHOLD_MS = 3_000L
        private const val VERY_SLOW_GPS_READ_THRESHOLD_MS = 8_000L

        const val ACTION_START_TRACKING = "com.rastreo.vehicular.action.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.rastreo.vehicular.action.STOP_TRACKING"
        const val EXTRA_TRIP_ID = "extra_trip_id"
        const val EXTRA_VEHICLE_ID = "extra_vehicle_id"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_LOCAL_TRIP_ID = "extra_local_trip_id"
        const val EXTRA_IS_RECOVERY = "extra_is_recovery"

        fun createStartIntent(
            context: Context,
            tripId: Int,
            vehicleId: Int? = null,
            category: String? = null,
            localTripId: String? = null,
            isRecovery: Boolean = false,
        ): Intent {
            return Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_TRIP_ID, tripId)
                putExtra(EXTRA_IS_RECOVERY, isRecovery)
                if (vehicleId != null) {
                    putExtra(EXTRA_VEHICLE_ID, vehicleId)
                }
                if (category != null) {
                    putExtra(EXTRA_CATEGORY, category)
                }
                if (localTripId != null) {
                    putExtra(EXTRA_LOCAL_TRIP_ID, localTripId)
                }
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, TrackingForegroundService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
        }
    }
}
