package com.example.moexmvp

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private const val MONTHLY_BAR_MIN_SLOT_DP = 42f
private const val MONTHLY_BAR_CHART_LEFT_PADDING = 52f
private const val MONTHLY_BAR_CHART_RIGHT_PADDING = 10f
private const val MONTHLY_BAR_CHART_TOP_PADDING = 12f
private const val MONTHLY_BAR_CHART_BOTTOM_PADDING = 56f

@Composable
internal fun StrategyTestMonthlyReturnBarChartCard(
    bars: List<StrategyTestMonthlyReturnBar>,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 200,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF252525), RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Text(
            text = "Доходность по месяцам (% от номинала)",
            color = Color(0xFFE0E0E0),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        StrategyTestMonthlyReturnBarChart(
            bars = bars,
            chartHeightDp = chartHeightDp,
        )
    }
}

@Composable
internal fun StrategyTestMonthlyReturnBarChart(
    bars: List<StrategyTestMonthlyReturnBar>,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 200,
) {
    if (bars.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(chartHeightDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Нет данных для графика", color = Color(0xFF9E9E9E), fontSize = 11.sp)
        }
        return
    }

    val scrollState = rememberScrollState()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val minChartWidthDp = max(
            constraints.maxWidth.toFloat(),
            bars.size * MONTHLY_BAR_MIN_SLOT_DP + MONTHLY_BAR_CHART_LEFT_PADDING + MONTHLY_BAR_CHART_RIGHT_PADDING,
        )
        Canvas(
            modifier = Modifier
                .width(minChartWidthDp.dp)
                .height(chartHeightDp.dp)
                .horizontalScroll(scrollState)
                .background(Color(0xFF0F1722), RoundedCornerShape(6.dp)),
        ) {
            drawMonthlyReturnBars(bars)
        }
    }
}

private fun DrawScope.drawMonthlyReturnBars(bars: List<StrategyTestMonthlyReturnBar>) {
    val n = bars.size
    val left = MONTHLY_BAR_CHART_LEFT_PADDING
    val right = MONTHLY_BAR_CHART_RIGHT_PADDING
    val top = MONTHLY_BAR_CHART_TOP_PADDING
    val bottom = MONTHLY_BAR_CHART_BOTTOM_PADDING
    val plotW = (size.width - left - right).coerceAtLeast(1f)
    val plotH = (size.height - top - bottom).coerceAtLeast(1f)

    val values = bars.map { it.returnPercent }
    val rawMin = values.minOrNull() ?: 0.0
    val rawMax = values.maxOrNull() ?: 0.0
    val yMin = if (rawMin < 0.0) rawMin else 0.0
    val yMax = if (rawMax > 0.0) rawMax else 0.0
    val span = (yMax - yMin).coerceAtLeast(1.0)
    val pad = span * 0.12
    val axisMin = yMin - if (yMin < 0.0) pad else 0.0
    val axisMax = yMax + pad
    val axisSpan = (axisMax - axisMin).coerceAtLeast(1.0)

    fun yForValue(v: Double): Float {
        val rel = ((v - axisMin) / axisSpan).toFloat().coerceIn(0f, 1f)
        return top + plotH * (1f - rel)
    }

    val zeroY = yForValue(0.0)
    val yTicks = buildYTicks(axisMin, axisMax, count = 5)

    val labelPaint = Paint().apply {
        color = android.graphics.Color.rgb(215, 227, 244)
        textSize = 10.sp.toPx()
        textAlign = Paint.Align.RIGHT
        isAntiAlias = true
    }
    val monthPaint = Paint().apply {
        color = android.graphics.Color.rgb(215, 227, 244)
        textSize = 10.sp.toPx()
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    val valuePaint = Paint().apply {
        textSize = 9.sp.toPx()
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    yTicks.forEach { tick ->
        val y = yForValue(tick)
        drawLine(
            color = Color(0xFF30455A),
            start = Offset(left, y),
            end = Offset(left + plotW, y),
            strokeWidth = 1f,
        )
        drawContext.canvas.nativeCanvas.drawText(
            formatPercentAxisValue(tick),
            left - 6f,
            y + 4f,
            labelPaint,
        )
    }

    drawLine(
        color = Color(0xFF8AA6C1),
        start = Offset(left, top),
        end = Offset(left, top + plotH),
        strokeWidth = 1.5f,
    )
    drawLine(
        color = Color(0xFF8AA6C1),
        start = Offset(left, top + plotH),
        end = Offset(left + plotW, top + plotH),
        strokeWidth = 1.5f,
    )
    drawLine(
        color = Color(0xFF546E7A),
        start = Offset(left, zeroY),
        end = Offset(left + plotW, zeroY),
        strokeWidth = 1.5f,
    )

    val slotW = plotW / n.coerceAtLeast(1)
    val barW = slotW * 0.62f

    bars.forEachIndexed { index, bar ->
        val cx = left + slotW * (index + 0.5f)
        val value = bar.returnPercent
        val yValue = yForValue(value)
        val topY = minOf(zeroY, yValue)
        val barH = abs(yValue - zeroY).coerceAtLeast(if (value == 0.0) 0f else 2f)
        val barColor = if (value >= 0.0) Color(0xFF81C784) else Color(0xFFE57373)
        drawRoundRect(
            color = barColor.copy(alpha = 0.92f),
            topLeft = Offset(cx - barW / 2f, topY),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(3f, 3f),
        )

        valuePaint.color = barColor.toArgb()
        val labelY = if (value >= 0.0) topY - 4f else topY + barH + 12f
        if (barH >= 6f || value != 0.0) {
            drawContext.canvas.nativeCanvas.drawText(
                formatPercentAxisValue(value),
                cx,
                labelY,
                valuePaint,
            )
        }

        val monthY = top + plotH + 28f
        drawContext.canvas.nativeCanvas.drawText(
            bar.label,
            cx,
            monthY,
            monthPaint,
        )
        if (bar.tradeCount > 0) {
            monthPaint.textSize = 8.sp.toPx()
            monthPaint.color = android.graphics.Color.rgb(158, 158, 158)
            drawContext.canvas.nativeCanvas.drawText(
                "n=${bar.tradeCount}",
                cx,
                monthY + 12f,
                monthPaint,
            )
            monthPaint.textSize = 10.sp.toPx()
            monthPaint.color = android.graphics.Color.rgb(215, 227, 244)
        }
    }
}

private fun formatPercentAxisValue(v: Double): String =
    String.format(Locale.US, "%+.1f%%", v)
