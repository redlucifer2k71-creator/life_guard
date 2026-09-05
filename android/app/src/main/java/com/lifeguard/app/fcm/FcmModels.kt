package com.lifeguard.app.fcm

import com.google.gson.annotations.SerializedName

/** Sent to backend when FCM generates a new token for this device. */
data class FcmTokenRequest(
    @SerializedName("user_id") val userId: Long,
    @SerializedName("fcm_token") val fcmToken: String
)

data class FcmTokenResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String
)
