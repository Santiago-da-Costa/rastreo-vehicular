package com.rastreo.vehicular.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import kotlin.coroutines.resume

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val speed: Float?,
    val provider: String?,
    val ageMs: Long?,
    val timestamp: String,
)

class LocationRepository(private val context: Context) {
    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): LocationSample? {
        if (!hasLocationPermission()) return null

        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(10_000L)
            .build()

        return suspendCancellableCoroutine { continuation ->
            client.getCurrentLocation(request, null)
                .addOnSuccessListener { location ->
                    continuation.resume(location?.toLocationSample())
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }
    }

    @SuppressLint("MissingPermission")
    fun observeLocationUpdates(intervalMs: Long): Flow<LocationSample> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        val normalizedIntervalMs = intervalMs.coerceAtLeast(1_000L)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, normalizedIntervalMs)
            .setMinUpdateIntervalMillis(normalizedIntervalMs)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySend(location.toLocationSample())
                }
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { error ->
                close(error)
            }

        awaitClose {
            client.removeLocationUpdates(callback)
        }
    }

    private fun Location.toLocationSample(): LocationSample {
        return LocationSample(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            speed = speed,
            provider = provider,
            ageMs = runCatching {
                val ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
                (ageNanos.coerceAtLeast(0L)) / 1_000_000L
            }.getOrNull(),
            timestamp = runCatching { Instant.ofEpochMilli(time).toString() }
                .getOrElse { Instant.now().toString() },
        )
    }
}
