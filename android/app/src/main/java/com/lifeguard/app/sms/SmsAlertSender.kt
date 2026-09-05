package com.lifeguard.app.sms

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lifeguard.app.data.UserSession

object SmsAlertSender {
    private const val TAG = "SmsAlertSender"

    /**
     * Sends an emergency SMS with GPS coordinates and live Google Maps link
     * to the user's registered emergency contact.
     */
    fun sendEmergencySms(
        context: Context,
        alertType: String,
        latitude: Double,
        longitude: Double,
        nearbyHelpersCount: Int = 0
    ) {
        val emergencyContact = UserSession.getEmergencyContact(context)
        if (emergencyContact.isBlank()) {
            Log.w(TAG, "No emergency contact phone set — skipping SMS alert")
            return
        }

        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "SEND_SMS permission not granted — cannot send SMS")
            return
        }

        val userName = UserSession.getFullName(context).ifBlank { "User" }
        val readableAlert = when (alertType) {
            "ROUTE_DEVIATION" -> "Off-Route Alert"
            "GUARD_MODE_DOUBLE_PRESS" -> "Panic SOS (Double Tap)"
            "GUARD_MODE_RELEASE" -> "Dead-Man Switch Alert"
            "TIMER_CHECKIN_EXPIRED" -> "Missed Check-In Alert"
            else -> "Emergency SOS"
        }

        val mapsLink = if (latitude != 0.0 && longitude != 0.0) {
            "https://maps.google.com/?q=$latitude,$longitude"
        } else {
            "Coordinates unavailable"
        }

        val message = buildString {
            append("EMERGENCY ALERT from $userName!\n")
            append("Type: $readableAlert\n")
            if (nearbyHelpersCount > 0) {
                append("Nearby Helpers Alerted: $nearbyHelpersCount\n")
            }
            append("Live Location:\n$mapsLink")
        }

        try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(emergencyContact, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(emergencyContact, null, message, null, null)
            }
            Log.i(TAG, "Emergency SMS dispatched to $emergencyContact")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send emergency SMS: ${e.message}", e)
        }
    }
}
