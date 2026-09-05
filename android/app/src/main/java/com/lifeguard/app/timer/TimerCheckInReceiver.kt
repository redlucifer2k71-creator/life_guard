package com.lifeguard.app.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the AlarmManager broadcast when a timer check-in is due.
 * Launches TimerCheckInActivity as a full-screen overlay (works even from lock screen).
 *
 * This BroadcastReceiver must run quickly — it just fires an Activity/Service intent
 * and returns. All heavy work happens in TimerCheckInActivity.
 */
class TimerCheckInReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimerCheckInReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TimerCheckInManager.ACTION_TIMER_CHECKIN) return

        Log.i(TAG, "⏰ Timer check-in alarm fired!")

        // Only proceed if the session is still active
        if (!TimerCheckInManager.isActive(context)) {
            Log.w(TAG, "Timer session no longer active — ignoring alarm")
            return
        }

        // Launch the full-screen PIN challenge activity
        val activityIntent = Intent(context, TimerCheckInActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(activityIntent)
    }
}
