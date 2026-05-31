package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.sqrt

/** MOEX ISS candle times are treated as Europe/Moscow (exchange local). */
internal val moexZoneId: ZoneId = ZoneId.of("Europe/Moscow")

internal fun parseCandleBars(body: String): List<CandleBar> {
    val rows = JSONObject(body)
        .optJSONObject("candles")
        ?.optJSONArray("data")
        ?: JSONArray()

    val result = mutableListOf<CandleBar>()
    for (i in 0 until rows.length()) {
        val row = rows.optJSONArray(i) ?: continue
        val beginAt = runCatching {
            LocalDateTime.parse(row.optString(0), candleTimeFormatter)
        }.getOrNull() ?: continue

        val open = toDouble(row.opt(1)) ?: continue
        val high = toDouble(row.opt(2)) ?: continue
        val low = toDouble(row.opt(3)) ?: continue
        val close = toDouble(row.opt(4)) ?: continue

        result += CandleBar(
            timestamp = beginAt,
            open = open,
            high = high,
            low = low,
            close = close
        )
    }
    return result
}

internal fun toDouble(value: Any?): Double? {
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}

internal fun parseCandleCloseSeries(body: String): Map<LocalDateTime, Double> {
    return parseCandleBars(body).associate { it.timestamp to it.close }
}

internal fun aggregateTo15Minutes(source: Map<LocalDateTime, Double>): Map<LocalDateTime, Double> {
    if (source.isEmpty()) return emptyMap()
    val bars = source.entries
        .sortedBy { it.key }
        .map { (ts, close) ->
            CandleBar(timestamp = ts, open = close, high = close, low = close, close = close)
        }
    return aggregateTo15MinuteBars(bars).associate { it.timestamp to it.close }
}

internal fun aggregateTo15MinuteBars(source: List<CandleBar>): List<CandleBar> {
    if (source.isEmpty()) return emptyList()

    val grouped = linkedMapOf<LocalDateTime, MutableList<CandleBar>>()
    source.sortedBy { it.timestamp }.forEach { bar ->
        val minuteBucket = (bar.timestamp.minute / 15) * 15
        val bucketTime = bar.timestamp.withMinute(minuteBucket).withSecond(0).withNano(0)
        grouped.getOrPut(bucketTime) { mutableListOf() }.add(bar)
    }

    return grouped.map { (bucketTime, bars) ->
        CandleBar(
            timestamp = bucketTime,
            open = bars.first().open,
            high = bars.maxOf { it.high },
            low = bars.minOf { it.low },
            close = bars.last().close
        )
    }
}

internal fun parseCloseSeries(body: String): Map<LocalDate, Double> {
    val rows = JSONObject(body)
        .optJSONObject("history")
        ?.optJSONArray("data")
        ?: JSONArray()

    val result = linkedMapOf<LocalDate, Double>()
    for (i in 0 until rows.length()) {
        val row = rows.optJSONArray(i) ?: continue
        val date = runCatching {
            LocalDate.parse(row.optString(0), tradeDateFormatter)
        }.getOrNull() ?: continue

        val close = when (val rawClose = row.opt(1)) {
            is Number -> rawClose.toDouble()
            is String -> rawClose.toDoubleOrNull()
            else -> null
        } ?: continue

        result[date] = close
    }
    return result
}

internal fun parseHistoryPage(body: String): HistoryPage {
    val root = JSONObject(body)
    val total = root
        .optJSONObject("history.cursor")
        ?.optJSONArray("data")
        ?.optJSONArray(0)
        ?.let { cursorRow ->
            if (cursorRow.length() > 1) cursorRow.optInt(1, -1) else -1
        }
        ?.takeIf { it > 0 }

    return HistoryPage(
        rows = parseCloseSeries(body),
        total = total
    )
}

internal fun shouldContinuePagination(
    start: Int,
    pageSize: Int,
    pageRows: Int,
    total: Int?
): Boolean {
    if (total != null) {
        val nextStart = start + pageRows
        return nextStart < total
    }
    // Fallback when cursor total is absent.
    return pageRows >= pageSize
}
