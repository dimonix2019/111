package com.example.moexmvp

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Общая геометрия Z + Equity на «Тест страт.» — одинаковая ширина plot и даты. */
internal const val STRATEGY_TEST_CHART_LEFT_PADDING = 62f
internal const val STRATEGY_TEST_CHART_RIGHT_PADDING = 52f
internal const val STRATEGY_TEST_CHART_TOP_PADDING = 10f
internal const val STRATEGY_TEST_CHART_BOTTOM_PADDING = 72f

internal val StrategyTestZLineColor = DesktopChartColors.zLine

internal data class StrategyTestChartTimeAxis(
    val dailyLabels: List<String>,
    val timeRange: Pair<Long, Long>,
    val monthTicks: List<XAxisTick>,
)

internal fun buildStrategyTestChartTimeAxis(dailyLabels: List<String>): StrategyTestChartTimeAxis? {
    if (dailyLabels.size < 2) return null
    val range = equityDailyChartTimeRangeMillis(dailyLabels) ?: return null
    return StrategyTestChartTimeAxis(
        dailyLabels = dailyLabels,
        timeRange = range,
        monthTicks = buildXMonthTicks(dailyLabels),
    )
}

internal fun equityDailyChartTimeRangeMillis(labels: List<String>): Pair<Long, Long>? {
    if (labels.isEmpty()) return null
    val zone = moexZoneId
    val firstDay = parseChartDateLabel(labels.first()) ?: return null
    val lastDay = parseChartDateLabel(labels.last()) ?: return null
    val tMin = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
    val tMax = lastDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
    return tMin to tMax
}

internal fun m15PointsInEquityChartRange(
    points: List<DataPoint>,
    equityDailyLabels: List<String>,
): List<DataPoint> {
    if (points.size < 2 || equityDailyLabels.isEmpty()) return points
    val range = equityDailyChartTimeRangeMillis(equityDailyLabels) ?: return points
    val (tMin, tMax) = range
    return points.filter { it.timestampMillis in tMin..tMax }
}

internal fun downsamplePointsForChartLine(
    points: List<DataPoint>,
    maxPoints: Int = 900,
): List<DataPoint> {
    if (points.size <= maxPoints) return points
    val step = points.size.toDouble() / maxPoints
    return buildList {
        var i = 0.0
        while (i < points.size) {
            add(points[i.toInt().coerceIn(0, points.lastIndex)])
            i += step
        }
        if (lastOrNull()?.timestampMillis != points.last().timestampMillis) {
            add(points.last())
        }
    }
}

internal fun buildStrategyTestZAxisRange(
    zValues: List<Double>,
    referenceLines: List<ChartReferenceLine>,
): Pair<Double, Double> {
    var zMin = zValues.minOrNull() ?: -1.0
    var zMax = zValues.maxOrNull() ?: 1.0
    referenceLines.forEach { line ->
        zMin = min(zMin, line.value)
        zMax = max(zMax, line.value)
    }
    if (zMin == zMax) {
        zMin -= 0.5
        zMax += 0.5
    }
    val span = (zMax - zMin).coerceAtLeast(0.4)
    val pad = span * 0.1
    return (zMin - pad) to (zMax + pad)
}

internal fun formatStrategyTestZAxisValue(value: Double): String =
    String.format(Locale.US, "%+.2f", value)

internal class StrategyTestChartXMapper(
    private val timeRange: Pair<Long, Long>,
    private val leftPadding: Float,
    private val plotWidth: Float,
) {
    fun xForTimestamp(ts: Long): Float {
        val (tMin, tMax) = timeRange
        val span = (tMax - tMin).coerceAtLeast(1L)
        val frac = ((ts - tMin).toDouble() / span).toFloat().coerceIn(0f, 1f)
        return leftPadding + frac * plotWidth
    }

    fun xForDayIndex(dailyLabels: List<String>, index: Int): Float {
        if (index !in dailyLabels.indices) return leftPadding
        val day = parseChartDateLabel(dailyLabels[index]) ?: return leftPadding
        val ts = day.atStartOfDay(moexZoneId).toInstant().toEpochMilli()
        return xForTimestamp(ts)
    }
}

