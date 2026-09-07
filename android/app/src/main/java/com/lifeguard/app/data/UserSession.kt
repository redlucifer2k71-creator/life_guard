package com.lifeguard.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.lifeguard.app.fcm.FcmTokenRequest
import com.lifeguard.app.network.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object UserSession {
    private const val PREFS_NAME = "lifeguard_session"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_FULL_NAME = "full_name"
    private const val KEY_PHONE = "phone_number"
    private const val KEY_LOGGED_IN = "is_logged_in"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_LAST_LAT = "last_latitude"
    private const val KEY_LAST_LNG = "last_longitude"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_EMERGENCY_CONTACT = "emergency_contact"
    private const val KEY_LAST_KNOWN_PHONE = "last_known_phone"
    private const val KEY_LAST_KNOWN_PIN_HASH = "last_known_pin_hash"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Called after successful login/register — saves all user fields + hashed PIN. */
    fun saveUser(context: Context, user: UserData, pinHash: String = "") {
        prefs(context).edit()
            .putLong(KEY_USER_ID, user.id)
            .putString(KEY_FULL_NAME, user.fullName)
            .putString(KEY_PHONE, user.phoneNumber)
            .putString(KEY_LAST_KNOWN_PHONE, user.phoneNumber)
            .putString(KEY_EMERGENCY_CONTACT, user.emergencyContactPhone ?: "")
            .putBoolean(KEY_LOGGED_IN, true)
            .apply {
                if (pinHash.isNotBlank()) {
                    putString(KEY_PIN_HASH, pinHash)
                    putString(KEY_LAST_KNOWN_PIN_HASH, pinHash)
                }
            }
            .apply()
    }

    fun getLastKnownPhone(context: Context): String =
        prefs(context).getString(KEY_LAST_KNOWN_PHONE, prefs(context).getString(KEY_PHONE, "") ?: "") ?: ""

    fun getLastKnownPinHash(context: Context): String =
        prefs(context).getString(KEY_LAST_KNOWN_PIN_HASH, prefs(context).getString(KEY_PIN_HASH, "") ?: "") ?: ""

    fun setLoggedIn(context: Context, loggedIn: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOGGED_IN, loggedIn).apply()
    }

    fun getUserId(context: Context): Long = prefs(context).getLong(KEY_USER_ID, -1L)

    fun getFullName(context: Context): String =
        prefs(context).getString(KEY_FULL_NAME, "User") ?: "User"

    fun getPhone(context: Context): String =
        prefs(context).getString(KEY_PHONE, "") ?: ""

    fun getEmergencyContact(context: Context): String =
        prefs(context).getString(KEY_EMERGENCY_CONTACT, "") ?: ""

    fun setEmergencyContact(context: Context, phone: String) {
        prefs(context).edit().putString(KEY_EMERGENCY_CONTACT, phone).apply()
    }

    fun isLoggedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOGGED_IN, false)

    /** Retrieve stored PIN hash for local offline PIN verification. */
    fun getStoredPinHash(context: Context): String =
        prefs(context).getString(KEY_PIN_HASH, "") ?: ""

    /** Updated by LocationTrackingService on every GPS fix. */
    fun saveLocation(context: Context, latitude: Double, longitude: Double) {
        prefs(context).edit()
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(latitude))
            .putLong(KEY_LAST_LNG, java.lang.Double.doubleToRawLongBits(longitude))
            .apply()
    }

    fun getLastLatitude(context: Context): Double {
        val bits = prefs(context).getLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(0.0))
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun getLastLongitude(context: Context): Double {
        val bits = prefs(context).getLong(KEY_LAST_LNG, java.lang.Double.doubleToRawLongBits(0.0))
        return java.lang.Double.longBitsToDouble(bits)
    }

    /** Save FCM token for push notifications. */
    fun saveFcmToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    fun getFcmToken(context: Context): String? =
        prefs(context).getString(KEY_FCM_TOKEN, null)

    /** Sync device FCM token to backend for communal emergency SOS delivery. */
    fun syncFcmTokenWithBackend(context: Context) {
        val userId = getUserId(context)
        if (userId == -1L) return

        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    saveFcmToken(context, token)
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val response = NetworkClient.apiService.registerFcmToken(
                                FcmTokenRequest(userId = userId, fcmToken = token)
                            )
                            if (response.isSuccessful) {
                                Log.i("UserSession", "FCM token successfully registered with backend for user $userId")
                            } else {
                                Log.w("UserSession", "FCM token registration returned HTTP ${response.code()}")
                            }
                        } catch (e: Exception) {
                            Log.w("UserSession", "FCM token upload failed: ${e.message}")
                        }
                    }
                }
            }.addOnFailureListener { e ->
                Log.w("UserSession", "Failed to retrieve FCM token: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("UserSession", "FirebaseMessaging error: ${e.message}")
        }
    }

    /** Onboarding — shown only on first launch. */
    fun hasSeenOnboarding(context: Context): Boolean =
        prefs(context).getBoolean("onboarding_seen", false)

    fun markOnboardingSeen(context: Context) {
        prefs(context).edit().putBoolean("onboarding_seen", true).apply()
    }

    fun getServerUrl(context: Context): String =
        prefs(context).getString("server_url", com.lifeguard.app.BuildConfig.BASE_URL)
            ?: com.lifeguard.app.BuildConfig.BASE_URL

    fun setServerUrl(context: Context, url: String) {
        val trimmed = url.trim().trimEnd('/')
        prefs(context).edit().putString("server_url", trimmed).apply()
        com.lifeguard.app.network.NetworkClient.setBaseUrl(trimmed)
    }

    fun getCallMeBotApiKey(context: Context): String =
        prefs(context).getString("callmebot_api_key", "") ?: ""

    fun setCallMeBotApiKey(context: Context, key: String) {
        prefs(context).edit().putString("callmebot_api_key", key.trim()).apply()
    }

    fun logout(context: Context) {
        // Preserve onboarding seen flag, server_url, and last known credentials across logouts
        val seenOnboarding = hasSeenOnboarding(context)
        val serverUrl = getServerUrl(context)
        val callmebot = getCallMeBotApiKey(context)
        val lastPhone = getLastKnownPhone(context)
        val lastPinHash = getLastKnownPinHash(context)
        prefs(context).edit().clear().apply()
        if (seenOnboarding) markOnboardingSeen(context)
        setServerUrl(context, serverUrl)
        setCallMeBotApiKey(context, callmebot)
        if (lastPhone.isNotBlank()) {
            prefs(context).edit()
                .putString(KEY_LAST_KNOWN_PHONE, lastPhone)
                .putString(KEY_LAST_KNOWN_PIN_HASH, lastPinHash)
                .apply()
        }
    }
}
