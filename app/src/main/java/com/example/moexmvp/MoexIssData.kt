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
    val till = LocalDate.now()
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
    val pointsWithZ = applyZScores(points)

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

internal fun applyZScores(points: List<DataPoint>): List<DataPoint> {
    if (points.isEmpty()) return points
    val spreads = points.map { it.spreadPercent }
    val mean = spreads.average()
    val variance = spreads
        .map { (it - mean) * (it - mean) }
        .average()
    val stdDev = kotlin.math.sqrt(variance).takeIf { it > 0.0 } ?: 1.0
    return points.map {
        it.copy(zScore = (it.spreadPercent - mean) / stdDev)
    }
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
        val request = Request.Builder().url(url).build()
        val page = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while loading candles for $secId")
            }
            parseCandleBars(response.body?.string().orEmpty())
        }
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
internal suspend fun fetchPortfolio15mSpreadEntities(
    from: LocalDate,
    till: LocalDate
): List<PortfolioM15SpreadEntity> = withContext(Dispatchers.IO) {
    val zone = ZoneId.of("Europe/Moscow")
    val (tatnBars, tatnpBars) = coroutineScope {
        val d1 = async { loadCandleBars("TATN", from, till, interval = 10) }
        val d2 = async { loadCandleBars("TATNP", from, till, interval = 10) }
        Pair(d1.await(), d2.await())
    }
    val tatn15 = aggregateTo15MinuteBars(tatnBars).associateBy { it.timestamp }
    val tatnp15 = aggregateTo15MinuteBars(tatnpBars).associateBy { it.timestamp }
    val times = tatn15.keys.intersect(tatnp15.keys).sorted()
    if (times.isEmpty()) return@withContext emptyList()
    times.mapNotNull { ts ->
        val c1 = tatn15[ts]?.close ?: return@mapNotNull null
        val c2 = tatnp15[ts]?.close ?: return@mapNotNull null
        if (c2 == 0.0) return@mapNotNull null
        val spread = (c1 / c2 - 1.0) * 100.0
        PortfolioM15SpreadEntity(
            tsMillis = ts.atZone(zone).toInstant().toEpochMilli(),
            tatnClose = c1,
            tatnpClose = c2,
            spreadPercent = spread,
            diff = c1 - c2
        )
    }
}

/**
 * 15m portfolio series with local Room cache on device.
 *
 * INCREMENTAL: merge cache with a short tail fetch from MOEX.
 * FULL_REFRESH: wipe cache and download the full from..till window.
 * CACHE_ONLY: read SQLite only (no network).
 */
internal suspend fun loadPortfolio15mDataPoints(
    context: Context,
    from: LocalDate,
    till: LocalDate,
    mode: PortfolioM15LoadMode
): List<DataPoint> = withContext(Dispatchers.IO) {
    val dao = PortfolioM15Database.get(context).dao()
    val zone = ZoneId.of("Europe/Moscow")
    val cutoffMillis = till.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()

    dao.deleteOlderThan(cutoffMillis)

    when (mode) {
        PortfolioM15LoadMode.CACHE_ONLY -> {
            val rows = dao.getAll()
            if (rows.isEmpty()) return@withContext emptyList()
            applyZScores(rows.map { it.toDataPoint() })
        }

        PortfolioM15LoadMode.FULL_REFRESH -> {
            val entities = fetchPortfolio15mSpreadEntities(from, till)
            dao.deleteAll()
            if (entities.isNotEmpty()) {
                dao.insertAll(entities)
            }
            val all = dao.getAll()
            if (all.isEmpty()) return@withContext emptyList()
            applyZScores(all.map { it.toDataPoint() })
        }

        PortfolioM15LoadMode.INCREMENTAL -> {
            val cached = dao.getAll()
            val mergeFrom = if (cached.isEmpty()) {
                from
            } else {
                val lastTs = cached.last().tsMillis
                val lastDay = Instant.ofEpochMilli(lastTs).atZone(zone).toLocalDate()
                val overlapStart = lastDay.minusDays(PORTFOLIO_M15_INCREMENTAL_OVERLAP_DAYS)
                maxOf(from, overlapStart)
            }
            if (mergeFrom <= till) {
                val fresh = fetchPortfolio15mSpreadEntities(mergeFrom, till)
                val byTs = LinkedHashMap<Long, PortfolioM15SpreadEntity>()
                cached.filter { it.tsMillis >= cutoffMillis }.forEach { byTs[it.tsMillis] = it }
                fresh.forEach { byTs[it.tsMillis] = it }
                val merged = byTs.values.sortedBy { it.tsMillis }
                if (merged.isNotEmpty()) {
                    dao.insertAll(merged)
                }
            }
            val all = dao.getAll()
            if (all.isEmpty()) return@withContext emptyList()
            applyZScores(all.map { it.toDataPoint() })
        }
    }
}

