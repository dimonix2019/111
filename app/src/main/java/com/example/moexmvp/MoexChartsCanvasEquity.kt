package com.example.moexmvp

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun EquityDrawdownComboChart(
    labels: List<String>,
    equityRub: List<Double>,
    drawdownRub: List<Double>,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 280,
    syncTimeAxis: StrategyTestChartTimeAxis? = null,
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
    val monthTicks = syncTimeAxis?.monthTicks ?: buildXMonthTicks(xLabels)
    val equityMax = (equity.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    val ddMax = drawdownNeg.minOrNull()?.let { abs(it) }?.coerceAtLeast(1.0) ?: 1.0
    val equityYTicks = buildYTicks(0.0, equityMax, count = 4)
    val drawdownYTicks = buildYTicks(-ddMax, 0.0, count = 4)

    val leftPadding = if (syncTimeAxis != null) STRATEGY_TEST_CHART_LEFT_PADDING else 62f
    val rightPadding = if (syncTimeAxis != null) STRATEGY_TEST_CHART_RIGHT_PADDING else 12f
    val topPadding = if (syncTimeAxis != null) STRATEGY_TEST_CHART_TOP_PADDING else 10f
    val bottomPadding = if (syncTimeAxis != null) STRATEGY_TEST_CHART_BOTTOM_PADDING else 72f

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
        val plotWidth = w
        val xMapper = syncTimeAxis?.let {
            StrategyTestChartXMapper(it.timeRange, leftPadding, plotWidth)
        }

        fun xForIndex(index: Int): Float {
            if (xMapper != null && syncTimeAxis != null) {
                return xMapper.xForDayIndex(syncTimeAxis.dailyLabels, index)
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

        equityYTicks.forEach { tick ->
            val y = yEquity(tick)
            drawLine(
                color = Color(0xFF30455A),
                start = Offset(leftPadding, y),
                end = Offset(leftPadding + w, y),
                strokeWidth = 1f,
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatRubAxisValue(tick),
                leftPadding - 6f,
                y + 4f,
                labelPaint,
            )
        }
        drawdownYTicks.forEach { tick ->
            val y = yDrawdown(tick)
            drawLine(
                color = Color(0xFF30455A),
                start = Offset(leftPadding, y),
                end = Offset(leftPadding + w, y),
                strokeWidth = 1f,
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatRubAxisValue(tick),
                leftPadding - 6f,
                y + 4f,
                labelPaint,
            )
        }

        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(leftPadding, midY),
            end = Offset(leftPadding + w, midY),
            strokeWidth = 2f,
        )
        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(leftPadding, topPadding),
            end = Offset(leftPadding, topPadding + h),
            strokeWidth = 1.5f,
        )
        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(leftPadding, topPadding + h),
            end = Offset(leftPadding + w, topPadding + h),
            strokeWidth = 1.5f,
        )

        val barW = (w / n.coerceAtLeast(1)) * 0.55f
        equity.forEachIndexed { i, v ->
            val cx = xForIndex(i)
            val yTop = yEquity(v.coerceAtLeast(0.0))
            val barColor = if (v >= 0.0) Color(0xFF4FC3F7) else Color(0xFFEF5350)
            drawRect(
                color = barColor.copy(alpha = 0.85f),
                topLeft = Offset(cx - barW / 2f, yTop),
                size = Size(barW, (midY - yTop).coerceAtLeast(2f)),
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
                style = Stroke(width = 2.5f, cap = StrokeCap.Round),
            )
        }

        if (syncTimeAxis != null && xMapper != null) {
            drawStrategyTestMonthTicks(
                drawScope = this,
                axis = syncTimeAxis,
                xMapper = xMapper,
                leftPadding = leftPadding,
                plotWidth = plotWidth,
                topPadding = topPadding,
                plotHeight = h,
                labelTextSizePx = 10.sp.toPx(),
            )
        } else {
            monthTicks.forEach { tick ->
                val x = xForIndex(tick.index)
                drawLine(
                    color = Color(0xFF2A3D50),
                    start = Offset(x, topPadding),
                    end = Offset(x, topPadding + h),
                    strokeWidth = 1f,
                )
                val y = topPadding + h + 34f
                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.rotate(-55f, x, y)
                drawContext.canvas.nativeCanvas.drawText(tick.label, x, y, monthPaint)
                drawContext.canvas.nativeCanvas.restore()
            }
        }

        drawContext.canvas.nativeCanvas.drawText(
            formatRubAxisValue(0.0),
            leftPadding - 6f,
            midY + 4f,
            labelPaint,
        )
    }
}

internal fun resolveSelectedIndex(
    tapX: Float,
    canvasWidth: Float,
    leftPadding: Float,
    rightPadding: Float,
    maxIndex: Int,
): Int {
    if (maxIndex <= 0) return 0
    val chartWidth = (canvasWidth - leftPadding - rightPadding).coerceAtLeast(1f)
    val clamped = (tapX - leftPadding).coerceIn(0f, chartWidth)
    return ((clamped / chartWidth) * maxIndex).roundToInt().coerceIn(0, maxIndex)
}
