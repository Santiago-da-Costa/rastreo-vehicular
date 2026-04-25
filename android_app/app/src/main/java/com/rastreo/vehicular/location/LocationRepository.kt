package com.rastreo.vehicular.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val speed: Float?,
    val provider: String?,
    val ageMs: Long?,
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
                    continuation.resume(
                        location?.let {
                            LocationSample(
                                latitude = it.latitude,
                                longitude = it.longitude,
                                accuracy = it.accuracy,
                                speed = it.speed,
                                provider = it.provider,
                                ageMs = runCatching {
                                    val ageNanos = SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos
                                    (ageNanos.coerceAtLeast(0L)) / 1_000_000L
                                }.getOrNull(),
                            )
                        }
                    )
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }
    }
}
