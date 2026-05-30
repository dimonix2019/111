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

/** μ/σ только за последние [lookbackDays] календарных дней (MSK, без look-ahead). Parity с strategy-web/zsim.py. */
internal fun applyZScoresRolling(
    points: List<DataPoint>,
    lookbackDays: Int = Z_SCORE_ROLLING_LOOKBACK_DAYS,
    minBarsInWindow: Int = Z_SCORE_ROLLING_MIN_BARS,
): List<DataPoint> {
    if (points.isEmpty()) return points
    if (lookbackDays <= 0) return applyZScores(points)

    val spreads = points.map { it.spreadPercent }
    val millisList = points.map { barMillisAt(it) }
    val n = points.size
    val minBars = maxOf(minBarsInWindow, 2)
    val zScores = DoubleArray(n)
    var windowStart = 0

    for (i in 0 until n) {
        val barMillis = millisList[i]
        val dt = Instant.ofEpochMilli(barMillis).atZone(moexZoneId)
        val fromMillis = dt.toLocalDate()
            .minusDays(lookbackDays.toLong())
            .atStartOfDay(moexZoneId)
            .toInstant()
            .toEpochMilli()

        while (windowStart < i && millisList[windowStart] < fromMillis) {
            windowStart++
        }

        var count = 0
        var total = 0.0
        var totalSq = 0.0
        for (j in windowStart..i) {
            val s = spreads[j]
            total += s
            totalSq += s * s
            count++
        }

        zScores[i] = if (count < minBars) {
            0.0
        } else {
            val mean = total / count
            val variance = (totalSq / count) - (mean * mean)
            var std = kotlin.math.sqrt(maxOf(variance, 0.0))
            if (std <= 1e-12) std = 1.0
            (spreads[i] - mean) / std
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

private fun barMillisAt(point: DataPoint): Long {
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
