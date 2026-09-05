package com.lifeguard.app.route

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.lifeguard.app.data.UserSession

/**
 * Foreground service that continuously compares GPS position against
 * the stored route polyline.
 *
 * When the user deviates beyond the threshold for longer than 5 minutes,
 * it launches RouteDeviationActivity (PIN challenge).
 *
 * Lifecycle:
 *   startService(ACTION_START)  → begin monitoring
 *   startService(ACTION_STOP)   → stop monitoring
 */
class RouteDeviationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Deviation tracking state
    private var deviationStartTime: Long = 0L
    private var isCurrentlyDeviating = false
    private var pinChallengeFired = false

    companion object {
        private const val TAG = "RouteDeviationService"
        private const val CHANNEL_ID = "lifeguard_route_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START = "ACTION_START_ROUTE_MONITOR"
        const val ACTION_STOP = "ACTION_STOP_ROUTE_MONITOR"

        // SharedPreferences key for storing the encoded polyline
        private const val PREFS_NAME = "lifeguard_route"
        private const val KEY_POLYLINE = "encoded_polyline"
        private const val KEY_ACTIVE = "route_active"
        private const val KEY_START_LAT = "start_lat"
        private const val KEY_START_LNG = "start_lng"
        private const val KEY_END_LAT = "end_lat"
        private const val KEY_END_LNG = "end_lng"

        /** Save the route polyline to prefs so the service can read it. */
        fun saveRoute(
            context: Context,
            encodedPolyline: String,
            startLat: Double, startLng: Double,
            endLat: Double, endLng: Double
        ) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_POLYLINE, encodedPolyline)
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_START_LAT, java.lang.Double.doubleToRawLongBits(startLat))
                .putLong(KEY_START_LNG, java.lang.Double.doubleToRawLongBits(startLng))
                .putLong(KEY_END_LAT, java.lang.Double.doubleToRawLongBits(endLat))
                .putLong(KEY_END_LNG, java.lang.Double.doubleToRawLongBits(endLng))
                .apply()
        }

        fun clearRoute(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .clear().apply()
        }

        fun isRouteActive(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACTIVE, false)
        }

        fun getStoredPolyline(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_POLYLINE, null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val currentPos = LatLng(location.latitude, location.longitude)
                    // Save to UserSession for SOS coordinate accuracy
                    UserSession.saveLocation(this@RouteDeviationService, location.latitude, location.longitude)
                    checkDeviation(currentPos)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startMonitoring() {
        createNotificationChannel()
        val notification = buildNotification("Monitoring your route...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Reset deviation state
        deviationStartTime = 0L
        isCurrentlyDeviating = false
        pinChallengeFired = false

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L  // Check every 5 seconds for route deviation
        ).setMinUpdateIntervalMillis(3000L).build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
            Log.i(TAG, "Route deviation monitoring started (5s interval)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
        }
    }

    private fun checkDeviation(currentPos: LatLng) {
        if (pinChallengeFired) return  // Already fired, don't keep checking

        val encodedPolyline = getStoredPolyline(this) ?: return
        val polyline = RouteManager.decodePolyline(encodedPolyline)

        val distance = RouteManager.distanceToPolyline(currentPos, polyline)
        val isOffRoute = distance > RouteManager.DEVIATION_THRESHOLD_METERS

        Log.d(TAG, "Distance to route: ${String.format("%.1f", distance)}m | Off-route: $isOffRoute")

        if (isOffRoute) {
            if (!isCurrentlyDeviating) {
                // Just started deviating
                isCurrentlyDeviating = true
                deviationStartTime = System.currentTimeMillis()
                updateNotification("⚠️ Off-route detected (${String.format("%.0f", distance)}m away)")
                Log.w(TAG, "Deviation started — timer begins")
            } else {
                // Still deviating — check duration
                val elapsed = System.currentTimeMillis() - deviationStartTime
                val remainingSec = ((RouteManager.DEVIATION_TIME_LIMIT_MS - elapsed) / 1000).coerceAtLeast(0)

                updateNotification("⚠️ Off-route for ${elapsed / 1000}s — SOS in ${remainingSec}s")

                if (elapsed >= RouteManager.DEVIATION_TIME_LIMIT_MS) {
                    // 5 minutes off-route → fire PIN challenge
                    Log.w(TAG, "5-minute deviation limit reached — launching PIN challenge!")
                    pinChallengeFired = true
                    launchPinChallenge()
                }
            }
        } else {
            // Back on route — reset deviation timer
            if (isCurrentlyDeviating) {
                Log.i(TAG, "Back on route — deviation timer reset")
                isCurrentlyDeviating = false
                deviationStartTime = 0L
                updateNotification("✅ On-route — monitoring active")
            }
        }
    }

    private fun launchPinChallenge() {
        val intent = Intent(this, RouteDeviationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            2002,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // Show high-priority alert notification with full-screen intent
        val urgentNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚠️ Route Deviation Alert!")
            .setContentText("Tap to enter PIN and cancel emergency SOS")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, urgentNotification)

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity deferred by OS: ${e.message}")
        }
    }

    private fun stopMonitoring() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        clearRoute(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Route deviation monitoring stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Life Guard Route Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors route deviation for safety alerts"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🗺️ Route Guard Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