internal fun isBeforeDynamicZRecalcWallClock(now: LocalDateTime): Boolean {
    val trigger = LocalTime.of(DYNAMIC_Z_RECALC_HOUR, DYNAMIC_Z_RECALC_MINUTE)
    return now.toLocalTime().isBefore(trigger)
}

internal fun ensureDynamicThresholds(context: Context): DynamicThresholdUpdate {
    val saved = loadSavedDynamicThresholds(context)
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    val todayIso = today.toString()
    val fallback = saved ?: DynamicThresholds(
        entry = DEFAULT_DYNAMIC_Z_ENTRY,
        exit = DEFAULT_DYNAMIC_Z_EXIT,
        calculatedDate = null
    )
    if (saved?.calculatedDate == todayIso) {
        return DynamicThresholdUpdate(thresholds = saved, recalculated = false)
    }
    if (isBeforeDynamicZRecalcWallClock(now)) {
        return DynamicThresholdUpdate(thresholds = fallback, recalculated = false)
    }
    val calculated = runCatching {
        calculateBestDynamicThresholds(
            from = today.minusDays(30),
            till = today
        )
    }.getOrNull() ?: return DynamicThresholdUpdate(thresholds = fallback, recalculated = false)
    saveDynamicThresholds(context, calculated)
    return DynamicThresholdUpdate(thresholds = calculated, recalculated = true)
}

internal fun loadSavedDynamicThresholds(context: Context): DynamicThresholds? {
    val prefs = context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.contains(PREF_DYNAMIC_Z_ENTRY) || !prefs.contains(PREF_DYNAMIC_Z_EXIT)) {
        return null
    }
    val entry = prefs.getFloat(PREF_DYNAMIC_Z_ENTRY, DEFAULT_DYNAMIC_Z_ENTRY.toFloat()).toDouble()
    val exit = prefs.getFloat(PREF_DYNAMIC_Z_EXIT, DEFAULT_DYNAMIC_Z_EXIT.toFloat()).toDouble()
    val date = prefs.getString(PREF_DYNAMIC_Z_DATE, null)
    if (entry <= 0.0 || exit < 0.0 || exit >= entry) {
        return null
    }
    return DynamicThresholds(
        entry = entry,
        exit = exit,
        calculatedDate = date
    )
}

internal fun saveDynamicThresholds(context: Context, thresholds: DynamicThresholds) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_DYNAMIC_Z_ENTRY, thresholds.entry.toFloat())
        .putFloat(PREF_DYNAMIC_Z_EXIT, thresholds.exit.toFloat())
        .putString(PREF_DYNAMIC_Z_DATE, thresholds.calculatedDate)
        .apply()
}

internal fun loadSavedStrategyPosition(context: Context): ZStrategyPosition {
    val raw = context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PREF_Z_STRATEGY_POSITION, ZStrategyPosition.Flat.name)
    return runCatching { ZStrategyPosition.valueOf(raw ?: ZStrategyPosition.Flat.name) }
        .getOrDefault(ZStrategyPosition.Flat)
}

internal fun saveStrategyPosition(context: Context, position: ZStrategyPosition) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_Z_STRATEGY_POSITION, position.name)
        .apply()
}

