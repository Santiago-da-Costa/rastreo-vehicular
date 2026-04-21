package com.rastreo.vehicular.data

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val username: String,
    val password: String,
)

data class TokenResponse(
    @SerializedName("access_token")
    val accessToken: String,
    @SerializedName("token_type")
    val tokenType: String,
)

data class UserMeResponse(
    val id: Int,
    val username: String,
    @SerializedName("full_name")
    val fullName: String,
    val email: String?,
    val role: String,
    @SerializedName("is_active")
    val isActive: Boolean,
    val permissions: Map<String, Boolean>,
    @SerializedName("vehicle_ids")
    val vehicleIds: List<Int>,
)

data class Vehicle(
    val id: Int,
    val nombre: String,
    val matricula: String,
    val descripcion: String?,
)

data class TripStartRequest(
    @SerializedName("vehicle_id")
    val vehicleId: Int,
    val categoria: String,
)

data class TripResponse(
    val id: Int,
    @SerializedName("vehicle_id")
    val vehicleId: Int,
    val categoria: String,
    @SerializedName("start_time")
    val startTime: String,
    @SerializedName("end_time")
    val endTime: String?,
    val status: String,
    @SerializedName("is_manual")
    val isManual: Boolean,
)

data class TripPointRequest(
    val latitude: Double,
    val longitude: Double,
    val timestamp: String,
    val accuracy: Float?,
    val speed: Float?,
)

data class TripPointResponse(
    val id: Int,
    @SerializedName("trip_id")
    val tripId: Int,
    val latitude: Double,
    val longitude: Double,
    val timestamp: String,
    val accuracy: Float?,
    val speed: Float?,
)
