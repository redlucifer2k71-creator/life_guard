package com.lifeguard.app.timer

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
 * Full-screen activity launched by the alarm when a timer check-in is due.
 *
 * This activity appears over the lock screen so users can respond even
 * without unlocking their phone first.
 *
 * On SUCCESS  → reschedule the next alarm and finish.
 * On FAILURE  → trigger SOS alert, stop the timer session, and finish.
 */
class TimerCheckInActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TimerCheckInActivity"
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
        val intervalMin = TimerCheckInManager.getIntervalMinutes(context)

        Log.i(TAG, "Timer check-in activity launched for user $userId")

        setContent {
            LifeGuardTheme {
                Surface(color = Color(0xEE0A0A0A)) {
                    PinChallengeDialog(
                        title = "⏱️ Safety Check-In",
                        subtitle = "You set a ${intervalMin}-minute check-in.\nEnter PIN to confirm you're safe.",
                        countdownSeconds = PIN_TIMEOUT_SECONDS,
                        onSuccess = { enteredPin ->
                            // Verify PIN locally
                            val storedHash = UserSession.getStoredPinHash(context)

                            if (PinHasher.verify(enteredPin, storedHash)) {
                                Toast.makeText(context, "✅ Check-in confirmed!", Toast.LENGTH_SHORT).show()
                                // Reschedule next alarm to keep session going
                                TimerCheckInManager.rescheduleAfterSuccess(context)
                            } else {
                                Toast.makeText(context, "❌ Wrong PIN — Sending SOS!", Toast.LENGTH_LONG).show()
                                triggerSosAndStop(userId)
                            }
                            finish()
                        },
                        onFailed = {
                            // Countdown expired or "I'm in danger" pressed
                            Log.w(TAG, "Timer check-in FAILED — triggering SOS")
                            Toast.makeText(context, "🆘 Check-in missed — SOS sent!", Toast.LENGTH_LONG).show()
                            triggerSosAndStop(userId)
                            finish()
                        },
                        onDismiss = {
                            // Back button disabled, but handle gracefully
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun triggerSosAndStop(userId: Long) {
        // Stop the timer session so it doesn't keep firing after SOS
        TimerCheckInManager.stopSession(this)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val lat = UserSession.getLastLatitude(this@TimerCheckInActivity)
                val lng = UserSession.getLastLongitude(this@TimerCheckInActivity)
                val response = NetworkClient.apiService.triggerAlert(
                    AlertTriggerRequest(
                        userId = userId,
                        alertType = "TIMER_CHECKIN_EXPIRED",
                        latitude = lat,
                        longitude = lng,
                        radiusMeters = 500.0
                    )
                )
                val count = if (response.isSuccessful) response.body()?.totalRecipientsNotified ?: 0 else 0

                // ── Dispatch Emergency SMS to Contact ──
                com.lifeguard.app.sms.SmsAlertSender.sendEmergencySms(
                    context = this@TimerCheckInActivity,
                    alertType = "TIMER_CHECKIN_EXPIRED",
                    latitude = lat,
                    longitude = lng,
                    nearbyHelpersCount = count
                )

                if (response.isSuccessful) {
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(
                            this@TimerCheckInActivity,
                            "🆘 SOS sent! $count helpers notified & SMS sent",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SOS alert", e)
                val lat = UserSession.getLastLatitude(this@TimerCheckInActivity)
                val lng = UserSession.getLastLongitude(this@TimerCheckInActivity)
                com.lifeguard.app.sms.SmsAlertSender.sendEmergencySms(
                    context = this@TimerCheckInActivity,
                    alertType = "TIMER_CHECKIN_EXPIRED",
                    latitude = lat,
                    longitude = lng,
                    nearbyHelpersCount = 0
                )
            }
        }
    }
}
