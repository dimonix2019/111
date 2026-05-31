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

internal fun buildZScoreReferenceLines(
    thresholds: DynamicThresholds,
    desktopStyle: Boolean = false,
): List<ChartReferenceLine> {
    val entryColor = if (desktopStyle) DesktopChartColors.entryThreshold else Color(0xFFFFB74D)
    val exitColor = if (desktopStyle) DesktopChartColors.exitThreshold else Color(0xFFA5D6A7)
    return listOf(
        ChartReferenceLine(
            value = thresholds.entry,
            color = entryColor,
            label = String.format(Locale.US, "+%.2f", thresholds.entry)
        ),
        ChartReferenceLine(
            value = -thresholds.entry,
            color = entryColor,
            label = String.format(Locale.US, "-%.2f", thresholds.entry)
        ),
        ChartReferenceLine(
            value = thresholds.exit,
            color = exitColor,
            label = String.format(Locale.US, "+%.2f", thresholds.exit)
        ),
        ChartReferenceLine(
            value = -thresholds.exit,
            color = exitColor,
            label = String.format(Locale.US, "-%.2f", thresholds.exit)
        )
    )
}

internal fun buildZScoreSignalMarkersFromEvents(
    points: List<DataPoint>,
    events: List<StrategySignalEvent>
): List<ChartPointMarker> {
    if (points.isEmpty()) return emptyList()
    val rangeStart = points.first().timestampMillis
    val rangeEnd = points.last().timestampMillis
    val bucketMs = 15 * 60 * 1000L
    /** Allow snapping across small clock skew (chart vs 15m cache both use Moscow). */
    val maxSnapMs = bucketMs * 2
    /** Events slightly after the last bar (e.g. confirm tap) still map to the last 15m bar. */
    val edgeGraceMs = 48 * 60 * 60 * 1000L
    return events
        .asSequence()
        .filter { it.timestampMillis in (rangeStart - bucketMs - edgeGraceMs)..(rangeEnd + bucketMs + edgeGraceMs) }
        .distinctBy { "${it.signalType}:${it.timestampMillis}" }
        .mapNotNull { event ->
            val idxNearest = points.indices.minByOrNull { index ->
                abs(points[index].timestampMillis - event.timestampMillis)
            } ?: return@mapNotNull null
            val diffNearest = abs(points[idxNearest].timestampMillis - event.timestampMillis)
            val idx = when {
                diffNearest <= maxSnapMs -> idxNearest
                event.timestampMillis > rangeEnd && event.timestampMillis <= rangeEnd + edgeGraceMs ->
                    points.lastIndex
                event.timestampMillis < rangeStart && event.timestampMillis >= rangeStart - edgeGraceMs ->
                    0
                else -> return@mapNotNull null
            }
            val (color, label, shape) = when (event.signalType) {
                StrategySignalType.EnterLong -> Triple(Color(0xFF69F0AE), "Enter LONG", ChartMarkerShape.TriangleUp)
                StrategySignalType.EnterShort -> Triple(Color(0xFFFF8A80), "Enter SHORT", ChartMarkerShape.TriangleDown)
                StrategySignalType.ExitLong -> Triple(Color(0xFF90CAF9), "Exit LONG", ChartMarkerShape.Diamond)
                StrategySignalType.ExitShort -> Triple(Color(0xFFFFCC80), "Exit SHORT", ChartMarkerShape.Diamond)
            }
            ChartPointMarker(
                index = idx,
                value = points[idx].zScore,
                color = color,
                label = label,
                shape = shape
            )
        }
        .toList()
}

/** Начальное окно графика: виден хвост последних [visibleDays] календарных дней. */
internal fun chartInitialWindowForLastCalendarDays(
    points: List<DataPoint>,
    visibleDays: Long = STRATEGY_TEST_Z_CHART_VISIBLE_DAYS
): Pair<Float, Float> {
    if (points.size < 2) return 1f to 0f
    val till = points.last().timestampMillis
    val from = java.time.Instant.ofEpochMilli(till)
        .atZone(moexZoneId)
        .toLocalDate()
        .minusDays(visibleDays)
        .atStartOfDay(moexZoneId)
        .toInstant()
        .toEpochMilli()
    val firstVisibleIdx = points.indexOfFirst { it.timestampMillis >= from }.coerceAtLeast(0)
    val maxIndex = points.lastIndex.coerceAtLeast(1)
    val width = ((points.size - firstVisibleIdx).toFloat() / points.size)
        .coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
    val start = (firstVisibleIdx.toFloat() / maxIndex).coerceIn(0f, 1f - width)
    return width to start
}

