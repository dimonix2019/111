package com.example.moexmvp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * Слушает восстановление интернета (типичный сценарий: ночь offline → утром включили сеть без перезапуска приложения).
 * [onNetworkRestored] вызывается на main thread из NetworkCallback.
 */
internal class MoexNetworkConnectivityMonitor(
    context: Context,
    private val onNetworkRestored: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var lastOnline = isNetworkValidated()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyIfRestored()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            notifyIfRestored()
        }

        override fun onLost(network: Network) {
            lastOnline = isNetworkValidated()
        }
    }

    fun start() {
        lastOnline = isNetworkValidated()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { connectivityManager.registerNetworkCallback(request, callback) }
        }
    }

    fun stop() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun notifyIfRestored() {
        val online = isNetworkValidated()
        if (online && !lastOnline) {
            lastOnline = true
            onNetworkRestored()
        } else if (!online) {
            lastOnline = false
        }
    }

    private fun isNetworkValidated(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                )
    }
}
