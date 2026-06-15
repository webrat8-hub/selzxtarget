package com.secure.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.PrintWriter
import java.io.StringWriter

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            pw.close()
            val stackTrace = sw.toString()

            val errorText = "=== CRASH SECURE CHAT ===\n$stackTrace"

            // 1. Otomatis Copy ke Clipboard
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Crash Log", errorText)
            clipboard.setPrimaryClip(clip)

            // 2. Buka menu Share
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, errorText)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(intent, "Aplikasi Crash! Kirim log ke:"))

            Thread.sleep(2000)
        } catch (_: Exception) {}

        android.os.Process.killProcess(android.os.Process.myPid())
        java.lang.System.exit(10)
    }
}