internal fun indexForTradeDateLabel(points: List<DataPoint>, label: String): Int? {
    if (points.isEmpty()) return null
    val exact = points.indexOfFirst { it.tradeDate == label }
    if (exact >= 0) return exact
    val targetTs = runCatching {
        LocalDateTime.parse(label, portfolio15mLabelFormatter)
            .atZone(moexZoneId)
            .toInstant()
            .toEpochMilli()
    }.getOrNull() ?: return null
    val idx = points.indices.minByOrNull { kotlin.math.abs(points[it].timestampMillis - targetTs) }
        ?: return null
    val diff = kotlin.math.abs(points[idx].timestampMillis - targetTs)
    return if (diff <= 15 * 60 * 1000L * 2) idx else null
}

/** Маркеры входа/выхода симуляции «Тест страт.»; [badgeText] = номер как в списке (#1 — свежая). */
internal fun buildZScoreMarkersFromStrategyTestTrades(
    points: List<DataPoint>,
    tradeItems: List<StrategyTestTradeItem>
): List<ChartPointMarker> {
    if (points.isEmpty() || tradeItems.isEmpty()) return emptyList()
    val markers = mutableListOf<ChartPointMarker>()
    tradeItems.forEachIndexed { listIndex, item ->
        val num = "#${listIndex + 1}"
        val t = item.trade
        val (enterShape, enterColor) = when (t.direction) {
            ZStrategyPosition.Long -> ChartMarkerShape.TriangleUp to Color(0xFF69F0AE)
            ZStrategyPosition.Short -> ChartMarkerShape.TriangleDown to Color(0xFFFF8A80)
            ZStrategyPosition.Flat -> ChartMarkerShape.Circle to Color(0xFFB0BEC5)
        }
        indexForTradeDateLabel(points, t.entryDate)?.let { idx ->
            markers += ChartPointMarker(
                index = idx,
                value = points[idx].zScore,
                color = enterColor,
                label = "Вх #$num",
                shape = enterShape,
                badgeText = num
            )
        }
        indexForTradeDateLabel(points, t.exitDate)?.let { idx ->
            markers += ChartPointMarker(
                index = idx,
                value = points[idx].zScore,
                color = Color(0xFFFFCC80),
                label = "Вых #$num",
                shape = ChartMarkerShape.Diamond,
                badgeText = num
            )
        }
    }
    return markers
}

/** Равномерный прореживание ряда для отрисовки (симуляция — на полном ряду). */
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

internal fun candleChartDataYRange(
    candles: List<CandlePoint>,
    valueHints: List<Double> = emptyList()
): Pair<Double, Double> {
    val lows = candles.map { it.low }
    val highs = candles.map { it.high }
    val min = (lows + valueHints).minOrNull() ?: 0.0
    val max = (highs + valueHints).maxOrNull() ?: 1.0
    return if (max <= min) min to (min + 1.0) else min to max
}

internal fun candleDefaultYViewCenter(dataMin: Double, dataMax: Double): Double =
    (dataMin + dataMax) / 2.0

/** Ширина тела свечи растёт при pinch-zoom по X (без жёсткого потолка 14px). */
internal fun chartCandleBodyWidthPx(plotWidthPx: Float, visibleCandleCount: Float): Float {
    val count = visibleCandleCount.coerceAtLeast(1f)
    val slot = plotWidthPx / count
    return (slot * 0.72f).coerceIn(2f, slot * 0.92f)
}

/** Видимый диапазон Y; viewCenter может быть вне dataMin..dataMax (как zoom фото в галерее). */
internal fun visibleCandleYRange(
    dataMin: Double,
    dataMax: Double,
    yZoom: Float,
    viewCenter: Double
): Pair<Double, Double> {
    val fullSpan = (dataMax - dataMin).coerceAtLeast(1e-9)
    val visSpan = fullSpan / yZoom.coerceIn(1f, CHART_Y_ZOOM_MAX)
    return (viewCenter - visSpan / 2.0) to (viewCenter + visSpan / 2.0)
}

