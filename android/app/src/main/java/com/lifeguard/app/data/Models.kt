package com.lifeguard.app.data

import com.google.gson.annotations.SerializedName

// --- Location ---
data class LocationUpdateRequest(
    @SerializedName("user_id") val userId: Long,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("speed") val speed: Float? = null,
    @SerializedName("heading") val heading: Float? = null
)

data class LocationUpdateResponse(
    @SerializedName("status") val status: String,
    @SerializedName("user_id") val userId: Long,
    @SerializedName("updated_at") val updatedAt: String
)

// --- Alerts ---
data class AlertTriggerRequest(
    @SerializedName("user_id") val userId: Long,
    @SerializedName("alert_type") val alertType: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("radius_meters") val radiusMeters: Double = 500.0
)

data class AlertTriggerResponse(
    @SerializedName("status") val status: String,
    @SerializedName("alert_id") val alertId: Long,
    @SerializedName("alert_type") val alertType: String,
    @SerializedName("alert_status") val alertStatus: String,
    @SerializedName("triggered_at") val triggeredAt: String,
    @SerializedName("total_recipients_notified") val totalRecipientsNotified: Int
)

// --- Auth ---
data class RegisterRequest(
    @SerializedName("full_name") val fullName: String,
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("pin") val pin: String,
    @SerializedName("emergency_contact_phone") val emergencyContactPhone: String? = null
)

data class LoginRequest(
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("pin") val pin: String
)

data class UserData(
    @SerializedName("id") val id: Long,
    @SerializedName("full_name") val fullName: String,
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("emergency_contact_phone") val emergencyContactPhone: String? = null,
    @SerializedName("is_active") val isActive: Boolean
)

data class AuthResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String,
    @SerializedName("user") val user: UserData
)
