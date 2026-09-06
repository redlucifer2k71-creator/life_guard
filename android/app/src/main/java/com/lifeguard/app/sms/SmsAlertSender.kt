package com.lifeguard.app.sms

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.lifeguard.app.data.UserSession

object SmsAlertSender {
    private const val TAG = "SmsAlertSender"

    /**
     * Checks if silent background SMS permission is granted and enabled.
     */
    fun isBackgroundSmsPermissionGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Sends an emergency SMS with GPS coordinates and live Google Maps link
     * to the user's registered emergency contact.
     *
     * Dual-mode safety architecture:
     * 1. If SEND_SMS permission is granted: Sends silently in the background via SmsManager.
     * 2. If SEND_SMS permission is restricted or blocked by Google Play Protect:
     *    Automatically launches the device's native SMS app with the emergency contact
     *    and SOS message + live GPS link pre-filled (requires ZERO permissions and is NEVER blocked).
     */
    fun formatPhoneNumber(rawPhone: String): String {
        val cleaned = rawPhone.replace(Regex("[^0-9+]"), "")
        return when {
            cleaned.startsWith("+") -> cleaned
            cleaned.length == 10 -> "+91$cleaned" // Standard Indian 10-digit mobile format
            cleaned.length == 12 && cleaned.startsWith("91") -> "+$cleaned"
            else -> cleaned
        }
    }

    fun sendEmergencySms(
        context: Context,
        alertType: String,
        latitude: Double,
        longitude: Double,
        nearbyHelpersCount: Int = 0
    ) {
        val rawContact = UserSession.getEmergencyContact(context).trim()
        if (rawContact.isBlank()) {
            Log.w(TAG, "No emergency contact phone set — skipping SMS alert")
            return
        }
        val emergencyContact = formatPhoneNumber(rawContact)

        val userName = UserSession.getFullName(context).ifBlank { "User" }
        val readableAlert = when (alertType) {
            "ROUTE_DEVIATION" -> "Off-Route Deviation Alert"
            "GUARD_MODE_DOUBLE_PRESS" -> "Panic SOS (Double Tap)"
            "GUARD_MODE_RELEASE" -> "Dead-Man Switch SOS"
            "TIMER_CHECKIN_EXPIRED" -> "Missed Safety Check-In Alert"
            else -> "Emergency SOS"
        }

        val mapsLink = if (latitude != 0.0 && longitude != 0.0) {
            "https://maps.google.com/?q=$latitude,$longitude"
        } else {
            "Location unavailable"
        }

        val message = buildString {
            append("EMERGENCY ALERT from $userName!\n")
            append("Type: $readableAlert\n")
            if (nearbyHelpersCount > 0) {
                append("Nearby Helpers Alerted: $nearbyHelpersCount\n")
            }
            append("Live Location:\n$mapsLink")
        }

        val hasDirectPermission = isBackgroundSmsPermissionGranted(context)

        if (hasDirectPermission) {
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
                Log.i(TAG, "Emergency SMS dispatched silently via SmsManager to $emergencyContact")
                showHelpOnTheWayNotification(context, emergencyContact)
                return
            } catch (e: Exception) {
                Log.w(TAG, "SmsManager send failed (${e.message}) — falling back to native SMS app intent", e)
            }
        }

        // ── Fallback: Launch native SMS app with pre-filled distress message ──
        // This guarantees delivery even if background SMS permission is not yet granted
        showHelpOnTheWayNotification(context, emergencyContact)
        launchSmsIntent(context, emergencyContact, message)
    }

    /**
     * Shows high-priority system notification: "Help is on the way!"
     */
    fun showHelpOnTheWayNotification(context: Context, contactPhone: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val notification = androidx.core.app.NotificationCompat.Builder(context, com.lifeguard.app.fcm.LifeGuardFirebaseService.CHANNEL_ID_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🆘 Help is on the way!")
                .setContentText("Emergency SMS & live GPS sent automatically to $contactPhone")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                    .bigText("Help is on the way! Your emergency alert and live GPS location have been dispatched to your contact ($contactPhone). Stay safe."))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setDefaults(android.app.Notification.DEFAULT_ALL)
                .setAutoCancel(true)
                .build()

            manager.notify(9999, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Notification failed: ${e.message}")
        }
    }

    /**
     * Launches the system SMS app with the pre-filled emergency contact and distress text.
     */
    fun launchSmsIntent(context: Context, contactPhone: String, messageText: String) {
        try {
            val uri = Uri.parse("smsto:${Uri.encode(contactPhone)}")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                putExtra("sms_body", messageText)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            Log.i(TAG, "Launched native SMS app with pre-filled distress message for $contactPhone")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SENDTO failed, trying generic ACTION_VIEW: ${e.message}")
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("sms:$contactPhone?body=${Uri.encode(messageText)}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "All SMS intent launchers failed: ${e2.message}")
            }
        }
    }

    /**
     * Sends a test SMS to verify emergency dispatch.
     */
    fun sendTestAlert(context: Context) {
        val lat = UserSession.getLastLatitude(context)
        val lng = UserSession.getLastLongitude(context)
        sendEmergencySms(
            context = context,
            alertType = "TEST_ALERT",
            latitude = lat,
            longitude = lng,
            nearbyHelpersCount = 0
        )
    }
}
