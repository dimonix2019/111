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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MONTHLY_BAR_MIN_SLOT_DP = 52f
private const val MONTHLY_BAR_CHART_LEFT_PADDING = 48f
private const val MONTHLY_BAR_CHART_RIGHT_PADDING = 12f
private const val MONTHLY_BAR_CHART_TOP_PADDING = 28f
private const val MONTHLY_BAR_CHART_BOTTOM_PADDING = 32f

private val plotBackgroundColor = Color(0xFFF3F3F3)
private val gridLineColor = Color(0xFFD8D8D8)
private val axisLineColor = Color(0xFFBDBDBD)
private val axisTextColor = Color(0xFF616161)
private val barPositiveColor = Color(0xFF4285F4)
private val barNegativeColor = Color(0xFFEF5350)
private val percentLabelColor = Color(0xFF424242)
private val monthLabelColor = Color(0xFF616161)

@Composable
internal fun StrategyTestMonthlyReturnBarChartCard(
    bars: List<StrategyTestMonthlyReturnBar>,
    notionalRub: Double,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 240,
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
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
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
    chartHeightDp: Int = 240,
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
                .horizontalScroll(scrollState),
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

    drawRect(
        color = plotBackgroundColor,
        topLeft = Offset(left, top),
        size = Size(plotW, plotH),
    )

    val pnlValues = bars.map { it.pnlRub }
    val rawMin = pnlValues.minOrNull() ?: 0.0
    val rawMax = pnlValues.maxOrNull() ?: 0.0
    val yMin = if (rawMin < 0.0) rawMin else 0.0
    val yMax = if (rawMax > 0.0) rawMax else 0.0
    val (axisMin, axisMax, yTicks) = buildNiceRubAxis(yMin, yMax, tickCount = 5)
    val axisSpan = (axisMax - axisMin).coerceAtLeast(1.0)

    fun yForPnl(v: Double): Float {
        val rel = ((v - axisMin) / axisSpan).toFloat().coerceIn(0f, 1f)
        return top + plotH * (1f - rel)
    }

    val zeroY = yForPnl(0.0)

    val axisLabelPaint = Paint().apply {
        color = axisTextColor.toArgb()
        textSize = 10.sp.toPx()
        textAlign = Paint.Align.RIGHT
        isAntiAlias = true
    }
    val monthPaint = Paint().apply {
        color = monthLabelColor.toArgb()
        textSize = 10.sp.toPx()
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    val percentPaint = Paint().apply {
        color = percentLabelColor.toArgb()
        textSize = 10.sp.toPx()
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    yTicks.forEach { tick ->
        val y = yForPnl(tick)
        drawLine(
            color = gridLineColor,
            start = Offset(left, y),
            end = Offset(left + plotW, y),
            strokeWidth = 1f,
        )
        drawContext.canvas.nativeCanvas.drawText(
            formatRubAxisValueCompact(tick),
            left - 8f,
            y + 4f,
            axisLabelPaint,
        )
    }

    drawLine(
        color = axisLineColor,
        start = Offset(left, top),
        end = Offset(left, top + plotH),
        strokeWidth = 1f,
    )
    drawLine(
        color = axisLineColor,
        start = Offset(left, top + plotH),
        end = Offset(left + plotW, top + plotH),
        strokeWidth = 1f,
    )
    if (axisMin < 0.0 && axisMax > 0.0) {
        drawLine(
            color = axisLineColor,
            start = Offset(left, zeroY),
            end = Offset(left + plotW, zeroY),
            strokeWidth = 1f,
        )
    }

    val slotW = plotW / n.coerceAtLeast(1)
    val barW = slotW * 0.48f

    bars.forEachIndexed { index, bar ->
        val cx = left + slotW * (index + 0.5f)
        val pnl = bar.pnlRub
        val yValue = yForPnl(pnl)
        val barColor = if (pnl >= 0.0) barPositiveColor else barNegativeColor

        if (pnl >= 0.0) {
            drawRect(
                color = barColor,
                topLeft = Offset(cx - barW / 2f, yValue),
                size = Size(barW, (zeroY - yValue).coerceAtLeast(if (pnl == 0.0) 0f else 2f)),
            )
        } else {
            drawRect(
                color = barColor,
                topLeft = Offset(cx - barW / 2f, zeroY),
                size = Size(barW, (yValue - zeroY).coerceAtLeast(2f)),
            )
        }

        val percentLabel = formatBarPercentLabel(bar.returnPercent)
        val labelY = if (pnl >= 0.0) yValue - 8f else yValue + 14f
        drawContext.canvas.nativeCanvas.drawText(
            percentLabel,
            cx,
            labelY,
            percentPaint,
        )

        drawContext.canvas.nativeCanvas.drawText(
            bar.label,
            cx,
            top + plotH + 16f,
            monthPaint,
        )
    }
}

/** Подпись над столбиком: «40%» от номинала 100k при PnL 40k. */
internal fun formatBarPercentLabel(returnPercent: Double): String =
    "${returnPercent.roundToInt()}%"

internal fun buildNiceRubAxis(
    rawMin: Double,
    rawMax: Double,
    tickCount: Int = 5,
): Triple<Double, Double, List<Double>> {
    val minV = if (rawMin < 0.0) rawMin else 0.0
    val maxV = if (rawMax > 0.0) rawMax else 0.0
    if (maxV <= minV) {
        val step = 10_000.0
        val ticks = (0 until tickCount).map { it * step }
        return Triple(0.0, step * (tickCount - 1), ticks)
    }
    val span = maxV - minV
    val roughStep = span / (tickCount - 1).coerceAtLeast(1)
    val step = niceStep(roughStep)
    val axisMin = if (minV < 0.0) floor(minV / step) * step else 0.0
    val axisMax = ceil(maxV / step) * step
    val ticks = mutableListOf<Double>()
    var t = axisMin
    while (t <= axisMax + step * 0.001) {
        ticks += t
        t += step
    }
    return Triple(axisMin, axisMax, ticks)
}

private fun niceStep(rough: Double): Double {
    if (rough <= 0.0) return 1.0
    val exp = floor(log10(rough)).toInt()
    val base = 10.0.pow(exp)
    val f = rough / base
    val niceF = when {
        f <= 1.0 -> 1.0
        f <= 2.0 -> 2.0
        f <= 5.0 -> 5.0
        else -> 10.0
    }
    return niceF * base
}

private fun formatRubAxisValueCompact(value: Double): String {
    val a = abs(value)
    return when {
        a >= 1_000_000 -> String.format(Locale.US, "%.0fM", value / 1_000_000.0)
        a >= 10_000 -> String.format(Locale.US, "%.0fk", value / 1_000.0)
        else -> String.format(Locale.US, "%.0f", value)
    }
}
