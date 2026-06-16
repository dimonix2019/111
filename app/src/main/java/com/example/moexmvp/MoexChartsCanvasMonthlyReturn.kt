package com.example.moexmvp

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private val plotBackgroundColor = Color(0xFFF3F3F3)
private val gridLineColor = Color(0xFFD8D8D8)
private val axisLineColor = Color(0xFFBDBDBD)
private val axisTextColor = Color(0xFF616161)
private val barPositiveColor = Color(0xFF4285F4)
private val barNegativeColor = Color(0xFFEF5350)
private val percentLabelColor = Color(0xFF424242)
private val monthLabelColor = Color(0xFF616161)

private const val Y_AXIS_WIDTH_DP = 44
private const val PLOT_TOP_PADDING_DP = 8
private const val PLOT_BOTTOM_PADDING_DP = 4
private const val MONTH_LABEL_HEIGHT_DP = 18
private const val PERCENT_LABEL_HEIGHT_DP = 16

@Composable
internal fun StrategyTestMonthlyReturnBarChartCard(
    bars: List<StrategyTestMonthlyReturnBar>,
    notionalRub: Double,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 260,
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
    chartHeightDp: Int = 260,
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

    val pnlMin = bars.minOf { it.pnlRub }.let { if (it < 0.0) it else 0.0 }
    val pnlMax = bars.maxOf { it.pnlRub }.let { if (it > 0.0) it else 0.0 }
    val (axisMin, axisMax, yTicks) = remember(bars) {
        buildNiceRubAxis(pnlMin, pnlMax, tickCount = 5)
    }
    val axisSpan = (axisMax - axisMin).coerceAtLeast(1.0)
    val plotBodyHeightDp = chartHeightDp - MONTH_LABEL_HEIGHT_DP - PERCENT_LABEL_HEIGHT_DP

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(plotBodyHeightDp.dp),
        ) {
            YAxisCanvas(
                axisMin = axisMin,
                axisMax = axisMax,
                yTicks = yTicks,
                modifier = Modifier
                    .width(Y_AXIS_WIDTH_DP.dp)
                    .fillMaxHeight(),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(plotBackgroundColor, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
            ) {
                PlotGridCanvas(
                    axisMin = axisMin,
                    axisMax = axisMax,
                    yTicks = yTicks,
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = PLOT_TOP_PADDING_DP.dp,
                            bottom = PLOT_BOTTOM_PADDING_DP.dp,
                            start = 2.dp,
                            end = 2.dp,
                        ),
                ) {
                    bars.forEach { bar ->
                        MonthlyBarColumn(
                            bar = bar,
                            axisMin = axisMin,
                            axisSpan = axisSpan,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Y_AXIS_WIDTH_DP.dp, top = 2.dp),
        ) {
            bars.forEach { bar ->
                Text(
                    text = bar.label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = monthLabelColor,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MonthlyBarColumn(
    bar: StrategyTestMonthlyReturnBar,
    axisMin: Double,
    axisSpan: Double,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatBarPercentLabel(bar.returnPercent),
            color = percentLabelColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.height(PERCENT_LABEL_HEIGHT_DP.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            MonthlyBarCanvas(
                pnlRub = bar.pnlRub,
                axisMin = axisMin,
                axisSpan = axisSpan,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun MonthlyBarCanvas(
    pnlRub: Double,
    axisMin: Double,
    axisSpan: Double,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val zeroFrac = ((0.0 - axisMin) / axisSpan).toFloat().coerceIn(0f, 1f)
        val valueFrac = ((pnlRub - axisMin) / axisSpan).toFloat().coerceIn(0f, 1f)
        val zeroY = size.height * (1f - zeroFrac)
        val valueY = size.height * (1f - valueFrac)
        val barW = size.width * 0.52f
        val barLeft = (size.width - barW) / 2f
        if (pnlRub >= 0.0) {
            val topY = minOf(zeroY, valueY)
            val barH = (zeroY - valueY).coerceAtLeast(if (pnlRub == 0.0) 0f else 3f)
            drawRect(
                color = barPositiveColor,
                topLeft = Offset(barLeft, topY),
                size = Size(barW, barH),
            )
        } else {
            val barH = (valueY - zeroY).coerceAtLeast(3f)
            drawRect(
                color = barNegativeColor,
                topLeft = Offset(barLeft, zeroY),
                size = Size(barW, barH),
            )
        }
    }
}

@Composable
private fun YAxisCanvas(
    axisMin: Double,
    axisMax: Double,
    yTicks: List<Double>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val axisSpan = (axisMax - axisMin).coerceAtLeast(1.0)
        val paint = Paint().apply {
            color = axisTextColor.toArgb()
            textSize = 10.sp.toPx()
            textAlign = Paint.Align.RIGHT
            isAntiAlias = true
        }
        yTicks.forEach { tick ->
            val frac = ((tick - axisMin) / axisSpan).toFloat().coerceIn(0f, 1f)
            val y = size.height * (1f - frac)
            drawContext.canvas.nativeCanvas.drawText(
                formatRubAxisValueCompact(tick),
                size.width - 4f,
                y + 4f,
                paint,
            )
        }
    }
}

@Composable
private fun PlotGridCanvas(
    axisMin: Double,
    axisMax: Double,
    yTicks: List<Double>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawPlotGrid(axisMin, axisMax, yTicks)
    }
}

private fun DrawScope.drawPlotGrid(
    axisMin: Double,
    axisMax: Double,
    yTicks: List<Double>,
) {
    val axisSpan = (axisMax - axisMin).coerceAtLeast(1.0)
    yTicks.forEach { tick ->
        val frac = ((tick - axisMin) / axisSpan).toFloat().coerceIn(0f, 1f)
        val y = size.height * (1f - frac)
        drawLine(
            color = gridLineColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
    }
    if (axisMin < 0.0 && axisMax > 0.0) {
        val zeroFrac = ((0.0 - axisMin) / axisSpan).toFloat()
        val zeroY = size.height * (1f - zeroFrac)
        drawLine(
            color = axisLineColor,
            start = Offset(0f, zeroY),
            end = Offset(size.width, zeroY),
            strokeWidth = 1.5f,
        )
    }
    drawLine(
        color = axisLineColor,
        start = Offset(0f, 0f),
        end = Offset(0f, size.height),
        strokeWidth = 1f,
    )
    drawLine(
        color = axisLineColor,
        start = Offset(0f, size.height),
        end = Offset(size.width, size.height),
        strokeWidth = 1f,
    )
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
