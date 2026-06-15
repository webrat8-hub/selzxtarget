package com.secure.chat.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class TargetScreenLocker {

    fun lock(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "SecureChat:Lock"
        )
        wakeLock.acquire(3000)

        // Lock via device admin
        val dm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val admin = android.content.ComponentName(context, TargetDeviceAdmin::class.java)
        if (dm.isAdminActive(admin)) {
            dm.lockNow()
        }

        wakeLock.release()
    }

    fun unlock(context: Context) {
        // Can only unlock if we have keyguard dismiss
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activity = context as? android.app.Activity
            activity?.let {
                keyguardManager.requestDismissKeyguard(activity, null)
            }
        }
    }
}