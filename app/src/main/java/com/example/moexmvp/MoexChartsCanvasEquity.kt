package com.example.moexmvp

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Контрастный Z-score поверх Equity/DD (не сливается с #4FC3F7 и #FFAB40). */
internal val EquityChartZOverlayLineColor = Color(0xFFE879F9)

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

internal fun buildEquityChartZAxisRange(
    zValues: List<Double>,
    referenceLines: List<ChartReferenceLine>,
): Pair<Double, Double> {
    var min = zValues.minOrNull() ?: -1.0
    var max = zValues.maxOrNull() ?: 1.0
    referenceLines.forEach { line ->
        min = min(min, line.value)
        max = max(max, line.value)
    }
    if (min == max) {
        min -= 0.5
        max += 0.5
    }
    val span = (max - min).coerceAtLeast(0.4)
    val pad = span * 0.1
    return (min - pad) to (max + pad)
}

internal fun formatZAxisValue(value: Double): String =
    String.format(Locale.US, "%+.2f", value)

@Composable
internal fun EquityDrawdownComboChart(
    labels: List<String>,
    equityRub: List<Double>,
    drawdownRub: List<Double>,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 280,
    zOverlayPoints: List<DataPoint> = emptyList(),
    zReferenceLines: List<ChartReferenceLine> = emptyList(),
) {
    val n = min(equityRub.size, drawdownRub.size)
    if (n == 0 || labels.isEmpty()) {
        Box(modifier = modifier.height(chartHeightDp.dp), contentAlignment = Alignment.Center) {
            Text("Нет данных для графика equity", color = Color(0xFFD7E3F4), fontSize = 11.sp)
        }
        return
    }
    val equity = equityRub.take(n)
    val drawdownNeg = drawdownRub.take(n).map { v -> if (v > 0) -v else v }
    val xLabels = labels.take(n)
    val monthTicks = buildXMonthTicks(xLabels)
    val equityMax = (equity.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    val ddMax = drawdownNeg.minOrNull()?.let { abs(it) }?.coerceAtLeast(1.0) ?: 1.0
    val equityYTicks = buildYTicks(0.0, equityMax, count = 4)
    val drawdownYTicks = buildYTicks(-ddMax, 0.0, count = 4)
    val zPoints = remember(zOverlayPoints, xLabels) {
        m15PointsInEquityChartRange(zOverlayPoints, xLabels)
    }
    val showZOverlay = zPoints.size >= 2
    val zValues = if (showZOverlay) zPoints.map { it.zScore } else emptyList()
    val (zMin, zMax) = if (showZOverlay) {
        buildEquityChartZAxisRange(zValues, zReferenceLines)
    } else {
        0.0 to 1.0
    }
    val zYTicks = if (showZOverlay) buildYTicks(zMin, zMax, count = 4) else emptyList()
    val timeRange = remember(xLabels) { equityDailyChartTimeRangeMillis(xLabels) }

    val leftPadding = 62f
    val rightPadding = if (showZOverlay) 52f else 12f
    val topPadding = 10f
    val bottomPadding = 72f

    Canvas(
        modifier = modifier
            .height(chartHeightDp.dp)
            .fillMaxWidth()
            .background(Color(0xFF0F1722), RoundedCornerShape(8.dp))
    ) {
        val w = size.width - leftPadding - rightPadding
        val h = size.height - topPadding - bottomPadding
        val midY = topPadding + h / 2f
        val topHalfH = h / 2f
        val bottomHalfH = h / 2f
        val maxIndex = (n - 1).coerceAtLeast(0)

        fun xForTimestamp(ts: Long): Float {
            val range = timeRange
            if (range == null) {
                return if (maxIndex == 0) leftPadding + w / 2f
                else leftPadding + w / 2f
            }
            val (tMin, tMax) = range
            val span = (tMax - tMin).coerceAtLeast(1L)
            val frac = ((ts - tMin).toDouble() / span).toFloat().coerceIn(0f, 1f)
            return leftPadding + frac * w
        }

        fun xForIndex(index: Int): Float {
            if (timeRange != null && index in xLabels.indices) {
                val day = parseChartDateLabel(xLabels[index])
                if (day != null) {
                    val ts = day.atStartOfDay(moexZoneId).toInstant().toEpochMilli()
                    return xForTimestamp(ts)
                }
            }
            return if (maxIndex == 0) leftPadding + w / 2f
            else leftPadding + (index.toFloat() / maxIndex) * w
        }

        fun yEquity(v: Double): Float {
            val rel = (v / equityMax).toFloat().coerceIn(0f, 1f)
            return midY - rel * topHalfH
        }

        fun yDrawdown(v: Double): Float {
            val rel = (abs(v) / ddMax).toFloat().coerceIn(0f, 1f)
            return midY + rel * bottomHalfH
        }

        fun yZ(v: Double): Float {
            val span = (zMax - zMin).coerceAtLeast(1e-6)
            val rel = ((v - zMin) / span).toFloat().coerceIn(0f, 1f)
            return topPadding + h * (1f - rel)
        }

        val labelPaint = Paint().apply {
            color = android.graphics.Color.rgb(215, 227, 244)
            textSize = 10.sp.toPx()
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val monthPaint = Paint().apply {
            color = android.graphics.Color.rgb(215, 227, 244)
            textSize = 10.sp.toPx()
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        val rightZLabelPaint = Paint().apply {
            color = EquityChartZOverlayLineColor.toArgb()
            textSize = 9.sp.toPx()
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
        }

        if (showZOverlay) {
            zYTicks.forEach { tick ->
                val y = yZ(tick)
                drawLine(
                    color = Color(0xFF253347),
                    start = Offset(leftPadding, y),
                    end = Offset(leftPadding + w, y),
                    strokeWidth = 1f,
                )
            }
            zReferenceLines.forEach { reference ->
                val y = yZ(reference.value)
                drawLine(
                    color = reference.color.copy(alpha = 0.85f),
                    start = Offset(leftPadding, y),
                    end = Offset(leftPadding + w, y),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(
                        intervals = floatArrayOf(reference.dashOnPx, reference.dashOffPx),
                    ),
                )
            }
        }

        equityYTicks.forEach { tick ->
            val y = yEquity(tick)
            drawLine(
                color = Color(0xFF30455A),
                start = Offset(leftPadding, y),
                end = Offset(leftPadding + w, y),
                strokeWidth = 1f
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatRubAxisValue(tick),
                leftPadding - 6f,
                y + 4f,
                labelPaint
            )
        }
        drawdownYTicks.forEach { tick ->
            val y = yDrawdown(tick)
            drawLine(
                color = Color(0xFF30455A),
                start = Offset(leftPadding, y),
                end = Offset(leftPadding + w, y),
                strokeWidth = 1f
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatRubAxisValue(tick),
                leftPadding - 6f,
                y + 4f,
                labelPaint
            )
        }

        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(leftPadding, midY),
            end = Offset(leftPadding + w, midY),
            strokeWidth = 2f
        )
        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(leftPadding, topPadding),
            end = Offset(leftPadding, topPadding + h),
            strokeWidth = 1.5f
        )
        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(leftPadding, topPadding + h),
            end = Offset(leftPadding + w, topPadding + h),
            strokeWidth = 1.5f
        )

        val barW = (w / n.coerceAtLeast(1)) * 0.55f
        equity.forEachIndexed { i, v ->
            val cx = xForIndex(i)
            val yTop = yEquity(v.coerceAtLeast(0.0))
            val barColor = if (v >= 0.0) Color(0xFF4FC3F7) else Color(0xFFEF5350)
            drawRect(
                color = barColor.copy(alpha = 0.85f),
                topLeft = Offset(cx - barW / 2f, yTop),
                size = Size(barW, (midY - yTop).coerceAtLeast(2f))
            )
        }

        if (n >= 2) {
            val path = Path()
            drawdownNeg.forEachIndexed { i, v ->
                val pt = Offset(xForIndex(i), yDrawdown(v))
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            drawPath(
                path = path,
                color = Color(0xFFFFAB40),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round)
            )
        }

        if (showZOverlay && zPoints.size >= 2) {
            val zPath = Path()
            zPoints.forEachIndexed { i, point ->
                val pt = Offset(xForTimestamp(point.timestampMillis), yZ(point.zScore))
                if (i == 0) zPath.moveTo(pt.x, pt.y) else zPath.lineTo(pt.x, pt.y)
            }
            drawPath(
                path = zPath,
                color = Color.White.copy(alpha = 0.22f),
                style = Stroke(width = 4f, cap = StrokeCap.Round),
            )
            drawPath(
                path = zPath,
                color = EquityChartZOverlayLineColor.copy(alpha = 0.95f),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round),
            )
            drawReferenceLineLabels(
                referenceLines = zReferenceLines,
                leftPadding = leftPadding,
                topPadding = topPadding,
                chartWidth = w,
                chartHeight = h,
                yForValue = ::yZ,
            )
            zYTicks.forEach { tick ->
                val y = yZ(tick)
                drawContext.canvas.nativeCanvas.drawText(
                    formatZAxisValue(tick),
                    leftPadding + w + 6f,
                    y + 4f,
                    rightZLabelPaint,
                )
            }
            drawLine(
                color = Color(0xFF8AA6C1),
                start = Offset(leftPadding + w, topPadding),
                end = Offset(leftPadding + w, topPadding + h),
                strokeWidth = 1.5f,
            )
        }

        monthTicks.forEach { tick ->
            val x = xForIndex(tick.index)
            drawLine(
                color = Color(0xFF2A3D50),
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + h),
                strokeWidth = 1f
            )
            val y = topPadding + h + 34f
            drawContext.canvas.nativeCanvas.save()
            drawContext.canvas.nativeCanvas.rotate(-55f, x, y)
            drawContext.canvas.nativeCanvas.drawText(tick.label, x, y, monthPaint)
            drawContext.canvas.nativeCanvas.restore()
        }

        drawContext.canvas.nativeCanvas.drawText(
            formatRubAxisValue(0.0),
            leftPadding - 6f,
            midY + 4f,
            labelPaint
        )
    }
}

internal fun resolveSelectedIndex(
    tapX: Float,
    canvasWidth: Float,
    leftPadding: Float,
    rightPadding: Float,
    maxIndex: Int
): Int {
    if (maxIndex <= 0) return 0
    val chartWidth = (canvasWidth - leftPadding - rightPadding).coerceAtLeast(1f)
    val clamped = (tapX - leftPadding).coerceIn(0f, chartWidth)
    return ((clamped / chartWidth) * maxIndex).roundToInt().coerceIn(0, maxIndex)
}
