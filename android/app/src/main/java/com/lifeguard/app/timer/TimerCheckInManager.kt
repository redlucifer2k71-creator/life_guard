package com.lifeguard.app.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Manages the recurring timer check-in session using AlarmManager.
 * Uses setExactAndAllowWhileIdle to wake the CPU even during Android Doze mode.
 *
 * Flow:
 * 1. User selects an interval (e.g. 10 minutes) and taps START.
 * 2. scheduleNextAlarm() sets a one-shot exact alarm.
 * 3. When the alarm fires, TimerCheckInReceiver launches the PIN challenge.
 * 4. If PIN is entered correctly → reschedule next alarm (continuous session).
 * 5. If PIN fails/times out → trigger SOS alert.
 */
object TimerCheckInManager {

    private const val TAG = "TimerCheckInManager"
    private const val PREFS_NAME = "lifeguard_timer"
    private const val KEY_ACTIVE = "timer_active"
    private const val KEY_INTERVAL_MS = "interval_ms"

    // Intent action used to identify this alarm
    const val ACTION_TIMER_CHECKIN = "com.lifeguard.app.ACTION_TIMER_CHECKIN"

    // Request code for PendingIntent
    private const val REQUEST_CODE = 9001

    /** Start a new timer session with the given interval. */
    fun startSession(context: Context, intervalMinutes: Int) {
        val intervalMs = intervalMinutes * 60 * 1000L
        saveSessionPrefs(context, true, intervalMs)
        scheduleNextAlarm(context, intervalMs)
        Log.i(TAG, "Timer session started — interval: ${intervalMinutes}min")
    }

    /** Cancel the current timer session. */
    fun stopSession(context: Context) {
        saveSessionPrefs(context, false, 0L)
        cancelAlarm(context)
        Log.i(TAG, "Timer session stopped")
    }

    /** Called by TimerCheckInReceiver after a successful PIN — reschedules the next alarm. */
    fun rescheduleAfterSuccess(context: Context) {
        val intervalMs = getIntervalMs(context)
        if (intervalMs > 0 && isActive(context)) {
            scheduleNextAlarm(context, intervalMs)
            Log.i(TAG, "Next check-in rescheduled in ${intervalMs / 60000}min")
        }
    }

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)

    fun getIntervalMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_INTERVAL_MS, 0L)

    fun getIntervalMinutes(context: Context): Int =
        (getIntervalMs(context) / 60000).toInt()

    // ── Private helpers ──

    private fun scheduleNextAlarm(context: Context, intervalMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + intervalMs

        val pendingIntent = buildPendingIntent(context)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ requires permission check before exact alarms
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                    )
                } else {
                    // Fallback to inexact — still works, just may be a few minutes late
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                    )
                    Log.w(TAG, "Exact alarm permission not granted — using inexact alarm")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
            }
            Log.i(TAG, "Alarm set for ${intervalMs / 1000}s from now")
        } catch (e: SecurityException) {
            Log.e(TAG, "Could not schedule exact alarm: ${e.message}")
        }
    }

    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context))
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TimerCheckInReceiver::class.java).apply {
            action = ACTION_TIMER_CHECKIN
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun saveSessionPrefs(context: Context, active: Boolean, intervalMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, active)
            .putLong(KEY_INTERVAL_MS, intervalMs)
            .apply()
    }
}