/** Pinch-zoom по Y вокруг точки касания (centroidYRel: 0 — верх графика, 1 — низ). */
internal fun yViewAfterPinchZoom(
    dataMin: Double,
    dataMax: Double,
    yZoom: Float,
    viewCenter: Double,
    pinchZoom: Float,
    centroidYRel: Float
): Pair<Float, Double> {
    val fullSpan = (dataMax - dataMin).coerceAtLeast(1e-9)
    val curSpan = fullSpan / yZoom.coerceIn(1f, CHART_Y_ZOOM_MAX)
    val curMin = viewCenter - curSpan / 2.0
    val curMax = viewCenter + curSpan / 2.0
    val rel = centroidYRel.coerceIn(0f, 1f)
    val yAtCentroid = curMax - rel * (curMax - curMin)
    val newZoom = (yZoom * pinchZoom).coerceIn(1f, CHART_Y_ZOOM_MAX)
    val newSpan = fullSpan / newZoom
    val newCenter = yAtCentroid + (rel - 0.5f) * newSpan
    return newZoom to newCenter
}

/** Правый зазор области графика: max(minPx, plotWidthPx × fraction). */
internal fun chartRightPlotPaddingPx(
    plotWidthPx: Float,
    minPx: Float = 16f,
    fraction: Float = CHART_RIGHT_PLOT_PADDING_FRACTION
): Float {
    if (plotWidthPx <= 0f || fraction <= 0f) return minPx.coerceAtLeast(0f)
    return max(minPx, plotWidthPx * fraction)
}

/**
 * Свечи Z-score строятся из того же 15м close-ряда, который идёт в стратегию:
 * close = Z бара, open = Z предыдущего бара, high/low = границы тела.
 */
internal fun buildZScoreCandlesFromM15Points(points: List<DataPoint>): List<CandlePoint> {
    return points.mapIndexed { index, point ->
        val close = point.zScore
        val open = points.getOrNull(index - 1)?.zScore ?: close
        CandlePoint(
            label = point.tradeDate,
            open = open,
            high = max(open, close),
            low = min(open, close),
            close = close
        )
    }
}

/** 10м spread-точки по пересечению TATN/TATNP (для Z OHLC внутри 15м). */
internal fun buildSpreadDataPointsFrom10mBars(
    tatn10: List<CandleBar>,
    tatnp10: List<CandleBar>,
): List<DataPoint> {
    val tatnByTime = tatn10.associateBy { it.timestamp }
    val tatnpByTime = tatnp10.associateBy { it.timestamp }
    val times = tatnByTime.keys.intersect(tatnpByTime.keys).sorted()
    return times.mapNotNull { ts ->
        val c1 = tatnByTime[ts]?.close ?: return@mapNotNull null
        val c2 = tatnpByTime[ts]?.close ?: return@mapNotNull null
        if (c2 == 0.0) return@mapNotNull null
        val spread = (c1 / c2 - 1.0) * 100.0
        DataPoint(
            timestampMillis = ts.atZone(moexZoneId).toInstant().toEpochMilli(),
            tradeDate = ts.format(portfolio15mLabelFormatter),
            tatnClose = c1,
            tatnpClose = c2,
            spreadPercent = spread,
            diff = c1 - c2,
            zScore = 0.0,
        )
    }
}

/** Диапазон Z внутри 15м слота (из 10м баров). */
internal data class IntrabarZRange(val min: Double, val max: Double)

internal fun floor15mMillis(tsMillis: Long): Long {
    val dt = Instant.ofEpochMilli(tsMillis).atZone(moexZoneId).toLocalDateTime()
    return dt.withMinute((dt.minute / 15) * 15)
        .withSecond(0)
        .withNano(0)
        .atZone(moexZoneId)
        .toInstant()
        .toEpochMilli()
}

internal fun buildIntrabarZRangesBy15mBucket(z10: List<DataPoint>): Map<Long, IntrabarZRange> {
    if (z10.isEmpty()) return emptyMap()
    val grouped = linkedMapOf<Long, MutableList<Double>>()
    z10.forEach { point ->
        val bucket = floor15mMillis(point.timestampMillis)
        grouped.getOrPut(bucket) { mutableListOf() }.add(point.zScore)
    }
    return grouped.mapNotNull { (bucket, zs) ->
        if (zs.isEmpty()) null else bucket to IntrabarZRange(zs.min(), zs.max())
    }.toMap()
}

/** 10м spread-точки, привязанные к 15м бару по времени (≤ timestamp 15м close). O(n+m). */
internal fun build10mSpreadsByM15Index(
    m15Points: List<DataPoint>,
    spread10: List<DataPoint>,
): List<List<Double>> {
    if (m15Points.isEmpty()) return emptyList()
    val m15Millis = m15Points.map { barMillisAt(it) }
    val buckets = List(m15Points.size) { mutableListOf<Double>() }
    var m15Idx = 0
    spread10.forEach { pt ->
        val ts = barMillisAt(pt)
        while (m15Idx + 1 < m15Millis.size && m15Millis[m15Idx + 1] <= ts) {
            m15Idx++
        }
        if (ts >= m15Millis[m15Idx]) {
            buckets[m15Idx].add(pt.spreadPercent)
        }
    }
    return buckets
}

