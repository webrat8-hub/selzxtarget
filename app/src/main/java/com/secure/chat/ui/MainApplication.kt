package com.secure.chat.ui

import android.app.Application

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Pasang jaring crash handler secara global untuk semua Activity
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }
}
