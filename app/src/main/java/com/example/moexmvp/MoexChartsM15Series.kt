package com.example.moexmvp

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun downsampleDataPointsForChart(
    points: List<DataPoint>,
    maxBars: Int = CHART_MAX_DISPLAY_BARS
): List<DataPoint> {
    if (points.size <= maxBars) return points
    val last = points.last()
    val step = points.size.toDouble() / maxBars
    val out = ArrayList<DataPoint>(maxBars + 1)
    var i = 0.0
    while (out.size < maxBars && i < points.size) {
        out += points[i.toInt().coerceIn(0, points.lastIndex)]
        i += step
    }
    if (out.isEmpty() || out.last() != last) out += last
    return out
}

/** Spread OHLC внутри 15м слота (из 10м close-спредов). */
internal data class SpreadOhlc(val open: Double, val high: Double, val low: Double, val close: Double)

internal fun buildSpreadOhlcBy15mBucket(spread10: List<DataPoint>): Map<Long, SpreadOhlc> {
    if (spread10.isEmpty()) return emptyMap()
    val grouped = linkedMapOf<Long, MutableList<Double>>()
    spread10.forEach { point ->
        val bucket = floor15mMillis(point.timestampMillis)
        grouped.getOrPut(bucket) { mutableListOf() }.add(point.spreadPercent)
    }
    return grouped.mapValues { (_, spreads) ->
        SpreadOhlc(
            open = spreads.first(),
            high = spreads.max(),
            low = spreads.min(),
            close = spreads.last()
        )
    }
}

/**
 * Z-свечи 15м: open/close = rolling Z между барами (как стратегия);
 * high/low расширяются spread OHLC слота (фитили внутри 15м).
 */
internal fun buildZScoreCandlesFrom15mSpreadOhlc(
    m15Points: List<DataPoint>,
    spreadOhlcByBucket: Map<Long, SpreadOhlc>,
): List<CandlePoint> {
    if (m15Points.isEmpty()) return emptyList()
    return m15Points.mapIndexed { index, point ->
        val open = if (index == 0) point.zScore else m15Points[index - 1].zScore
        val close = point.zScore
        val bodyHigh = max(open, close)
        val bodyLow = min(open, close)
        val bucket = floor15mMillis(point.timestampMillis)
        val ohlc = spreadOhlcByBucket[bucket]
        val stats = rollingSpreadStatsAt(m15Points, index)
        val wickRange = if (ohlc != null && stats != null) {
            listOf(
                zScoreAtSpread(ohlc.open, stats),
                zScoreAtSpread(ohlc.high, stats),
                zScoreAtSpread(ohlc.low, stats),
                open,
                close,
            ).let { it.min() to it.max() }
        } else {
            null
        }
        CandlePoint(
            label = point.tradeDate,
            open = open,
            high = wickRange?.second?.let { max(bodyHigh, it) } ?: bodyHigh,
            low = wickRange?.first?.let { min(bodyLow, it) } ?: bodyLow,
            close = close,
        )
    }
}

/** Свечи строятся на полном 15м ряду, затем прореживаются с сохранением OHLC. */
internal fun downsampleM15ChartSeries(
    points: List<DataPoint>,
    candles: List<CandlePoint>,
    maxBars: Int = CHART_MAX_DISPLAY_BARS,
): Pair<List<DataPoint>, List<CandlePoint>> {
    require(points.size == candles.size) {
        "points.size=${points.size} != candles.size=${candles.size}"
    }
    if (points.size <= maxBars) return points to candles
    val n = points.size
    val bucketSize = n.toDouble() / maxBars
    val outPoints = ArrayList<DataPoint>(maxBars + 1)
    val outCandles = ArrayList<CandlePoint>(maxBars + 1)
    var start = 0
    while (start < n && outPoints.size < maxBars) {
        val endIdx = max(start, min(((start + bucketSize).toInt()), n) - 1)
        val pSlice = points.subList(start, endIdx + 1)
        val cSlice = candles.subList(start, endIdx + 1)
        outPoints += pSlice.last()
        outCandles += CandlePoint(
            label = pSlice.last().tradeDate,
            open = cSlice.first().open,
            high = cSlice.maxOf { it.high },
            low = cSlice.minOf { it.low },
            close = cSlice.last().close
        )
        start = endIdx + 1
    }
    if (outPoints.isEmpty() || outPoints.last() != points.last()) {
        outPoints += points.last()
        outCandles += candles.last()
    }
    return outPoints to outCandles
}

