package com.example.moexmvp

import android.content.Context

private const val WEB_DESK_PREFS = "moex_web_desk"
private const val KEY_BASE_URL = "base_url"
private const val KEY_MONITOR_ENABLED = "monitor_enabled"
private const val KEY_ORDERS_ON_WEB_ONLY = "orders_on_web_only"
private const val KEY_LAST_EVENT_ID = "last_event_id"
private const val KEY_LAST_OPEN_ID = "last_open_id"
private const val KEY_HAD_OPEN = "had_open"
private const val KEY_PROFIT_ALERT_TRADE_ID = "profit_alert_trade_id"

/** Prefs for Tailscale / LAN web desk monitoring. */
internal object WebDeskPrefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(WEB_DESK_PREFS, Context.MODE_PRIVATE)

    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, "")?.trim().orEmpty()

    fun setBaseUrl(context: Context, url: String) {
        val cleaned = sanitizeBaseUrl(url) ?: url.trim().trimEnd('/')
        prefs(context).edit().putString(KEY_BASE_URL, cleaned).apply()
    }

    /**
     * Extract a usable desk base URL from messy paste
     * (e.g. «привет как http://100.x.x.x:8765» → http://100.x.x.x:8765).
     */
    fun sanitizeBaseUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isBlank()) return null
        val embedded = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
            .find(t)
            ?.value
            ?.trim()
            ?.trimEnd('/', ')', ',', ';')
        val candidate = (embedded ?: t).trim().trimEnd('/')
        val withScheme = when {
            candidate.startsWith("http://", ignoreCase = true) -> candidate
            candidate.startsWith("https://", ignoreCase = true) -> candidate
            else -> "http://$candidate"
        }
        return try {
            val u = java.net.URI(withScheme)
            val host = u.host?.trim().orEmpty()
            if (host.isBlank()) null
            else {
                val port = if (u.port > 0) ":${u.port}" else ""
                "${u.scheme}://$host$port"
            }
        } catch (_: Exception) {
            withScheme.trimEnd('/')
        }
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

    /** Trade id for which ≥3% deposit profit push already fired (once per open). */
    fun profitAlertTradeId(context: Context): Long =
        prefs(context).getLong(KEY_PROFIT_ALERT_TRADE_ID, 0L)

    fun setProfitAlertTradeId(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_PROFIT_ALERT_TRADE_ID, id).apply()
    }

    fun normalizedBaseUrl(context: Context): String? =
        sanitizeBaseUrl(baseUrl(context))
}
