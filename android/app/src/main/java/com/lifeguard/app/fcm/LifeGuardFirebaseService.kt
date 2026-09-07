package com.lifeguard.app.fcm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lifeguard.app.MainActivity
import com.lifeguard.app.data.UserSession
import com.lifeguard.app.network.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives Firebase Cloud Messaging (FCM) push notifications.
 *
 * Two key flows:
 * 1. onNewToken() — Called when FCM gives us a fresh token. We register it
 *    with the backend so the server knows where to push alerts to this device.
 *
 * 2. onMessageReceived() — Called when an SOS alert push arrives. We show
 *    a high-priority "heads-up" notification with victim name, distance,
 *    alert type, and a "Navigate" action button.
 */
class LifeGuardFirebaseService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "LifeGuardFCM"
        const val CHANNEL_ID_ALERTS = "lifeguard_sos_alerts"
        const val CHANNEL_ID_SYSTEM = "lifeguard_system"

        // Notification data keys (must match backend FCM payload)
        const val KEY_ALERT_TYPE = "alert_type"
        const val KEY_VICTIM_NAME = "victim_name"
        const val KEY_DISTANCE_M = "distance_m"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ALERT_ID = "alert_id"

        // Intent extras for the Navigate action
        const val EXTRA_LATITUDE = "victim_lat"
        const val EXTRA_LONGITUDE = "victim_lng"
        const val EXTRA_VICTIM_NAME = "victim_name"
        const val EXTRA_ALERT_ID = "alert_id"

        // Notification IDs
        private var notifIdCounter = 3000

        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()

                // High importance channel for incoming SOS alerts with siren sound and vibration
                val alertChannel = NotificationChannel(
                    CHANNEL_ID_ALERTS,
                    "🆘 SOS Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Incoming emergency SOS alerts from nearby community members"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
                    enableLights(true)
                    setSound(soundUri, audioAttributes)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }

                // Low importance channel for system messages (token registered, etc.)
                val systemChannel = NotificationChannel(
                    CHANNEL_ID_SYSTEM,
                    "Life Guard System",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "System messages and status updates"
                }

                manager.createNotificationChannel(alertChannel)
                manager.createNotificationChannel(systemChannel)
            }
        }
    }

    // ── Token Management ──

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM token received: ${token.take(20)}...")

        // Store token locally
        UserSession.saveFcmToken(applicationContext, token)

        // Register with backend if user is logged in
        UserSession.syncFcmTokenWithBackend(applicationContext)
    }

    // ── Message Handling ──

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i(TAG, "FCM message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        if (data.isEmpty()) {
            // Plain notification payload (no data) — handled automatically by system
            return
        }

        val alertType = data[KEY_ALERT_TYPE] ?: "SOS_ALERT"
        val victimName = data[KEY_VICTIM_NAME] ?: "Nearby user"
        val distanceM = data[KEY_DISTANCE_M]?.toDoubleOrNull() ?: 0.0
        val latitude = data[KEY_LATITUDE]?.toDoubleOrNull() ?: 0.0
        val longitude = data[KEY_LONGITUDE]?.toDoubleOrNull() ?: 0.0
        val alertId = data[KEY_ALERT_ID]?.toLongOrNull() ?: -1L

        Log.i(TAG, "SOS alert: type=$alertType victim=$victimName dist=${distanceM}m")

        showSosNotification(
            alertType = alertType,
            victimName = victimName,
            distanceM = distanceM,
            latitude = latitude,
            longitude = longitude,
            alertId = alertId
        )
    }

    private fun showSosNotification(
        alertType: String,
        victimName: String,
        distanceM: Double,
        latitude: Double,
        longitude: Double,
        alertId: Long
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels(this)

        // Wake screen for 5 seconds on emergency SOS
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wl = pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "LifeGuard:SosWakeLock"
            )
            wl?.acquire(5000L)
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock error: ${e.message}")
        }

        val notifId = notifIdCounter++

        // Format distance
        val distanceStr = when {
            distanceM <= 1.0 -> "in immediate area"
            distanceM < 1000 -> "${distanceM.toInt()}m away"
            else -> "${"%.1f".format(distanceM / 1000)}km away"
        }

        // Map alert type to readable title
        val alertTitle = when (alertType) {
            "GUARD_MODE_DOUBLE_PRESS" -> "🆘 EMERGENCY SOS"
            "GUARD_MODE_RELEASE" -> "🆘 EMERGENCY SOS"
            "TIMER_CHECKIN_EXPIRED" -> "⏱️ Safety Check-In Missed"
            "ROUTE_DEVIATION" -> "🗺️ Route Deviation Alert"
            else -> "🆘 Emergency Alert"
        }

        // Tap notification → open app
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_LATITUDE, latitude)
            putExtra(EXTRA_LONGITUDE, longitude)
            putExtra(EXTRA_VICTIM_NAME, victimName)
            putExtra(EXTRA_ALERT_ID, alertId)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Navigate" action → open Google Maps to victim location
        val navigateIntent = Intent(this, NavigateToVictimActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_LATITUDE, latitude)
            putExtra(EXTRA_LONGITUDE, longitude)
            putExtra(EXTRA_VICTIM_NAME, victimName)
        }
        val navigatePendingIntent = PendingIntent.getActivity(
            this, notifId + 10000, navigateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(alertTitle)
            .setContentText("$victimName needs help · $distanceStr")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$victimName needs help!\n📍 Location: $distanceStr\n🚨 Alert type: $alertType")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(longArrayOf(0, 400, 200, 400, 200, 400))
            .setContentIntent(openPendingIntent)
            .setFullScreenIntent(openPendingIntent, true)
            .addAction(
                android.R.drawable.ic_menu_directions,
                "Navigate to ${victimName.split(" ").first()}",
                navigatePendingIntent
            )
            .build()

        manager.notify(notifId, notification)
    }
}
