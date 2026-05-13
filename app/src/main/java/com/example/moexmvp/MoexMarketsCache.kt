package com.example.moexmvp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "moex_markets_snapshot"
private const val KEY_PREFIX = "snap_v1_"
private const val TTL_MS = 15L * 60L * 1000L

private fun key(period: Period) = KEY_PREFIX + period.name

private fun dataPointToJson(p: DataPoint) = JSONObject().apply {
    put("ts", p.timestampMillis)
    put("td", p.tradeDate)
    put("t1", p.tatnClose)
    put("t2", p.tatnpClose)
    put("sp", p.spreadPercent)
    put("df", p.diff)
    put("z", p.zScore)
}

private fun jsonToDataPoint(o: JSONObject): DataPoint? = runCatching {
    DataPoint(
        timestampMillis = o.getLong("ts"),
        tradeDate = o.getString("td"),
        tatnClose = o.getDouble("t1"),
        tatnpClose = o.getDouble("t2"),
        spreadPercent = o.getDouble("sp"),
        diff = o.getDouble("df"),
        zScore = o.getDouble("z")
    )
}.getOrNull()

private fun candleToJson(c: CandlePoint) = JSONObject().apply {
    put("lb", c.label)
    put("o", c.open)
    put("h", c.high)
    put("l", c.low)
    put("cl", c.close)
}

private fun jsonToCandle(o: JSONObject): CandlePoint? = runCatching {
    CandlePoint(
        label = o.getString("lb"),
        open = o.getDouble("o"),
        high = o.getDouble("h"),
        low = o.getDouble("l"),
        close = o.getDouble("cl")
    )
}.getOrNull()

internal fun saveMarketsSnapshot(context: Context, period: Period, success: UiState.Success) {
    val root = JSONObject().apply {
        put("savedAtMillis", System.currentTimeMillis())
        put("loadedAt", success.loadedAt)
        put("points", JSONArray().apply { success.points.forEach { put(dataPointToJson(it)) } })
        put("tatn", JSONArray().apply { success.tatnCandles.forEach { put(candleToJson(it)) } })
        put("tatnp", JSONArray().apply { success.tatnpCandles.forEach { put(candleToJson(it)) } })
    }
    context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(key(period), root.toString())
        .apply()
}

/**
 * Возвращает последний успешный снимок «Рынка» для периода, если он не старше [TTL_MS].
 * Используется при недоступности MOEX (сеть / HTTP).
 */
internal fun readMarketsSnapshotIfFresh(context: Context, period: Period): UiState.Success? {
    val raw = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(key(period), null)
        ?: return null
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val savedAt = root.optLong("savedAtMillis", 0L)
    if (savedAt <= 0L || System.currentTimeMillis() - savedAt > TTL_MS) return null
    val loadedAt = root.optString("loadedAt", "").ifBlank { "—" }
    val pts = root.optJSONArray("points") ?: return null
    val points = buildList {
        for (i in 0 until pts.length()) {
            val o = pts.optJSONObject(i) ?: continue
            jsonToDataPoint(o)?.let { add(it) }
        }
    }
    if (points.isEmpty()) return null
    fun parseCandles(name: String): List<CandlePoint> {
        val arr = root.optJSONArray(name) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                jsonToCandle(o)?.let { add(it) }
            }
        }
    }
    return UiState.Success(
        points = points,
        loadedAt = loadedAt,
        tatnCandles = parseCandles("tatn"),
        tatnpCandles = parseCandles("tatnp"),
        marketsDataSource = MarketsDataSource.FifteenMinuteCache
    )
}
