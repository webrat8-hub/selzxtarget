package com.secure.chat.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.secure.chat.R

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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

        // After splash, go to fake chat
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, ChatActivity::class.java))
            finish()
        }, 2500)
    }
}