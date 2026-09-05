package com.lifeguard.app.data

import android.content.Context
import android.content.SharedPreferences

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

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Called after successful login/register — saves all user fields + hashed PIN. */
    fun saveUser(context: Context, user: UserData, pinHash: String = "") {
        prefs(context).edit()
            .putLong(KEY_USER_ID, user.id)
            .putString(KEY_FULL_NAME, user.fullName)
            .putString(KEY_PHONE, user.phoneNumber)
            .putString(KEY_EMERGENCY_CONTACT, user.emergencyContactPhone ?: "")
            .putBoolean(KEY_LOGGED_IN, true)
            .apply { if (pinHash.isNotBlank()) putString(KEY_PIN_HASH, pinHash) }
            .apply()
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

    fun logout(context: Context) {
        // Preserve onboarding seen flag and server_url across logouts
        val seenOnboarding = hasSeenOnboarding(context)
        val serverUrl = getServerUrl(context)
        prefs(context).edit().clear().apply()
        if (seenOnboarding) markOnboardingSeen(context)
        setServerUrl(context, serverUrl)
    }
}
