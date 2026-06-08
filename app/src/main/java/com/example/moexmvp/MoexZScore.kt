package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

internal enum class ZScoreMode {
    Global,
    Rolling30,
}

/** Z по всему ряду (legacy global — look-ahead на истории). */
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

/** μ/σ rolling-окна на индексе [index] (parity с [applyZScoresRolling] для close бара). */
internal data class RollingSpreadStats(val mean: Double, val stdDev: Double)

/** Кэш spread/millis для серии — без O(n) аллокаций на каждый бар (3M/6M/1Y в landscape). */
internal class RollingSpreadStatsCache private constructor(
    private val spreads: DoubleArray,
    private val millisList: LongArray,
    private val lookbackDays: Int,
    private val minBarsInWindow: Int,
) {
    fun at(index: Int): RollingSpreadStats? =
        rollingSpreadStatsAtArrays(spreads, millisList, index, lookbackDays, minBarsInWindow)

    companion object {
        fun from(
            points: List<DataPoint>,
            lookbackDays: Int = Z_SCORE_ROLLING_LOOKBACK_DAYS,
            minBarsInWindow: Int = Z_SCORE_ROLLING_MIN_BARS,
        ): RollingSpreadStatsCache {
            val n = points.size
            return RollingSpreadStatsCache(
                spreads = DoubleArray(n) { points[it].spreadPercent },
                millisList = LongArray(n) { barMillisAt(points[it]) },
                lookbackDays = lookbackDays,
                minBarsInWindow = minBarsInWindow,
            )
        }
    }
}

private fun rollingSpreadStatsAtArrays(
    spreads: DoubleArray,
    millisList: LongArray,
    index: Int,
    lookbackDays: Int,
    minBarsInWindow: Int,
): RollingSpreadStats? {
    if (spreads.isEmpty() || index !in spreads.indices) return null
    if (lookbackDays <= 0) {
        val mean = spreads.average()
        val variance = spreads.map { (it - mean) * (it - mean) }.average()
        val std = kotlin.math.sqrt(variance).takeIf { it > 0.0 } ?: 1.0
        return RollingSpreadStats(mean, std)
    }

    val minBars = maxOf(minBarsInWindow, 2)
    val barMillis = millisList[index]
    val fromMillis = Instant.ofEpochMilli(barMillis).atZone(moexZoneId)
        .toLocalDate()
        .minusDays(lookbackDays.toLong())
        .atStartOfDay(moexZoneId)
        .toInstant()
        .toEpochMilli()

    var windowStart = 0
    while (windowStart < index && millisList[windowStart] < fromMillis) {
        windowStart++
    }

    var count = 0
    var total = 0.0
    var totalSq = 0.0
    for (j in windowStart..index) {
        val s = spreads[j]
        total += s
        totalSq += s * s
        count++
    }
    if (count < minBars) return null

    val mean = total / count
    val variance = (totalSq / count) - (mean * mean)
    var std = kotlin.math.sqrt(maxOf(variance, 0.0))
    if (std <= 1e-12) std = 1.0
    return RollingSpreadStats(mean, std)
}

internal fun rollingSpreadStatsAt(
    points: List<DataPoint>,
    index: Int,
    lookbackDays: Int = Z_SCORE_ROLLING_LOOKBACK_DAYS,
    minBarsInWindow: Int = Z_SCORE_ROLLING_MIN_BARS,
): RollingSpreadStats? {
    if (points.isEmpty() || index !in points.indices) return null
    return rollingSpreadStatsAtArrays(
        spreads = DoubleArray(points.size) { points[it].spreadPercent },
        millisList = LongArray(points.size) { barMillisAt(points[it]) },
        index = index,
        lookbackDays = lookbackDays,
        minBarsInWindow = minBarsInWindow,
    )
}

internal fun zScoreAtSpread(spread: Double, stats: RollingSpreadStats): Double =
    (spread - stats.mean) / stats.stdDev

/** μ/σ только за последние [lookbackDays] календарных дней (MSK, без look-ahead). Parity с strategy-web/zsim.py. */
internal fun applyZScoresRolling(
    points: List<DataPoint>,
    lookbackDays: Int = Z_SCORE_ROLLING_LOOKBACK_DAYS,
    minBarsInWindow: Int = Z_SCORE_ROLLING_MIN_BARS,
): List<DataPoint> {
    if (points.isEmpty()) return points
    if (lookbackDays <= 0) return applyZScores(points)

    val spreads = points.map { it.spreadPercent }
    val n = points.size
    val zScores = DoubleArray(n)

    for (i in 0 until n) {
        val stats = rollingSpreadStatsAt(points, i, lookbackDays, minBarsInWindow)
        zScores[i] = if (stats == null) {
            0.0
        } else {
            zScoreAtSpread(spreads[i], stats)
        }
    }

    return points.mapIndexed { idx, pt -> pt.copy(zScore = zScores[idx]) }
}

internal fun applyZScoresForMode(points: List<DataPoint>, mode: ZScoreMode): List<DataPoint> =
    when (mode) {
        ZScoreMode.Global -> applyZScores(points)
        ZScoreMode.Rolling30 -> applyZScoresRolling(points)
    }

/** Default Z mode — rolling 30d (parity с strategy-web). */
internal fun applyZScoresDefault(points: List<DataPoint>): List<DataPoint> =
    applyZScoresForMode(points, ZScoreMode.Rolling30)

/** Как [applyZScoresDefault], но без второго полного списка (меньше пиков RAM на 255д). */
internal fun applyZScoresDefaultInPlace(points: MutableList<DataPoint>) {
    if (points.size < 2) return
    val cache = RollingSpreadStatsCache.from(points)
    for (i in points.indices) {
        val stats = cache.at(i) ?: continue
        val z = zScoreAtSpread(points[i].spreadPercent, stats)
        points[i] = points[i].copy(zScore = z)
    }
}

internal fun barMillisAt(point: DataPoint): Long {
    if (point.timestampMillis > 0L) return point.timestampMillis
    return parseTradeDateMillis(point.tradeDate)
}

private fun parseTradeDateMillis(tradeDate: String): Long {
    val s = tradeDate.trim()
    runCatching {
        LocalDateTime.parse(s, portfolio15mLabelFormatter)
            .atZone(moexZoneId)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()?.let { return it }
    runCatching {
        LocalDate.parse(s.take(10), tradeDateFormatter)
            .atStartOfDay(moexZoneId)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()?.let { return it }
    return 0L
}
