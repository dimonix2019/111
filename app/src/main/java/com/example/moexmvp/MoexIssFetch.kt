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

internal fun loadCloseSeries(
    secId: String,
    from: LocalDate,
    till: LocalDate
): Map<LocalDate, Double> {
    val pageSize = 100
    var start = 0
    val result = linkedMapOf<LocalDate, Double>()

    while (true) {
        val url = buildString {
            append("https://iss.moex.com/iss/history/engines/stock/markets/shares/boards/TQBR/securities/")
            append(secId)
            append(".json?iss.meta=off&history.columns=TRADEDATE,CLOSE")
            append("&from=").append(from)
            append("&till=").append(till)
            append("&limit=").append(pageSize)
            append("&start=").append(start)
        }
        val request = Request.Builder().url(url).build()
        val page = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while loading $secId")
            }
            val body = response.body?.string().orEmpty()
            parseHistoryPage(body)
        }
        if (page.rows.isEmpty()) break
        result.putAll(page.rows)
        if (!shouldContinuePagination(start, pageSize, page.rows.size, page.total)) break
        start += pageSize
    }

    return result
}

internal fun fetchData(period: Period): FetchedData {
    val till = LocalDate.now(moexZoneId)
    val from = period.from(till)

    val tatnBars = loadCandleSeries("TATN", period, from, till)
    val tatnpBars = loadCandleSeries("TATNP", period, from, till)

    val tatnByTime = tatnBars.associateBy { it.timestamp }
    val tatnpByTime = tatnpBars.associateBy { it.timestamp }
    val alignedTimes = (tatnByTime.keys + tatnpByTime.keys).sorted()

    val points = alignedTimes.mapNotNull { time ->
        val tatn = tatnByTime[time]?.close ?: return@mapNotNull null
        val tatnp = tatnpByTime[time]?.close ?: return@mapNotNull null
        if (tatnp == 0.0) return@mapNotNull null

        val spread = (tatn / tatnp - 1.0) * 100.0
        DataPoint(
            timestampMillis = time.atZone(moexZoneId).toInstant().toEpochMilli(),
            tradeDate = formatLabelForPeriod(time, period),
            tatnClose = tatn,
            tatnpClose = tatnp,
            spreadPercent = spread,
            diff = tatn - tatnp,
            zScore = 0.0
        )
    }
    val pointsWithZ = applyZScoresDefault(points)

    return FetchedData(
        points = pointsWithZ,
        tatnCandles = tatnBars.map {
            CandlePoint(
                label = formatLabelForPeriod(it.timestamp, period),
                open = it.open,
                high = it.high,
                low = it.low,
                close = it.close
            )
        },
        tatnpCandles = tatnpBars.map {
            CandlePoint(
                label = formatLabelForPeriod(it.timestamp, period),
                open = it.open,
                high = it.high,
                low = it.low,
                close = it.close
            )
        }
    )
}

internal fun formatLabelForPeriod(timestamp: LocalDateTime, period: Period): String {
    return if (period == Period.OneDay) {
        timestamp.toLocalTime().format(intradayLabelFormatter)
    } else {
        timestamp.toLocalDate().format(tradeDateFormatter)
    }
}

internal fun loadCandleSeries(
    secId: String,
    period: Period,
    from: LocalDate,
    till: LocalDate
): List<CandleBar> {
    val raw = when (period) {
        Period.OneDay -> loadCandleBars(secId, from, till, interval = 1)
        else -> loadCandleBars(secId, from, till, interval = 24)
    }
    val transformed = if (period == Period.OneDay) {
        aggregateTo15MinuteBars(raw)
    } else {
        raw
    }
    return transformed.sortedBy { it.timestamp }
}

internal fun loadCandleBars(
    secId: String,
    from: LocalDate,
    till: LocalDate,
    interval: Int
): List<CandleBar> {
    val pageSize = 500
    var start = 0
    val allBars = mutableListOf<CandleBar>()

    while (true) {
        val url = buildString {
            append("https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/")
            append(secId)
            append("/candles.json?iss.meta=off&candles.columns=begin,open,high,low,close")
            append("&interval=").append(interval)
            append("&from=").append(from)
            append("&till=").append(till)
            append("&limit=").append(pageSize)
            append("&start=").append(start)
        }
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()
        var lastError: IOException? = null
        var page: List<CandleBar> = emptyList()
        for (attempt in 0 until 3) {
            try {
                page = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} while loading candles for $secId")
                    }
                    parseCandleBars(response.body?.string().orEmpty())
                }
                lastError = null
                break
            } catch (e: IOException) {
                lastError = e
                if (attempt < 2) Thread.sleep(400L * (attempt + 1))
            }
        }
        if (lastError != null) throw lastError
        if (page.isEmpty()) break
        allBars += page
        if (page.size < pageSize) break
        start += pageSize
    }

    return allBars
}
internal suspend fun loadState(context: Context, period: Period): UiState = withContext(Dispatchers.IO) {
    try {
        val data = fetchData(period)
        if (data.points.isEmpty()) {
            UiState.Empty
        } else {
            val success = UiState.Success(
                points = data.points,
                loadedAt = LocalDateTime.now().format(updatedAtFormatter),
                tatnCandles = data.tatnCandles,
                tatnpCandles = data.tatnpCandles,
                marketsDataSource = MarketsDataSource.Network
            )
            saveMarketsSnapshot(context.applicationContext, period, success)
            success
        }
    } catch (t: Throwable) {
        readMarketsSnapshotIfFresh(context.applicationContext, period)
            ?: UiState.Error(t.message ?: "Unknown error")
    }
}

/**
 * Fetch 15m-aligned spread rows from MOEX (10m ISS → 15m bars). Does not read/write local cache.
 */
