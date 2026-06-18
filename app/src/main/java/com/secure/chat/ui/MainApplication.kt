package com.secure.chat.ui

import android.app.Application
import com.google.firebase.FirebaseApp

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 🔥 FIX: Inisialisasi FirebaseApp WAJIB sebelum akses Firebase
        FirebaseApp.initializeApp(this)
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }
}