/** Z-диапазон внутри 15м слота: 10м spread через μ/σ того же rolling-окна, что close 15м бара. */
internal fun intrabarZRangeForM15Bar(
    spreads10mInBar: List<Double>,
    m15Stats: RollingSpreadStats?,
): IntrabarZRange? {
    if (m15Stats == null || spreads10mInBar.isEmpty()) return null
    val zs = spreads10mInBar.map { zScoreAtSpread(it, m15Stats) }
    return IntrabarZRange(zs.min(), zs.max())
}

/**
 * 15м Z-свечи OHLC без разрывов: open/close = тот же 15м rolling Z, что стратегия;
 * high/low расширяются 10м Z внутри слота (фитили).
 */
internal suspend fun buildZScoreCandlesOhlcAnchoredToM15Series(
    m15Points: List<DataPoint>,
    intrabarLookbackDays: Long = CHART_INTRABAR_OHLC_LOOKBACK_DAYS,
): List<CandlePoint> = withContext(Dispatchers.IO) {
    if (m15Points.isEmpty()) return@withContext emptyList()
    val base = buildZScoreCandlesFromM15Points(m15Points)
    if (m15Points.size < 2) return@withContext base

    val zone = moexZoneId
    val lastDay = Instant.ofEpochMilli(m15Points.last().timestampMillis).atZone(zone).toLocalDate()
    val intrabarFromDay = lastDay.minusDays(intrabarLookbackDays.coerceAtLeast(1L))
    val intrabarFromMillis = intrabarFromDay.atStartOfDay(zone).toInstant().toEpochMilli()
    val firstEnrichIdx = m15Points.indexOfFirst { it.timestampMillis >= intrabarFromMillis }.coerceAtLeast(0)

    val spread10 = buildSpreadDataPointsFrom10mBars(
        loadCandleBars("TATN", intrabarFromDay, lastDay.plusDays(1), interval = 10),
        loadCandleBars("TATNP", intrabarFromDay, lastDay.plusDays(1), interval = 10),
    )
    if (spread10.size < Z_SCORE_ROLLING_MIN_BARS) return@withContext base

    val spreadsByM15 = build10mSpreadsByM15Index(m15Points, spread10)

    m15Points.mapIndexed { index, point ->
        val open = if (index == 0) point.zScore else m15Points[index - 1].zScore
        val close = point.zScore
        val bodyHigh = max(open, close)
        val bodyLow = min(open, close)
        val range = if (index >= firstEnrichIdx) {
            intrabarZRangeForM15Bar(
                spreads10mInBar = spreadsByM15.getOrNull(index).orEmpty(),
                m15Stats = rollingSpreadStatsAt(m15Points, index),
            )
        } else {
            null
        }
        CandlePoint(
            label = point.tradeDate,
            open = open,
            high = if (range != null) max(bodyHigh, range.max) else bodyHigh,
            low = if (range != null) min(bodyLow, range.min) else bodyLow,
            close = close,
        )
    }
}

/** @deprecated Используйте [buildZScoreCandlesOhlcAnchoredToM15Series] — без разрывов между барами. */
internal suspend fun buildZScoreCandlesFullOhlcForM15Series(
    m15Points: List<DataPoint>,
): List<CandlePoint> = buildZScoreCandlesOhlcAnchoredToM15Series(m15Points)

internal fun buildChartStats(series: List<ChartSeries>): ChartStats {
    val allValues = series.flatMap { it.values }
    if (allValues.isEmpty()) {
        return ChartStats(min = 0.0, max = 0.0)
    }
    return ChartStats(
        min = allValues.minOrNull() ?: 0.0,
        max = allValues.maxOrNull() ?: 0.0
    )
}

internal fun buildCandleStats(candles: List<CandlePoint>): ChartStats {
    if (candles.isEmpty()) {
        return ChartStats(min = 0.0, max = 0.0)
    }
    return ChartStats(
        min = candles.minOfOrNull { it.low } ?: 0.0,
        max = candles.maxOfOrNull { it.high } ?: 0.0
    )
}