internal fun drawStrategyTestMonthTicks(
    drawScope: androidx.compose.ui.graphics.drawscope.DrawScope,
    axis: StrategyTestChartTimeAxis,
    xMapper: StrategyTestChartXMapper,
    leftPadding: Float,
    plotWidth: Float,
    topPadding: Float,
    plotHeight: Float,
    labelTextSizePx: Float,
) {
    val monthPaint = Paint().apply {
        color = android.graphics.Color.rgb(215, 227, 244)
        textSize = labelTextSizePx
        textAlign = Paint.Align.RIGHT
        isAntiAlias = true
    }
    axis.monthTicks.forEach { tick ->
        val x = xMapper.xForDayIndex(axis.dailyLabels, tick.index)
        drawScope.drawLine(
            color = Color(0xFF2A3D50),
            start = Offset(x, topPadding),
            end = Offset(x, topPadding + plotHeight),
            strokeWidth = 1f,
        )
        val y = topPadding + plotHeight + 34f
        drawScope.drawContext.canvas.nativeCanvas.save()
        drawScope.drawContext.canvas.nativeCanvas.rotate(-55f, x, y)
        drawScope.drawContext.canvas.nativeCanvas.drawText(tick.label, x, y, monthPaint)
        drawScope.drawContext.canvas.nativeCanvas.restore()
    }
}

@Composable
internal fun StrategyTestZScoreLineChartCard(
    dailyLabels: List<String>,
    m15Points: List<DataPoint>,
    referenceLines: List<ChartReferenceLine>,
    chartHeightDp: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF171717), RoundedCornerShape(8.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Z-score", color = StrategyTestZLineColor, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        StrategyTestZScoreLineChart(
            dailyLabels = dailyLabels,
            m15Points = m15Points,
            referenceLines = referenceLines,
            chartHeightDp = chartHeightDp,
        )
    }
}

