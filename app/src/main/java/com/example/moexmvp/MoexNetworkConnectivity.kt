package com.example.moexmvp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock

/**
 * Слушает восстановление интернета (типичный сценарий: ночь offline → утром включили сеть без перезапуска приложения).
 * [onNetworkRestored] вызывается на main thread из NetworkCallback (с debounce).
 */
internal class MoexNetworkConnectivityMonitor(
    context: Context,
    private val onNetworkRestored: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var lastOnline = isMoexNetworkAvailable(appContext)
    private var lastRestoreNotifyAtMs = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyIfRestored()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            notifyIfRestored()
        }

        override fun onLost(network: Network) {
            lastOnline = isMoexNetworkAvailable(appContext)
        }
    }

    fun start() {
        lastOnline = isMoexNetworkAvailable(appContext)
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
        val online = isMoexNetworkAvailable(appContext)
        if (online && !lastOnline) {
            lastOnline = true
            val now = SystemClock.elapsedRealtime()
            if (now - lastRestoreNotifyAtMs < NETWORK_RESTORE_DEBOUNCE_MS) return
            lastRestoreNotifyAtMs = now
            onNetworkRestored()
        } else if (!online) {
            lastOnline = false
        }
    }
}

/** Есть ли сейчас маршрут в интернет (без блокирующих HTTP-запросов). */
internal fun isMoexNetworkAvailable(context: Context): Boolean {
    val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        (
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            )
}