/** Пороги входа/выхода |Z| с вкладки портфеля (степперы). Если не сохранены — [fallback]. */
internal fun loadPortfolioZThresholds(context: Context, fallback: DynamicThresholds): DynamicThresholds {
    val prefs = context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.contains(PREF_PORTFOLIO_Z_ENTRY_THRESHOLD) ||
        !prefs.contains(PREF_PORTFOLIO_Z_EXIT_THRESHOLD)
    ) {
        return fallback
    }
    val entry = prefs.getFloat(PREF_PORTFOLIO_Z_ENTRY_THRESHOLD, fallback.entry.toFloat())
        .toDouble()
        .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    val exit = prefs.getFloat(PREF_PORTFOLIO_Z_EXIT_THRESHOLD, fallback.exit.toFloat())
        .toDouble()
        .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    if (entry <= 0.0 || exit < 0.0 || exit >= entry) return fallback
    return DynamicThresholds(
        entry = entry,
        exit = exit,
        calculatedDate = fallback.calculatedDate
    )
}

internal fun savePortfolioZThresholds(context: Context, entry: Double, exit: Double) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_PORTFOLIO_Z_ENTRY_THRESHOLD, entry.toFloat())
        .putFloat(PREF_PORTFOLIO_Z_EXIT_THRESHOLD, exit.toFloat())
        .apply()
}

/** Сигнал на последнем 15м баре (пересечение prev→last), те же правила что [buildZStrategyPortfolioMetrics]. */
internal fun zStrategySignalOnLast15mBar(
    points: List<DataPoint>,
    position: ZStrategyPosition,
    thresholds: DynamicThresholds
): ZStrategySignal {
    if (points.size < 2) return ZStrategySignal.None
    val prev = points[points.size - 2]
    val current = points.last()
    return determineZStrategySignal(prev.zScore, current.zScore, position, thresholds)
}

private val consumed15mSignalEdgeLock = Any()

/**
 * Не обрабатывать один и тот же пересечение на одном 15м баре повторно
 * (опрос каждые 5–15 с, UI + фон, сброс позиции из симуляции).
 */
internal fun tryConsume15mStrategySignalEdge(
    context: Context,
    barTimestampMillis: Long,
    signal: ZStrategySignal
): Boolean {
    if (signal == ZStrategySignal.None) return false
    val edgeKey = "$barTimestampMillis|${signal.name}"
    synchronized(consumed15mSignalEdgeLock) {
        val prefs = context.applicationContext.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(PREF_LAST_CONSUMED_15M_SIGNAL_EDGE, null) == edgeKey) {
            return false
        }
        prefs.edit().putString(PREF_LAST_CONSUMED_15M_SIGNAL_EDGE, edgeKey).commit()
        return true
    }
}

internal fun clearConsumed15mStrategySignalEdge(context: Context) {
    synchronized(consumed15mSignalEdgeLock) {
        context.applicationContext.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_LAST_CONSUMED_15M_SIGNAL_EDGE)
            .apply()
    }
}

internal fun loadDailySignalLimit(context: Context, day: LocalDate): DailySignalLimit {
    val prefs = context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
    val savedDay = prefs.getString(PREF_Z_DAILY_SIGNAL_DATE, null)
    val dayText = day.toString()
    if (savedDay != dayText) {
        return DailySignalLimit(date = dayText, sentCount = 0)
    }
    val legacyCount = listOf(
        prefs.getBoolean(PREF_Z_DAILY_SIGNAL_ENTRY, false),
        prefs.getBoolean(PREF_Z_DAILY_SIGNAL_EXIT, false)
    ).count { it }
    return DailySignalLimit(
        date = dayText,
        sentCount = prefs.getInt(PREF_Z_DAILY_SIGNAL_COUNT, legacyCount)
    )
}

internal fun saveDailySignalLimit(context: Context, limit: DailySignalLimit) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_Z_DAILY_SIGNAL_DATE, limit.date)
        .putInt(PREF_Z_DAILY_SIGNAL_COUNT, limit.sentCount)
        .remove(PREF_Z_DAILY_SIGNAL_ENTRY)
        .remove(PREF_Z_DAILY_SIGNAL_EXIT)
        .apply()
}