@Composable
internal fun StrategyTestZScoreLineChart(
    dailyLabels: List<String>,
    m15Points: List<DataPoint>,
    referenceLines: List<ChartReferenceLine>,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 200,
) {
    val axis = remember(dailyLabels) { buildStrategyTestChartTimeAxis(dailyLabels) }
    val linePoints = remember(m15Points, dailyLabels) {
        val inRange = m15PointsInEquityChartRange(m15Points, dailyLabels)
        downsamplePointsForChartLine(inRange)
    }
    if (axis == null || linePoints.size < 2) {
        Box(
            modifier = modifier
                .height(chartHeightDp.dp)
                .fillMaxWidth()
                .background(Color(0xFF0F1722), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Нет данных для Z-score", color = Color(0xFFD7E3F4), fontSize = 11.sp)
        }
        return
    }
    val zValues = linePoints.map { it.zScore }
    val (zMin, zMax) = buildStrategyTestZAxisRange(zValues, referenceLines)
    val zYTicks = buildYTicks(zMin, zMax, count = 4)

    Canvas(
        modifier = modifier
            .height(chartHeightDp.dp)
            .fillMaxWidth()
            .background(Color(0xFF0F1722), RoundedCornerShape(8.dp)),
    ) {
        val plotWidth = size.width - STRATEGY_TEST_CHART_LEFT_PADDING - STRATEGY_TEST_CHART_RIGHT_PADDING
        val plotHeight = size.height - STRATEGY_TEST_CHART_TOP_PADDING - STRATEGY_TEST_CHART_BOTTOM_PADDING
        val xMapper = StrategyTestChartXMapper(axis.timeRange, STRATEGY_TEST_CHART_LEFT_PADDING, plotWidth)

        fun yZ(v: Double): Float {
            val span = (zMax - zMin).coerceAtLeast(1e-6)
            val rel = ((v - zMin) / span).toFloat().coerceIn(0f, 1f)
            return STRATEGY_TEST_CHART_TOP_PADDING + plotHeight * (1f - rel)
        }

        val labelPaint = Paint().apply {
            color = android.graphics.Color.rgb(215, 227, 244)
            textSize = 10.sp.toPx()
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val rightZLabelPaint = Paint().apply {
            color = StrategyTestZLineColor.toArgb()
            textSize = 10.sp.toPx()
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
        }

        zYTicks.forEach { tick ->
            val y = yZ(tick)
            drawLine(
                color = Color(0xFF30455A),
                start = Offset(STRATEGY_TEST_CHART_LEFT_PADDING, y),
                end = Offset(STRATEGY_TEST_CHART_LEFT_PADDING + plotWidth, y),
                strokeWidth = 1f,
            )
        }
        referenceLines.forEach { reference ->
            val y = yZ(reference.value)
            drawLine(
                color = reference.color.copy(alpha = 0.85f),
                start = Offset(STRATEGY_TEST_CHART_LEFT_PADDING, y),
                end = Offset(STRATEGY_TEST_CHART_LEFT_PADDING + plotWidth, y),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(reference.dashOnPx, reference.dashOffPx),
                ),
            )
        }

        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(STRATEGY_TEST_CHART_LEFT_PADDING, STRATEGY_TEST_CHART_TOP_PADDING),
            end = Offset(STRATEGY_TEST_CHART_LEFT_PADDING, STRATEGY_TEST_CHART_TOP_PADDING + plotHeight),
            strokeWidth = 1.5f,
        )
        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(STRATEGY_TEST_CHART_LEFT_PADDING, STRATEGY_TEST_CHART_TOP_PADDING + plotHeight),
            end = Offset(STRATEGY_TEST_CHART_LEFT_PADDING + plotWidth, STRATEGY_TEST_CHART_TOP_PADDING + plotHeight),
            strokeWidth = 1.5f,
        )
        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(STRATEGY_TEST_CHART_LEFT_PADDING + plotWidth, STRATEGY_TEST_CHART_TOP_PADDING),
            end = Offset(STRATEGY_TEST_CHART_LEFT_PADDING + plotWidth, STRATEGY_TEST_CHART_TOP_PADDING + plotHeight),
            strokeWidth = 1.5f,
        )

        val path = Path()
        linePoints.forEachIndexed { i, point ->
            val pt = Offset(xMapper.xForTimestamp(point.timestampMillis), yZ(point.zScore))
            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
        }
        drawPath(
            path = path,
            color = StrategyTestZLineColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
        )

        drawReferenceLineLabels(
            referenceLines = referenceLines,
            leftPadding = STRATEGY_TEST_CHART_LEFT_PADDING,
            topPadding = STRATEGY_TEST_CHART_TOP_PADDING,
            chartWidth = plotWidth,
            chartHeight = plotHeight,
            yForValue = ::yZ,
            labelTextSizeSp = 10.sp,
        )
        zYTicks.forEach { tick ->
            val y = yZ(tick)
            drawContext.canvas.nativeCanvas.drawText(
                formatStrategyTestZAxisValue(tick),
                STRATEGY_TEST_CHART_LEFT_PADDING + plotWidth + 6f,
                y + 4f,
                rightZLabelPaint,
            )
        }

        drawStrategyTestMonthTicks(
            drawScope = this,
            axis = axis,
            xMapper = xMapper,
            leftPadding = STRATEGY_TEST_CHART_LEFT_PADDING,
            plotWidth = plotWidth,
            topPadding = STRATEGY_TEST_CHART_TOP_PADDING,
            plotHeight = plotHeight,
            labelTextSizePx = 10.sp.toPx(),
        )
    }
}
