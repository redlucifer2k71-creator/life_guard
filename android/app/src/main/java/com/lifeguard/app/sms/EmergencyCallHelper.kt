package com.lifeguard.app.sms

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.lifeguard.app.data.UserSession
import com.lifeguard.app.service.LifeGuardAccessibilityService
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object EmergencyCallHelper {
    private const val TAG = "EmergencyCallHelper"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Places a direct emergency call if CALL_PHONE permission is granted,
     * or opens the system dialer with the emergency contact number pre-filled.
     */
    fun makeEmergencyCall(context: Context, rawPhone: String) {
        val cleanPhone = rawPhone.replace(Regex("[^0-9+]"), "")
        if (cleanPhone.isBlank()) {
            Log.w(TAG, "Empty phone number — cannot initiate call")
            return
        }

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$cleanPhone")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(callIntent)
                Log.i(TAG, "Direct emergency call initiated to $cleanPhone")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct ACTION_CALL failed (${e.message}), falling back to ACTION_DIAL", e)
            }
        }

        // Fallback: Launch dialer with number pre-filled (requires ZERO permissions)
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$cleanPhone")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            Log.i(TAG, "Opened system dialer for emergency contact $cleanPhone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch phone dialer: ${e.message}")
        }
    }

    /**
     * Dispatches WhatsApp emergency alert:
     * 1. If CallMeBot API key is configured in settings:
     *    Dispatches 100% silently in background via Cloud Gateway (NO app opens!).
     * 2. Fallback / Direct WhatsApp:
     *    Arms LifeGuardAccessibilityService so the Send button is clicked automatically
     *    and the screen immediately closes back without requiring user interaction.
     */
    fun sendWhatsAppEmergencyAlert(context: Context, rawPhone: String, message: String) {
        var cleanPhone = rawPhone.replace(Regex("[^0-9]"), "")
        if (cleanPhone.isBlank()) {
            Log.w(TAG, "Empty phone number — cannot send WhatsApp alert")
            return
        }

        // Auto-prefix Indian 10-digit phone numbers with 91 country code if missing
        if (cleanPhone.length == 10) {
            cleanPhone = "91$cleanPhone"
        }

        val callMeBotApiKey = UserSession.getCallMeBotApiKey(context).trim()
        if (callMeBotApiKey.isNotBlank()) {
            sendSilentCloudWhatsApp(cleanPhone, message, callMeBotApiKey)
            return
        }

        // On-device automated dispatch
        launchAutomatedWhatsAppIntent(context, cleanPhone, message)
    }

    /**
     * Sends WhatsApp message 100% silently in the background via CallMeBot gateway.
     * Zero UI popups or app switches on the device!
     */
    private fun sendSilentCloudWhatsApp(cleanPhone: String, message: String, apiKey: String) {
        val encodedText = Uri.encode(message)
        // CallMeBot phone format expects leading '+' e.g. +919876543210 or just digits
        val targetPhone = if (cleanPhone.startsWith("+")) cleanPhone else "+$cleanPhone"
        val cloudUrl = "https://api.callmebot.com/whatsapp.php?phone=$targetPhone&text=$encodedText&apikey=$apiKey"

        val request = Request.Builder()
            .url(cloudUrl)
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Cloud WhatsApp dispatch failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.i(TAG, "Cloud WhatsApp dispatch response code: ${response.code}")
                response.close()
            }
        })
    }

    /**
     * Launches WhatsApp with auto-send arming.
     */
    fun launchAutomatedWhatsAppIntent(context: Context, cleanPhone: String, message: String) {
        // Arm accessibility auto-clicker before opening WhatsApp
        LifeGuardAccessibilityService.armAutoSend()

        val encodedText = Uri.encode(message)
        val whatsappUrl = "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedText"
        val parsedUri = Uri.parse(whatsappUrl)

        // Try standard WhatsApp first
        try {
            val intent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "Dispatched WhatsApp emergency message with auto-send armed to $cleanPhone")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Standard WhatsApp not available: ${e.message}")
        }

        // Try WhatsApp Business next
        try {
            val businessIntent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
                setPackage("com.whatsapp.w4b")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(businessIntent)
            Log.i(TAG, "Dispatched WhatsApp Business emergency message with auto-send armed to $cleanPhone")
            return
        } catch (e: Exception) {
            Log.w(TAG, "WhatsApp Business not available: ${e.message}")
        }

        // Final fallback: standard browser
        try {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallbackIntent)
            Log.i(TAG, "Dispatched WhatsApp alert via browser fallback")
        } catch (e: Exception) {
            Log.e(TAG, "All WhatsApp launch attempts failed: ${e.message}")
        }
    }
}
