package com.secure.chat.ui

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.secure.chat.R
import java.io.PrintWriter
import java.io.StringWriter

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // ============ CRASH LOGGER OTOMATIS COPY & SHARE ============
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.close()
                val stackTrace = sw.toString()

                val errorText = "=== CRASH SECURE CHAT ===\n$stackTrace"

                // 1. Otomatis Copy ke Clipboard HP
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Crash Log", errorText)
                clipboard.setPrimaryClip(clip)

                // 2. Buka menu Share (Biar bisa langsung oper ke WA/Notes)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, errorText)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(Intent.createChooser(intent, "Aplikasi Crash! Kirim log ke:"))

                // Kasih jeda sebentar biar OS sempet proses clipboard & intent share
                Thread.sleep(2000)
            } catch (_: Exception) {}

            android.os.Process.killProcess(android.os.Process.myPid())
            java.lang.System.exit(10)
        }
        // ============================================================

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash_target)

        val logo = findViewById<ImageView>(R.id.splashLogo)
        val title = findViewById<TextView>(R.id.splashTitle)
        val subtitle = findViewById<TextView>(R.id.splashSubtitle)

        // Fade in
        logo.alpha = 0f
        title.alpha = 0f
        subtitle.alpha = 0f

        logo.animate().alpha(1f).duration = 800
        title.animate().alpha(1f).duration = 1200
        subtitle.animate().alpha(1f).duration = 1600

        // Pulse
        val pulse = ValueAnimator.ofFloat(1f, 1.08f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                logo.scaleX = it.animatedValue as Float
                logo.scaleY = it.animatedValue as Float
            }
        }
        pulse.start()

        // After splash, go to ChatActivity
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, ChatActivity::class.java))
            finish()
        }, 2500)
    }
}
