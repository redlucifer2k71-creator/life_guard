package com.lifeguard.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LifeGuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LifeGuardA11y"

        @Volatile
        var isAutoSendArmed = false
        private var armExpiryTimestamp = 0L

        /**
         * Arms the auto-sender for 15 seconds during an emergency trigger.
         */
        fun armAutoSend() {
            isAutoSendArmed = true
            armExpiryTimestamp = System.currentTimeMillis() + 15_000L
            Log.i(TAG, "Auto-send armed for emergency dispatch")
        }

        fun disarmAutoSend() {
            isAutoSendArmed = false
        }

        /**
         * Checks whether Life Guard's Accessibility Service is currently enabled in Android Settings.
         */
        fun isEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${LifeGuardAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = arrayOf(
                "com.whatsapp",
                "com.whatsapp.w4b",
                "com.google.android.apps.messaging",
                "com.android.mms",
                "com.samsung.android.messaging"
            )
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
        Log.i(TAG, "Life Guard Accessibility Service connected & active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutoSendArmed || event == null) return
        if (System.currentTimeMillis() > armExpiryTimestamp) {
            isAutoSendArmed = false
            return
        }

        val rootNode = rootInActiveWindow ?: return
        val pkg = event.packageName?.toString() ?: ""

        if (pkg.contains("whatsapp") || pkg.contains("messaging") || pkg.contains("mms")) {
            findAndClickSend(rootNode)
        }
    }

    private fun findAndClickSend(root: AccessibilityNodeInfo) {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        // 1. Check known view IDs
        val viewIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.google.android.apps.messaging:id/send_message_button_icon",
            "com.google.android.apps.messaging:id/send_message_button",
            "com.samsung.android.messaging:id/send_button"
        )
        for (id in viewIds) {
            candidates.addAll(root.findAccessibilityNodeInfosByViewId(id))
        }

        // 2. Check text or contentDescription
        if (candidates.isEmpty()) {
            candidates.addAll(root.findAccessibilityNodeInfosByText("Send"))
        }

        // 3. Recursive inspection for contentDescription containing "send"
        if (candidates.isEmpty()) {
            scanNodesRecursively(root, candidates)
        }

        for (node in candidates) {
            var clickableNode: AccessibilityNodeInfo? = node
            while (clickableNode != null && !clickableNode.isClickable) {
                clickableNode = clickableNode.parent
            }

            if (clickableNode != null && clickableNode.isClickable) {
                val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    Log.i(TAG, "Automatically tapped Send button in emergency!")
                    isAutoSendArmed = false

                    // Briefly delay to ensure network dispatch, then return back to app
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }, 300)
                    break
                }
            }
        }
    }

    private fun scanNodesRecursively(node: AccessibilityNodeInfo?, output: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc == "send" || desc == "send message" || desc == "send sms") {
            output.add(node)
            return
        }
        for (i in 0 until node.childCount) {
            scanNodesRecursively(node.getChild(i), output)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Life Guard Accessibility Service interrupted")
    }
}