/** Быстрый ряд для UI: close-свечи на полном ряду → downsample. */
internal fun buildM15ZChartDisplay(
    simPoints: List<DataPoint>,
): Pair<List<DataPoint>, List<CandlePoint>> {
    if (simPoints.isEmpty()) return emptyList<DataPoint>() to emptyList<CandlePoint>()
    val fullCandles = buildZScoreCandlesFromM15Points(simPoints)
    return downsampleM15ChartSeries(simPoints, fullCandles)
}

internal suspend fun loadSpreadOhlcForM15Range(
    m15Points: List<DataPoint>,
    lookbackDays: Long = CHART_INTRABAR_OHLC_LOOKBACK_DAYS,
): Map<Long, SpreadOhlc> =
    withContext(Dispatchers.IO) {
        if (m15Points.isEmpty()) return@withContext emptyMap<Long, SpreadOhlc>()
        val zone = moexZoneId
        val lastDay = Instant.ofEpochMilli(m15Points.last().timestampMillis).atZone(zone).toLocalDate()
        val seriesFirstDay = Instant.ofEpochMilli(m15Points.first().timestampMillis).atZone(zone).toLocalDate()
        val firstDay = maxOf(seriesFirstDay, lastDay.minusDays(lookbackDays.coerceAtLeast(1L)))
        val spread10 = buildSpreadDataPointsFrom10mBars(
            loadCandleBars("TATN", firstDay, lastDay.plusDays(1), interval = 10),
            loadCandleBars("TATNP", firstDay, lastDay.plusDays(1), interval = 10),
        )
        buildSpreadOhlcBy15mBucket(spread10)
    }

/** 15м Z-свечи с spread OHLC из 10м (фитили внутри слота). */
internal suspend fun buildM15ZChartDisplayWithSpreadOhlc(
    simPoints: List<DataPoint>,
): Pair<List<DataPoint>, List<CandlePoint>> {
    if (simPoints.isEmpty()) return emptyList<DataPoint>() to emptyList<CandlePoint>()
    val fullCandles = buildZScoreCandlesOhlcAnchoredToM15Series(simPoints)
    return downsampleM15ChartSeries(simPoints, fullCandles)
}

internal fun visibleCandleDrawIndexRange(
    maxIndex: Int,
    windowStart: Float,
    windowWidth: Float,
    paddingFrac: Float = 0.03f
): IntRange {
    if (maxIndex <= 0) return 0..0
    val lo = (windowStart - paddingFrac).coerceIn(0f, 1f)
    val hi = (windowStart + windowWidth + paddingFrac).coerceIn(0f, 1f)
    val start = (lo * maxIndex).toInt().coerceIn(0, maxIndex)
    val end = (hi * maxIndex).toInt().coerceIn(0, maxIndex)
    return start..end
}

internal fun filterM15PointsForMarketsPeriod(
    points: List<DataPoint>,
    period: Period
): List<DataPoint> {
    if (points.isEmpty()) return emptyList()
    val latestDate = java.time.Instant.ofEpochMilli(points.last().timestampMillis)
        .atZone(moexZoneId)
        .toLocalDate()
    val fromMillis = period.from(latestDate)
        .atStartOfDay(moexZoneId)
        .toInstant()
        .toEpochMilli()
    return points.filter { it.timestampMillis >= fromMillis }
}

