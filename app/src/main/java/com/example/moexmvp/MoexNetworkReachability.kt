package com.example.moexmvp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal const val SIGNAL_MONITOR_INTERVAL_MS = 15_000L
internal const val SIGNAL_MONITOR_MAX_BACKOFF_MS = 5 * 60_000L

internal fun isDeviceNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    @Suppress("DEPRECATION")
    return cm.activeNetworkInfo?.isConnectedOrConnecting == true
}

internal fun isTransientNetworkFailure(throwable: Throwable): Boolean {
    var current: Throwable? = throwable
    while (current != null) {
        when (current) {
            is UnknownHostException,
            is ConnectException,
            is SocketTimeoutException,
            is SSLException,
            -> return true
        }
        current = current.cause
    }
    return false
}

internal fun nextSignalMonitorDelayMs(consecutiveNetworkFailures: Int): Long {
    if (consecutiveNetworkFailures <= 0) return SIGNAL_MONITOR_INTERVAL_MS
    val multiplier = 1 shl (consecutiveNetworkFailures - 1).coerceAtMost(5)
    return (SIGNAL_MONITOR_INTERVAL_MS * multiplier).coerceAtMost(SIGNAL_MONITOR_MAX_BACKOFF_MS)
}
