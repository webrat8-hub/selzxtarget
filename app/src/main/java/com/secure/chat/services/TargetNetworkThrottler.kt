package com.secure.chat.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log

class TargetNetworkThrottler {

    companion object {
        private const val TAG = "TargetThrottler"
        var isThrottling = false
            private set
    }

    private var connectivityManager: ConnectivityManager? = null

    fun start(context: Context) {
        isThrottling = true
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager?.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (isThrottling) {
                        connectivityManager?.bindProcessToNetwork(null)
                    }
                }
            })
        }

        Log.d(TAG, "Network throttling started")
    }

    fun stop(context: Context) {
        isThrottling = false
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager?.bindProcessToNetwork(null)
        }
        Log.d(TAG, "Network throttling stopped")
    }
}