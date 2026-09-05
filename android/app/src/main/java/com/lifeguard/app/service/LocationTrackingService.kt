package com.lifeguard.app.service

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
import com.lifeguard.app.data.LocationUpdateRequest
import com.lifeguard.app.network.NetworkClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val CHANNEL_ID = "lifeguard_location_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START_LOCATION_SERVICE"
        const val ACTION_STOP = "ACTION_STOP_LOCATION_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    Log.d(TAG, "Location retrieved: Lat=${location.latitude}, Lng=${location.longitude}")
                    sendLocationToBackend(location.latitude, location.longitude, location.speed, location.bearing)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundLocationService()
            ACTION_STOP -> stopForegroundLocationService()
        }
        return START_STICKY
    }

    private fun startForegroundLocationService() {
        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        requestLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 
            10000L // 10 seconds interval
        ).setMinUpdateIntervalMillis(5000L)
         .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest, 
                locationCallback, 
                Looper.getMainLooper()
            )
            Log.i(TAG, "Location updates requested successfully (10s interval)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing: ${e.message}")
        }
    }

    private fun sendLocationToBackend(lat: Double, lng: Double, speed: Float, heading: Float) {
        serviceScope.launch {
            try {
                // Save last known GPS to SharedPreferences for SOS trigger
                com.lifeguard.app.data.UserSession.saveLocation(this@LocationTrackingService, lat, lng)

                val userId = com.lifeguard.app.data.UserSession.getUserId(this@LocationTrackingService)
                if (userId == -1L) {
                    Log.w(TAG, "User not logged in — skipping backend location sync")
                    return@launch
                }

                val request = LocationUpdateRequest(
                    userId = userId,
                    latitude = lat,
                    longitude = lng,
                    speed = speed,
                    heading = heading
                )
                val response = NetworkClient.apiService.updateLocation(request)
                if (response.isSuccessful) {
                    Log.i(TAG, "Backend location sync success: ${response.body()}")
                } else {
                    Log.w(TAG, "Backend location sync failed: ${response.code()} ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing location to backend", e)
            }
        }
    }

    private fun stopForegroundLocationService() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Life Guard Location Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors live GPS location for emergency SOS tracking"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Life Guard Active Protection")
            .setContentText("Monitoring route & location for emergency detection...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
