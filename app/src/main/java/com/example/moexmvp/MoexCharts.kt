package com.example.moexmvp

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFEBEE), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Error", color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
        Text(message)
        Button(onClick = onRetry) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Retry", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
internal fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("No data for selected period")
    }
}

@Composable
internal fun SummaryBlock(points: List<DataPoint>, loadedAt: String) {
    val latest = points.lastOrNull() ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171717), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Latest: ${latest.tradeDate}", fontWeight = FontWeight.Bold, color = Color.White)
        Text("Updated: $loadedAt", color = Color(0xFFBDBDBD), fontSize = 12.sp)
        Text(
            text = "TATN ${"%.2f".format(latest.tatnClose)}   |   TATNP ${"%.2f".format(latest.tatnpClose)}",
            color = Color.White
        )
        Text(
            text = "Z-score ${"%.2f".format(latest.zScore)}   |   Spread ${"%.2f".format(latest.spreadPercent)}%   |   Diff ${"%.2f".format(latest.diff)}",
            color = Color.White
        )
    }
}

@Composable
internal fun PeriodSelector(selected: Period, onSelect: (Period) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Period.entries.forEach { period ->
            val active = period == selected
            Button(
                onClick = { onSelect(period) },
                modifier = Modifier.height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) {
                        Color(0xFF1565C0)
                    } else {
                        Color(0xFF2196F3)
                    },
                    contentColor = if (active) {
                        Color.White
                    } else {
                        Color.White
                    }
                )
            ) {
                Text(text = period.label)
            }
        }
    }
}

@Composable
internal fun RealtimeControls(
    enabled: Boolean,
    isRefreshing: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171717), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Realtime", fontWeight = FontWeight.Bold, color = Color.White)
            Button(
                onClick = onToggle,
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (enabled) Color(0xFF2E7D32) else Color(0xFF2196F3),
                    contentColor = Color.White
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (enabled) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(if (enabled) "ON" else "OFF", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        Text("Interval: 5s (fixed)", color = Color(0xFFB3E5FC), fontSize = 12.sp)
        Text(
            text = if (isRefreshing) "Status: updating..." else "Status: up to date",
            fontSize = 12.sp,
            color = if (isRefreshing) Color(0xFF90CAF9) else Color(0xFFA5D6A7)
        )
    }
}

@Composable
internal fun SpreadScaleControls(mode: SpreadScaleMode, onModeChange: (SpreadScaleMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Spread scale", fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SpreadScaleMode.entries.forEach { candidate ->
                val active = candidate == mode
                Button(
                    onClick = { onModeChange(candidate) },
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                ) {
                    Text(candidate.label)
                }
            }
        }
    }
}

@Composable
internal fun ChartCard(
    title: String,
    series: List<ChartSeries>,
    labels: List<String>,
    chartHeightDp: Int = 180,
    rightAxisPercentBase: Double? = null,
    yScale: YAxisScale = YAxisScale.Auto,
    referenceLines: List<ChartReferenceLine> = emptyList(),
    pointMarkers: List<ChartPointMarker> = emptyList(),
    showLegend: Boolean = true,
    enableZoomPan: Boolean = false,
    markerScale: Float = 1f,
    showZoomHint: Boolean = false,
    /** Доп. строка под выбранной точкой (например PnL симуляции по Z). */
    tradeTapHintFormatter: ((Int) -> String?)? = null
) {
    val axisScale = remember(series, labels, yScale, referenceLines) {
        buildAxisScale(
            series = series,
            labels = labels,
            yScale = yScale,
            valueHints = referenceLines.map { it.value }
        )
    }
    val stats = remember(series) { buildChartStats(series) }
    var selectedIndex by remember(series, labels) { mutableStateOf<Int?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171717), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = Color.White)
        if (showZoomHint && enableZoomPan) {
            Text(
                text = "Масштаб: два пальца · сдвиг: перетаскивание · двойной тап: весь период",
                color = Color(0xFF9FA8DA),
                fontSize = 10.sp
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .height(chartHeightDp.dp)
                    .width(54.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                axisScale.yTicks
                    .asReversed()
                    .forEach { tick ->
                        Text(
                            text = formatAxisValue(tick),
                            fontSize = 10.sp,
                            color = Color(0xFFD7E3F4)
                        )
                    }
            }
            LineChart(
                series = series,
                yTicks = axisScale.yTicks,
                xTicks = axisScale.xTicks,
                selectedIndex = selectedIndex,
                onSelectIndex = { selectedIndex = it },
                referenceLines = referenceLines,
                pointMarkers = pointMarkers,
                modifier = Modifier
                    .weight(1f)
                    .height(chartHeightDp.dp),
                enableZoomPan = enableZoomPan,
                markerScale = markerScale
            )
            if (rightAxisPercentBase != null && rightAxisPercentBase != 0.0) {
                Column(
                    modifier = Modifier
                        .height(chartHeightDp.dp)
                        .width(64.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    axisScale.yTicks
                        .asReversed()
                        .forEach { tick ->
                            Text(
                                text = formatPercentDeltaFromBase(tick, rightAxisPercentBase),
                                fontSize = 10.sp,
                                color = Color(0xFFD7E3F4)
                            )
                        }
                }
            }
        }
        if (showLegend) {
            Legend(
                series = series,
                referenceLines = referenceLines,
                pointMarkers = pointMarkers
            )
        }
        selectedIndex?.let { selected ->
            val label = labels.getOrNull(selected)
            val values = series.mapNotNull { chartSeries ->
                chartSeries.values.getOrNull(selected)?.let { value ->
                    "${chartSeries.name}: ${formatAxisValue(value)}"
                }
            }
            if (!label.isNullOrBlank() && values.isNotEmpty()) {
                Text(
                    text = "↕ $label | ${values.joinToString(" | ")}",
                    fontSize = 12.sp,
                    color = Color(0xFFF1F8FF)
                )
            }
            tradeTapHintFormatter?.invoke(selected)?.let { hint ->
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = Color(0xFFFFE082),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Text(
            text = "Min: ${formatAxisValue(stats.min)}   Max: ${formatAxisValue(stats.max)}",
            fontSize = 12.sp,
            color = Color(0xFFD7E3F4)
        )
    }
}

@Composable
internal fun CandlestickChartCard(title: String, candles: List<CandlePoint>) {
    val axisScale = remember(candles) { buildCandleAxisScale(candles) }
    val stats = remember(candles) { buildCandleStats(candles) }
    var selectedIndex by remember(candles) { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171717), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = Color.White)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .height(180.dp)
                    .width(54.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                axisScale.yTicks
                    .asReversed()
                    .forEach { tick ->
                        Text(
                            text = formatAxisValue(tick),
                            fontSize = 10.sp,
                            color = Color(0xFFD7E3F4)
                        )
                    }
            }
            CandlestickChart(
                candles = candles,
                yTicks = axisScale.yTicks,
                xTicks = axisScale.xTicks,
                selectedIndex = selectedIndex,
                onSelectIndex = { selectedIndex = it },
                modifier = Modifier
                    .weight(1f)
                    .height(180.dp)
            )
        }
        selectedIndex?.let { selected ->
            candles.getOrNull(selected)?.let { candle ->
                Text(
                    text = "↕ ${candle.label} | O ${formatAxisValue(candle.open)} | " +
                        "H ${formatAxisValue(candle.high)} | L ${formatAxisValue(candle.low)} | " +
                        "C ${formatAxisValue(candle.close)}",
                    fontSize = 12.sp,
                    color = Color(0xFFF1F8FF)
                )
            }
        }
        Text(
            text = "Min: ${formatAxisValue(stats.min)}   Max: ${formatAxisValue(stats.max)}",
            fontSize = 12.sp,
            color = Color(0xFFD7E3F4)
        )
    }
}

