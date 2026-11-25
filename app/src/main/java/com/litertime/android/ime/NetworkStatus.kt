package com.litertime.android.ime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Small connectivity helper used to decide whether the online-model cloud badge is shown. */
object NetworkStatus {
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}