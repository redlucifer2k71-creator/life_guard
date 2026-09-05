package com.lifeguard.app.route

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import com.lifeguard.app.data.AlertTriggerRequest
import com.lifeguard.app.data.PinHasher
import com.lifeguard.app.data.UserSession
import com.lifeguard.app.network.NetworkClient
import com.lifeguard.app.ui.PinChallengeDialog
import com.lifeguard.app.ui.theme.LifeGuardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen PIN challenge launched when the user is off-route for 5+ minutes.
 *
 * SUCCESS → Back on route, resume monitoring.
 * FAILURE → Trigger ROUTE_DEVIATION SOS alert and stop monitoring.
 */
class RouteDeviationActivity : ComponentActivity() {

    companion object {
        private const val TAG = "RouteDeviationActivity"
        private const val PIN_TIMEOUT_SECONDS = 30
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow activity to show over lock screen (modern API for Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val win = window
            win.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        } else {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val context = this
        val userId = UserSession.getUserId(context)

        setContent {
            LifeGuardTheme {
                Surface(color = Color(0xEE0A0A0A)) {
                    PinChallengeDialog(
                        title = "🗺️ Route Deviation Detected",
                        subtitle = "You've been off your planned route for 5 minutes.\nEnter PIN to confirm you're safe.",
                        countdownSeconds = PIN_TIMEOUT_SECONDS,
                        onSuccess = { enteredPin ->
                            val storedHash = UserSession.getStoredPinHash(context)

                            if (PinHasher.verify(enteredPin, storedHash)) {
                                Toast.makeText(context, "✅ Confirmed safe — still monitoring route", Toast.LENGTH_SHORT).show()
                                // Stop the current deviation service and restart it
                                // so the timer resets (user gets another 5 min grace)
                                restartRouteMonitor()
                            } else {
                                Toast.makeText(context, "❌ Wrong PIN — Sending SOS!", Toast.LENGTH_LONG).show()
                                triggerSosAndStop(userId)
                            }
                            finish()
                        },
                        onFailed = {
                            Log.w(TAG, "Route deviation PIN failed — triggering SOS")
                            Toast.makeText(context, "🆘 Route deviation SOS sent!", Toast.LENGTH_LONG).show()
                            triggerSosAndStop(userId)
                            finish()
                        },
                        onDismiss = { /* Back button disabled */ }
                    )
                }
            }
        }
    }

    /**
     * Restart the route deviation monitor so the deviation timer resets.
     * The user passed the PIN check but is still off-route — give them another 5 min window.
     */
    private fun restartRouteMonitor() {
        // Stop current monitor
        val stopIntent = Intent(this, RouteDeviationService::class.java).apply {
            action = RouteDeviationService.ACTION_STOP
        }
        stopService(stopIntent)

        // Re-start only if the route is still saved
        if (RouteDeviationService.isRouteActive(this)) {
            val startIntent = Intent(this, RouteDeviationService::class.java).apply {
                action = RouteDeviationService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
        }
    }

    private fun triggerSosAndStop(userId: Long) {
        // Stop monitoring — SOS has been sent
        val stopIntent = Intent(this, RouteDeviationService::class.java).apply {
            action = RouteDeviationService.ACTION_STOP
        }
        stopService(stopIntent)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = NetworkClient.apiService.triggerAlert(
                    AlertTriggerRequest(
                        userId = userId,
                        alertType = "ROUTE_DEVIATION",
                        latitude = UserSession.getLastLatitude(this@RouteDeviationActivity),
                        longitude = UserSession.getLastLongitude(this@RouteDeviationActivity),
                        radiusMeters = 500.0
                    )
                )
                if (response.isSuccessful) {
                    val count = response.body()?.totalRecipientsNotified ?: 0
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(
                            this@RouteDeviationActivity,
                            "🆘 Route deviation SOS sent! $count helpers notified",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SOS alert", e)
            }
        }
    }
}