@Composable
internal fun Legend(
    series: List<ChartSeries>,
    referenceLines: List<ChartReferenceLine> = emptyList(),
    pointMarkers: List<ChartPointMarker> = emptyList()
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        series.forEach { item ->
            LegendItem(
                color = item.color,
                label = item.name,
                square = true
            )
        }
        referenceLines.forEach { reference ->
            LegendItem(
                color = reference.color,
                label = reference.label,
                square = false
            )
        }
        pointMarkers
            .distinctBy { it.label }
            .forEach { marker ->
                LegendItem(
                    color = marker.color,
                    label = marker.label,
                    square = true
                )
            }
    }
}

@Composable
internal fun LegendItem(
    color: Color,
    label: String,
    square: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(if (square) 14.dp else 4.dp)
                .background(
                    color = color,
                    shape = if (square) CircleShape else RoundedCornerShape(2.dp)
                )
        )
        Text("  $label", color = Color(0xFFF1F8FF))
    }
}


internal fun buildZScoreReferenceLines(thresholds: DynamicThresholds): List<ChartReferenceLine> {
    return listOf(
        ChartReferenceLine(
            value = thresholds.entry,
            color = Color(0xFFFFB74D),
            label = String.format(Locale.US, "+%.2f", thresholds.entry)
        ),
        ChartReferenceLine(
            value = -thresholds.entry,
            color = Color(0xFFFFB74D),
            label = String.format(Locale.US, "-%.2f", thresholds.entry)
        ),
        ChartReferenceLine(
            value = thresholds.exit,
            color = Color(0xFFA5D6A7),
            label = String.format(Locale.US, "+%.2f", thresholds.exit)
        ),
        ChartReferenceLine(
            value = -thresholds.exit,
            color = Color(0xFFA5D6A7),
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

internal fun buildCandleAxisScale(candles: List<CandlePoint>): AxisScale {
    if (candles.isEmpty()) {
        return AxisScale(
            yTicks = emptyList(),
            xTicks = emptyList()
        )
    }
    val min = candles.minOfOrNull { it.low } ?: 0.0
    val max = candles.maxOfOrNull { it.high } ?: 1.0
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

@Composable
internal fun StrategyTestEquityDrawdownChartCard(
    labels: List<String>,
    equityRub: List<Double>,
    drawdownRub: List<Double>,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 220
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF171717), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Equity и просадка (симуляция, ₽)",
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 14.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("■ Equity", color = Color(0xFF4FC3F7), fontSize = 10.sp)
            Text("— Drawdown", color = Color(0xFFFFAB40), fontSize = 10.sp)
        }
        Text(
            text = "Верх: Equity (₽) от нулевой линии. Низ: Drawdown (отрицательный, ₽). По оси X — месяцы.",
            color = Color(0xFF9E9E9E),
            fontSize = 9.sp,
            lineHeight = 12.sp
        )
        EquityDrawdownComboChart(
            labels = labels,
            equityRub = equityRub,
            drawdownRub = drawdownRub,
            chartHeightDp = chartHeightDp
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
