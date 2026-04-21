package com.rastreo.vehicular.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface RastreoApi {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): TokenResponse

    @GET("auth/me")
    suspend fun me(): UserMeResponse

    @GET("vehicles")
    suspend fun listVehicles(): List<Vehicle>

    @POST("trips/start")
    suspend fun startTrip(@Body request: TripStartRequest): TripResponse

    @POST("trips/{tripId}/points")
    suspend fun sendTripPoint(
        @Path("tripId") tripId: Int,
        @Body request: TripPointRequest,
    ): TripPointResponse

    @POST("trips/{tripId}/stop")
    suspend fun stopTrip(
        @Path("tripId") tripId: Int,
        @Body body: Map<String, String> = emptyMap(),
    ): TripResponse
}
