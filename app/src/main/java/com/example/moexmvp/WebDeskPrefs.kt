package com.example.moexmvp

import android.content.Context

private const val WEB_DESK_PREFS = "moex_web_desk"
private const val KEY_BASE_URL = "base_url"
private const val KEY_MONITOR_ENABLED = "monitor_enabled"
private const val KEY_ORDERS_ON_WEB_ONLY = "orders_on_web_only"
private const val KEY_LAST_EVENT_ID = "last_event_id"
private const val KEY_LAST_OPEN_ID = "last_open_id"
private const val KEY_HAD_OPEN = "had_open"

/** Prefs for Tailscale / LAN web desk monitoring. */
internal object WebDeskPrefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(WEB_DESK_PREFS, Context.MODE_PRIVATE)

    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, "")?.trim().orEmpty()

    fun setBaseUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_BASE_URL, url.trim().trimEnd('/')).apply()
    }

    /** When true, poll web events and show push; WebView desk is available. */
    fun isMonitorEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MONITOR_ENABLED, false)

    fun setMonitorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONITOR_ENABLED, enabled).apply()
    }

    /**
     * When monitor is on, default true: phone must not AUTO-place orders
     * (web is the only executor).
     */
    fun isOrdersOnWebOnly(context: Context): Boolean {
        if (!isMonitorEnabled(context)) return false
        return prefs(context).getBoolean(KEY_ORDERS_ON_WEB_ONLY, true)
    }

    fun setOrdersOnWebOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ORDERS_ON_WEB_ONLY, value).apply()
    }

    fun lastEventId(context: Context): Long =
        prefs(context).getLong(KEY_LAST_EVENT_ID, 0L)

    fun setLastEventId(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_LAST_EVENT_ID, id).apply()
    }

    fun lastOpenId(context: Context): Long =
        prefs(context).getLong(KEY_LAST_OPEN_ID, 0L)

    fun setLastOpenId(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_LAST_OPEN_ID, id).apply()
    }

    fun hadOpen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAD_OPEN, false)

    fun setHadOpen(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAD_OPEN, value).apply()
    }

    fun normalizedBaseUrl(context: Context): String? {
        val raw = baseUrl(context)
        if (raw.isBlank()) return null
        val withScheme = when {
            raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("https://", ignoreCase = true) -> raw
            else -> "http://$raw"
        }
        return withScheme.trimEnd('/')
    }
}