internal fun buildCandleAxisScale(
    candles: List<CandlePoint>,
    valueHints: List<Double> = emptyList()
): AxisScale {
    if (candles.isEmpty()) {
        return AxisScale(
            yTicks = emptyList(),
            xTicks = emptyList()
        )
    }
    val min = (candles.map { it.low } + valueHints).minOrNull() ?: 0.0
    val max = (candles.map { it.high } + valueHints).maxOrNull() ?: 1.0
    val normalizedMax = if (max <= min) min + 1.0 else max
    return AxisScale(
        yTicks = buildYTicks(min, normalizedMax, count = 5),
        xTicks = buildXTicks(candles.map { it.label })
    )
}

internal fun buildAxisScale(
    series: List<ChartSeries>,
    labels: List<String>,
    yScale: YAxisScale,
    valueHints: List<Double> = emptyList()
): AxisScale {
    val allValues = series.flatMap { it.values } + valueHints
    if (allValues.isEmpty()) {
        return AxisScale(
            yTicks = emptyList(),
            xTicks = buildXTicks(labels)
        )
    }

    val (min, max) = when (yScale) {
        is YAxisScale.Fixed -> {
            if (yScale.max > yScale.min) {
                yScale.min to yScale.max
            } else {
                yScale.min to (yScale.min + 1.0)
            }
        }

        YAxisScale.Auto -> {
            val rawMin = allValues.minOrNull() ?: 0.0
            val rawMax = allValues.maxOrNull() ?: 1.0
            if (rawMin == rawMax) {
                val padding = (abs(rawMin) * 0.02).coerceAtLeast(1.0)
                (rawMin - padding) to (rawMax + padding)
            } else {
                rawMin to rawMax
            }
        }
    }

    return AxisScale(
        yTicks = buildYTicks(min, max, count = 5),
        xTicks = buildXTicks(labels)
    )
}

internal fun buildYTicks(min: Double, max: Double, count: Int): List<Double> {
    if (count <= 1) return listOf(min)
    val step = (max - min) / (count - 1)
    return (0 until count).map { index ->
        min + step * index
    }
}

/** Подписи оси X: первый день каждого месяца в ряду `yyyy-MM-dd`. */
internal fun buildXMonthTicks(dailyLabels: List<String>): List<XAxisTick> {
    if (dailyLabels.isEmpty()) return emptyList()
    val monthFormatter = DateTimeFormatter.ofPattern("LLL yy", Locale.forLanguageTag("ru"))
    val seenMonths = linkedSetOf<String>()
    return buildList {
        dailyLabels.forEachIndexed { index, label ->
            val date = runCatching { LocalDate.parse(label.trim().take(10)) }.getOrNull() ?: return@forEachIndexed
            val key = "${date.year}-${date.monthValue.toString().padStart(2, '0')}"
            if (key in seenMonths) return@forEachIndexed
            seenMonths += key
            val text = date.format(monthFormatter).replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.forLanguageTag("ru")) else ch.toString()
            }
            add(XAxisTick(index = index, label = text))
        }
    }
}

internal fun formatRubAxisValue(value: Double): String {
    val v = value
    val a = abs(v)
    return when {
        a >= 1_000_000 -> String.format(Locale.US, "%+.1fM ₽", v / 1_000_000.0)
        a >= 10_000 -> String.format(Locale.US, "%+.0fk ₽", v / 1_000.0)
        else -> String.format(Locale.US, "%+.0f ₽", v)
    }
}

internal fun buildXTicks(labels: List<String>, desiredCount: Int = 5): List<XAxisTick> {
    if (labels.isEmpty()) return emptyList()
    if (labels.size == 1) return listOf(XAxisTick(index = 0, label = labels.first()))

    val count = minOf(desiredCount, labels.size)
    val maxIndex = labels.lastIndex
    val uniqueIndices = linkedSetOf<Int>()
    for (i in 0 until count) {
        val index = ((i.toDouble() / (count - 1)) * maxIndex).roundToInt()
        uniqueIndices += index
    }

    return uniqueIndices
        .sorted()
        .map { index -> XAxisTick(index = index, label = labels[index]) }
}
internal fun formatAxisValue(value: Double): String {
    val precision = when {
        abs(value) >= 1000 -> 0
        abs(value) >= 100 -> 1
        abs(value) >= 1 -> 2
        else -> 3
    }
    return String.format(Locale.US, "%.${precision}f", value)
}

internal fun formatPercentDeltaFromBase(value: Double, base: Double): String {
    if (base == 0.0) return "0.00%"
    val deltaPercent = ((value / base) - 1.0) * 100.0
    val sign = if (deltaPercent > 0) "+" else ""
    return sign + String.format(Locale.US, "%.2f%%", deltaPercent)
}
