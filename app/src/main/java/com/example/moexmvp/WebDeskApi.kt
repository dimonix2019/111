package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

internal data class WebDeskHealthLive(
    val ok: Boolean,
    val stale: Boolean,
    val monitorAlive: Boolean,
    val lastBar: String?,
    val lastZ: Double?,
    val message: String?,
)

internal data class WebDeskEvent(
    val id: Long,
    val tsMs: Long,
    val level: String,
    val message: String,
)

internal data class WebDeskStatusSnapshot(
    val events: List<WebDeskEvent>,
    val openId: Long?,
    val openDirection: String?,
    val openEntryTime: String?,
    val monitorRunning: Boolean,
    val lastMessage: String?,
)

/** Lite desk open mark for profit-% alerts (deposit base). */
internal data class WebDeskOpenMark(
    val openId: Long?,
    val direction: String?,
    val unrealizedPnlRub: Double?,
    val netApproxRub: Double?,
    val entryDepositRub: Double,
)

/** HTTP client for strategy-web desk over Tailscale/LAN. */
internal object WebDeskApi {
    suspend fun fetchHealthLive(context: Context): Result<WebDeskHealthLive> =
        withContext(Dispatchers.IO) {
            val base = WebDeskPrefs.normalizedBaseUrl(context)
                ?: return@withContext Result.failure(IllegalStateException("URL не задан"))
            runCatching {
                val req = Request.Builder().url("$base/api/health/live").get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    val o = JSONObject(body)
                    WebDeskHealthLive(
                        ok = o.optString("status") == "ok",
                        stale = o.optBoolean("stale", false),
                        monitorAlive = o.optBoolean("monitor_alive", false),
                        lastBar = o.optString("last_bar").takeIf { it.isNotBlank() && it != "null" },
                        lastZ = o.optDouble("last_z").takeIf { o.has("last_z") && !o.isNull("last_z") },
                        message = o.optString("last_message").takeIf { it.isNotBlank() && it != "null" },
                    )
                }
            }
        }

    suspend fun fetchStatus(context: Context): Result<WebDeskStatusSnapshot> =
        withContext(Dispatchers.IO) {
            val base = WebDeskPrefs.normalizedBaseUrl(context)
                ?: return@withContext Result.failure(IllegalStateException("URL не задан"))
            runCatching {
                val req = Request.Builder().url("$base/api/live/status").get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    val root = JSONObject(body)
                    val eventsJson = root.optJSONArray("events")
                    val events = buildList {
                        if (eventsJson != null) {
                            for (i in 0 until eventsJson.length()) {
                                val e = eventsJson.optJSONObject(i) ?: continue
                                add(
                                    WebDeskEvent(
                                        id = e.optLong("id", 0L),
                                        tsMs = e.optLong("ts_ms", 0L),
                                        level = e.optString("level", "info"),
                                        message = e.optString("message", ""),
                                    ),
                                )
                            }
                        }
                    }
                    val open = root.optJSONObject("open")
                    val openId = open?.optLong("id")?.takeIf { open.has("id") && !open.isNull("id") }
                    val mon = root.optJSONObject("monitor")
                    WebDeskStatusSnapshot(
                        events = events,
                        openId = openId,
                        openDirection = open?.optString("direction")?.takeIf { it.isNotBlank() },
                        openEntryTime = open?.optString("entry_time")?.takeIf { it.isNotBlank() },
                        monitorRunning = mon?.optBoolean("running", false) == true,
                        lastMessage = mon?.optString("last_message")?.takeIf {
                            it.isNotBlank() && it != "null"
                        },
                    )
                }
            }
        }

    /** Lite desk → статус чеклиста + спред для шторки монитора. */
    suspend fun fetchDeskShade(context: Context): Result<WebDeskShadeSnapshot?> =
        withContext(Dispatchers.IO) {
            val base = WebDeskPrefs.normalizedBaseUrl(context)
                ?: return@withContext Result.failure(IllegalStateException("URL не задан"))
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/trade/desk?days=1&lite=1")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    buildWebDeskShadeSnapshot(JSONObject(body))
                }
            }
        }

    /**
     * Lite trade desk: open mark + entry_deposit for ≥3% profit push
     * (same base as web «Сделка» % от вложения).
     */
    suspend fun fetchOpenMark(context: Context): Result<WebDeskOpenMark?> =
        withContext(Dispatchers.IO) {
            val base = WebDeskPrefs.normalizedBaseUrl(context)
                ?: return@withContext Result.failure(IllegalStateException("URL не задан"))
            runCatching {
                val req = Request.Builder()
                    .url("$base/api/trade/desk?days=1&lite=1")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    val root = JSONObject(body)
                    val open = root.optJSONObject("open")
                        ?: return@use null
                    val mark = open.optJSONObject("mark")
                    val settings = root.optJSONObject("settings")
                    val deposit = settings?.optDouble("entry_deposit_rub")
                        ?.takeIf { settings.has("entry_deposit_rub") && !settings.isNull("entry_deposit_rub") }
                        ?: 10_000.0
                    fun optMarkRub(key: String): Double? =
                        mark?.optDouble(key)?.takeIf { mark.has(key) && !mark.isNull(key) }
                    WebDeskOpenMark(
                        openId = open.optLong("id").takeIf { open.has("id") && !open.isNull("id") },
                        direction = open.optString("direction").takeIf { it.isNotBlank() },
                        unrealizedPnlRub = optMarkRub("unrealized_pnl_rub"),
                        netApproxRub = optMarkRub("net_approx_rub"),
                        entryDepositRub = if (deposit.isFinite() && deposit > 0) deposit else 10_000.0,
                    )
                }
            }
        }
}
