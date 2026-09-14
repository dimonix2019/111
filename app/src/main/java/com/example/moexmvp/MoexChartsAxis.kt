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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val upper = maxOf(2f, slot * 0.92f)
    return (slot * 0.72f).coerceIn(2f, upper)
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
 * 15м Z-свечи из того же close-ряда, что симуляция «Тест страт.»:
 * close = Z бара, open = Z предыдущего бара (без 10м фитилей).
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

/** 15м свечи спреда %: close = spread%, open = spread% предыдущего бара. */
internal fun buildSpreadCandlesFromM15Points(points: List<DataPoint>): List<CandlePoint> {
    return points.mapIndexed { index, point ->
        val close = point.spreadPercent
        val open = points.getOrNull(index - 1)?.spreadPercent ?: close
        CandlePoint(
            label = point.tradeDate,
            open = open,
            high = max(open, close),
            low = min(open, close),
            close = close,
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
    val statsCache = RollingSpreadStatsCache.from(m15Points)

    m15Points.mapIndexed { index, point ->
        val open = if (index == 0) point.zScore else m15Points[index - 1].zScore
        val close = point.zScore
        val bodyHigh = max(open, close)
        val bodyLow = min(open, close)
        val range = if (index >= firstEnrichIdx) {
            intrabarZRangeForM15Bar(
                spreads10mInBar = spreadsByM15.getOrNull(index).orEmpty(),
                m15Stats = statsCache.at(index),
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
        ).let(::clampZScoreCandleWicks)
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

private val m15ChartXAxisLabelFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yy HH:mm", Locale.forLanguageTag("ru"))

/** Подпись времени под 15м Z-графиком: dd.MM.yy HH:mm (год «26», без секунд). */
internal fun formatM15ChartXAxisLabel(tradeDateLabel: String): String {
    val trimmed = tradeDateLabel.trim()
    val dt = runCatching { LocalDateTime.parse(trimmed, portfolio15mLabelFormatter) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(trimmed, updatedAtFormatter) }.getOrNull()
        ?: return trimmed
            .removeSuffix(":00")
            .let { s -> if (s.length >= 16) s.substring(2, 16) else s.takeLast(14) }
    return dt.format(m15ChartXAxisLabelFormatter)
}

/** Время последней загрузки MOEX в сводке «Рынок» (без секунд). */
internal fun formatMarketsLoadedAtShort(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "—") return "—"
    val dt = parseMarketsLoadedAt(raw)
        ?: return sanitizeMarketsLoadedAtRaw(raw).removeSuffix(":00").takeLast(14)
    return dt.format(m15ChartXAxisLabelFormatter)
}

internal data class ChartXLabelStyle(
    val rotationDeg: Float,
    val bottomPaddingPx: Float,
    val baselineFromBottomPx: Float,
    val horizontal: Boolean,
)

internal val ChartXLabelStyleTilted = ChartXLabelStyle(
    rotationDeg = CHART_X_LABEL_ROTATION_DEG,
    bottomPaddingPx = CHART_BOTTOM_PADDING_PX,
    baselineFromBottomPx = CHART_X_LABEL_BASELINE_FROM_BOTTOM_PX,
    horizontal = false,
)

internal val ChartXLabelStyleHorizontal = ChartXLabelStyle(
    rotationDeg = 0f,
    bottomPaddingPx = 36f,
    baselineFromBottomPx = 12f,
    horizontal = true,
)

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
        xTicks = buildXTicksForM15Candles(candles),
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

/** Подписи X для 15м рядов (линейный график спреда и т.п.). */
internal fun buildXTicksForM15Labels(
    labels: List<String>,
    desiredCount: Int = 5,
): List<XAxisTick> {
    if (labels.isEmpty()) return emptyList()
    return buildXTicks(labels, desiredCount)
        .map { tick ->
            XAxisTick(
                index = tick.index,
                label = formatM15ChartXAxisLabel(labels[tick.index]),
            )
        }
}

/** Подписи X для 15м свечей: индексы по времени слева→направо, короткий dd.MM.yy HH:mm. */
internal fun buildXTicksForM15Candles(
    candles: List<CandlePoint>,
    desiredCount: Int = 6,
): List<XAxisTick> {
    if (candles.isEmpty()) return emptyList()
    return buildXTicks(candles.map { it.label }, desiredCount)
        .map { tick ->
            XAxisTick(
                index = tick.index,
                label = formatM15ChartXAxisLabel(candles[tick.index].label),
            )
        }
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

/** Время открытия торгового дня (МСК) — совпадает с [DYNAMIC_Z_RECALC_HOUR]/[DYNAMIC_Z_RECALC_MINUTE]. */
internal fun tradingDayOpenTimeMsk(): LocalTime =
    LocalTime.of(DYNAMIC_Z_RECALC_HOUR, DYNAMIC_Z_RECALC_MINUTE)

internal fun m15LabelCalendarDate(label: String): LocalDate? =
    runCatching { LocalDate.parse(label.trim().take(10)) }.getOrNull()

internal fun m15LabelBarTime(label: String): LocalTime? =
    runCatching {
        LocalTime.parse(label.trim().drop(11).take(5), intradayLabelFormatter)
    }.getOrNull()

/**
 * Спред на открытии торгового дня: первый 15м бар дня с временем ≥ 07:30 МСК
 * (утренняя сессия MOEX; в ISS-ряде это обычно бар 07:30).
 */
internal fun spreadPercentAtTradingDayOpen(
    points: List<DataPoint>,
    day: LocalDate,
): Double? {
    if (points.isEmpty()) return null
    val openTime = tradingDayOpenTimeMsk()
    val dayPoints = points.filter { m15LabelCalendarDate(it.tradeDate) == day }
    if (dayPoints.isEmpty()) return null
    val atOrAfterOpen = dayPoints.filter { pt ->
        m15LabelBarTime(pt.tradeDate)?.let { it >= openTime } == true
    }
    return atOrAfterOpen.firstOrNull()?.spreadPercent
        ?: dayPoints.first().spreadPercent
}

/** База правой оси Spread: открытие последнего календарного дня в видимом ряду. */
internal fun spreadPercentBaseForChartRightAxis(points: List<DataPoint>): Double? {
    if (points.isEmpty()) return null
    val lastDay = points.mapNotNull { m15LabelCalendarDate(it.tradeDate) }.maxOrNull()
        ?: return points.first().spreadPercent
    return spreadPercentAtTradingDayOpen(points, lastDay)
}
