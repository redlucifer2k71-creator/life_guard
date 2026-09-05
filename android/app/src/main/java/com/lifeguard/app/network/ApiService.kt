package com.lifeguard.app.network

import com.lifeguard.app.data.*
import com.lifeguard.app.fcm.FcmTokenRequest
import com.lifeguard.app.fcm.FcmTokenResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("/api/v1/users/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("/api/v1/users/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("/api/v1/location/update")
    suspend fun updateLocation(@Body request: LocationUpdateRequest): Response<LocationUpdateResponse>

    @POST("/api/v1/alerts/trigger")
    suspend fun triggerAlert(@Body request: AlertTriggerRequest): Response<AlertTriggerResponse>

    @POST("/api/v1/users/fcm-token")
    suspend fun registerFcmToken(@Body request: FcmTokenRequest): Response<FcmTokenResponse>
}
