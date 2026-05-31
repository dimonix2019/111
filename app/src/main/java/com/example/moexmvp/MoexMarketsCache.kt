package com.example.moexmvp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "moex_markets_snapshot"
private const val KEY_PREFIX = "snap_v1_"

/** TTL локального снимка «Рынка» при недоступности MOEX (мс). */
internal const val MARKETS_SNAPSHOT_TTL_MS = 15L * 60L * 1000L

/** Для мгновенного старта: показать сохранённый снимок даже если он старше [MARKETS_SNAPSHOT_TTL_MS]. */
internal const val MARKETS_SNAPSHOT_DISPLAY_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

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

/** Для юнит-тестов и атомарной сериализации снимка. */
internal fun encodeMarketsSnapshotJson(success: UiState.Success, savedAtMillis: Long): String =
    JSONObject().apply {
        put("savedAtMillis", savedAtMillis)
        put("loadedAt", success.loadedAt)
        put("points", JSONArray().apply { success.points.forEach { put(dataPointToJson(it)) } })
        put("tatn", JSONArray().apply { success.tatnCandles.forEach { put(candleToJson(it)) } })
        put("tatnp", JSONArray().apply { success.tatnpCandles.forEach { put(candleToJson(it)) } })
    }.toString()

/**
 * Разбор снимка; [nowMillis] и [ttlMs] задают «свежесть» (как при чтении из prefs).
 */
internal fun decodeMarketsSnapshotIfFresh(
    raw: String,
    nowMillis: Long,
    ttlMs: Long = MARKETS_SNAPSHOT_TTL_MS
): UiState.Success? {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val savedAt = root.optLong("savedAtMillis", 0L)
    if (savedAt <= 0L || nowMillis - savedAt > ttlMs) return null
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

internal fun saveMarketsSnapshot(context: Context, period: Period, success: UiState.Success) {
    val json = encodeMarketsSnapshotJson(success, System.currentTimeMillis())
    context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(key(period), json)
        .apply()
}

/**
 * Возвращает последний успешный снимок «Рынка» для периода, если он не старше [MARKETS_SNAPSHOT_TTL_MS].
 * Используется при недоступности MOEX (сеть / HTTP).
 */
internal fun readMarketsSnapshotWithMaxAge(
    context: Context,
    period: Period,
    maxAgeMs: Long,
): UiState.Success? {
    val raw = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(key(period), null)
        ?: return null
    return decodeMarketsSnapshotIfFresh(raw, System.currentTimeMillis(), ttlMs = maxAgeMs)
}

internal fun readMarketsSnapshotIfFresh(context: Context, period: Period): UiState.Success? =
    readMarketsSnapshotWithMaxAge(context, period, MARKETS_SNAPSHOT_TTL_MS)

/** Снимок для первого кадра UI (может быть устаревшим — помечается marketsStale). */
internal fun readMarketsSnapshotForDisplay(context: Context, period: Period): UiState.Success? =
    readMarketsSnapshotWithMaxAge(context, period, MARKETS_SNAPSHOT_DISPLAY_MAX_AGE_MS)

internal fun marketsSnapshotAgeMillis(context: Context, period: Period): Long? {
    val raw = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(key(period), null)
        ?: return null
    val savedAt = runCatching { JSONObject(raw).optLong("savedAtMillis", 0L) }.getOrDefault(0L)
    if (savedAt <= 0L) return null
    return System.currentTimeMillis() - savedAt
}

/** Снимок «Рынка» свежий — сеть MOEX для daily-ряда не нужна. */
internal fun marketsSnapshotFreshEnough(context: Context, period: Period): Boolean {
    val age = marketsSnapshotAgeMillis(context, period) ?: return false
    return age <= MARKETS_SNAPSHOT_TTL_MS
}