internal fun calculateBestDynamicThresholds(from: LocalDate, till: LocalDate): DynamicThresholds? {
    val tatnBars = aggregateTo15MinuteBars(
        loadCandleBars("TATN", from, till, interval = 1)
    ).associateBy { it.timestamp }
    val tatnpBars = aggregateTo15MinuteBars(
        loadCandleBars("TATNP", from, till, interval = 1)
    ).associateBy { it.timestamp }
    val alignedTimes = tatnBars.keys.intersect(tatnpBars.keys).sorted()
    if (alignedTimes.size < 20) return null

    val spreads = alignedTimes.mapNotNull { timestamp ->
        val tatnClose = tatnBars[timestamp]?.close ?: return@mapNotNull null
        val tatnpClose = tatnpBars[timestamp]?.close ?: return@mapNotNull null
        if (tatnpClose == 0.0) return@mapNotNull null
        (tatnClose / tatnpClose - 1.0) * 100.0
    }
    if (spreads.size < 20) return null

    val mean = spreads.average()
    val variance = spreads
        .map { (it - mean) * (it - mean) }
        .average()
    val stdDev = kotlin.math.sqrt(variance).takeIf { it > 0.0 } ?: 1.0
    val points = spreads.map { spread ->
        BacktestPoint(
            spread = spread,
            z = (spread - mean) / stdDev
        )
    }
    if (points.size < 20) return null

    var best: BacktestResult? = null
    for (entryTenths in Z_STRATEGY_ENTRY_MIN_TENTHS..Z_STRATEGY_ENTRY_MAX_TENTHS) {
        val entry = entryTenths / 10.0
        for (exitTenths in Z_STRATEGY_EXIT_MIN_TENTHS..Z_STRATEGY_EXIT_MAX_TENTHS) {
            val exit = exitTenths / 10.0
            if (exit >= entry) continue
            val result = backtestZStrategy(points, entry, exit)
            if (result.trades < Z_STRATEGY_MIN_TRADES) continue
            if (best == null || result.pnl > best.pnl) {
                best = result
            }
        }
    }

    val winner = best ?: return null
    return DynamicThresholds(
        entry = winner.entry,
        exit = winner.exit,
        calculatedDate = till.toString()
    )
}

internal fun backtestZStrategy(points: List<BacktestPoint>, entry: Double, exit: Double): BacktestResult {
    var position = ZStrategyPosition.Flat
    var entrySpread = 0.0
    var pnl = 0.0
    var trades = 0

    for (index in 1 until points.size) {
        val prev = points[index - 1]
        val current = points[index]
        when (position) {
            ZStrategyPosition.Long -> {
                if (prev.z < -exit && current.z >= -exit) {
                    pnl += current.spread - entrySpread
                    position = ZStrategyPosition.Flat
                    trades += 1
                }
            }

            ZStrategyPosition.Short -> {
                if (prev.z > exit && current.z <= exit) {
                    pnl += entrySpread - current.spread
                    position = ZStrategyPosition.Flat
                    trades += 1
                }
            }

            ZStrategyPosition.Flat -> Unit
        }
        if (position == ZStrategyPosition.Flat) {
            if (prev.z > -entry && current.z <= -entry) {
                position = ZStrategyPosition.Long
                entrySpread = current.spread
            } else if (prev.z < entry && current.z >= entry) {
                position = ZStrategyPosition.Short
                entrySpread = current.spread
            }
        }
    }

    if (position != ZStrategyPosition.Flat) {
        val lastSpread = points.last().spread
        pnl += if (position == ZStrategyPosition.Long) {
            lastSpread - entrySpread
        } else {
            entrySpread - lastSpread
        }
        trades += 1
    }

    return BacktestResult(
        pnl = pnl,
        trades = trades,
        entry = entry,
        exit = exit
    )
}

internal fun determineZStrategySignal(
    previousZ: Double?,
    currentZ: Double,
    position: ZStrategyPosition,
    thresholds: DynamicThresholds
): ZStrategySignal {
    val prev = previousZ ?: return ZStrategySignal.None
    return when (position) {
        ZStrategyPosition.Flat -> {
            when {
                prev > -thresholds.entry && currentZ <= -thresholds.entry -> ZStrategySignal.EnterLong
                prev < thresholds.entry && currentZ >= thresholds.entry -> ZStrategySignal.EnterShort
                else -> ZStrategySignal.None
            }
        }

        ZStrategyPosition.Long -> {
            if (prev < -thresholds.exit && currentZ >= -thresholds.exit) {
                ZStrategySignal.ExitLong
            } else {
                ZStrategySignal.None
            }
        }

        ZStrategyPosition.Short -> {
            if (prev > thresholds.exit && currentZ <= thresholds.exit) {
                ZStrategySignal.ExitShort
            } else {
                ZStrategySignal.None
            }
        }
    }
}
