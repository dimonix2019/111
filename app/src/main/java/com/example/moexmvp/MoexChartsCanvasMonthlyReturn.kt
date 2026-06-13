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
import kotlin.math.roundToInt

private const val MONTHLY_BAR_MIN_SLOT_DP = 44f
private const val MONTHLY_BAR_CHART_LEFT_PADDING = 58f
private const val MONTHLY_BAR_CHART_RIGHT_PADDING = 10f
private const val MONTHLY_BAR_CHART_TOP_PADDING = 22f
private const val MONTHLY_BAR_CHART_BOTTOM_PADDING = 36f

@Composable
internal fun StrategyTestMonthlyReturnBarChartCard(
    bars: List<StrategyTestMonthlyReturnBar>,
    notionalRub: Double,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 220,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF252525), RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Text(
            text = "PnL по месяцам (₽) · над столбиком — % от ${"%.0f".format(Locale.US, notionalRub)} ₽",
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
    chartHeightDp: Int = 220,
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

    val pnlValues = bars.map { it.pnlRub }
    val rawMin = pnlValues.minOrNull() ?: 0.0
    val rawMax = pnlValues.maxOrNull() ?: 0.0
    val yMin = if (rawMin < 0.0) rawMin else 0.0
    val yMax = if (rawMax > 0.0) rawMax else 0.0
    val span = (yMax - yMin).coerceAtLeast(1_000.0)
    val pad = span * 0.12
    val axisMin = yMin - if (yMin < 0.0) pad else 0.0
    val axisMax = yMax + pad
    val axisSpan = (axisMax - axisMin).coerceAtLeast(1.0)

    fun yForPnl(v: Double): Float {
        val rel = ((v - axisMin) / axisSpan).toFloat().coerceIn(0f, 1f)
        return top + plotH * (1f - rel)
    }

    val zeroY = yForPnl(0.0)
    val yTicks = buildYTicks(axisMin, axisMax, count = 5)

    val axisLabelPaint = Paint().apply {
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
    val percentPaint = Paint().apply {
        textSize = 10.sp.toPx()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        isAntiAlias = true
    }

    yTicks.forEach { tick ->
        val y = yForPnl(tick)
        drawLine(
            color = Color(0xFF30455A),
            start = Offset(left, y),
            end = Offset(left + plotW, y),
            strokeWidth = 1f,
        )
        drawContext.canvas.nativeCanvas.drawText(
            formatRubAxisValueCompact(tick),
            left - 6f,
            y + 4f,
            axisLabelPaint,
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
        val pnl = bar.pnlRub
        val yValue = yForPnl(pnl)
        val topY = minOf(zeroY, yValue)
        val barH = abs(yValue - zeroY).coerceAtLeast(if (pnl == 0.0) 0f else 2f)
        val barColor = if (pnl >= 0.0) Color(0xFF81C784) else Color(0xFFE57373)
        drawRoundRect(
            color = barColor.copy(alpha = 0.92f),
            topLeft = Offset(cx - barW / 2f, topY),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(3f, 3f),
        )

        percentPaint.color = barColor.toArgb()
        val percentLabel = formatBarPercentLabel(bar.returnPercent)
        val labelY = if (pnl >= 0.0) topY - 6f else topY + barH + 14f
        drawContext.canvas.nativeCanvas.drawText(
            percentLabel,
            cx,
            labelY,
            percentPaint,
        )

        drawContext.canvas.nativeCanvas.drawText(
            bar.label,
            cx,
            top + plotH + 18f,
            monthPaint,
        )
    }
}

/** Подпись над столбиком: «40%» от номинала 100k при PnL 40k. */
internal fun formatBarPercentLabel(returnPercent: Double): String =
    "${returnPercent.roundToInt()}%"

private fun formatRubAxisValueCompact(value: Double): String {
    val a = abs(value)
    return when {
        a >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        a >= 10_000 -> String.format(Locale.US, "%.0fk", value / 1_000.0)
        else -> String.format(Locale.US, "%.0f", value)
    }
}
