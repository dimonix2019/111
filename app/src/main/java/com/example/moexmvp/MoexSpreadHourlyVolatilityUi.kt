package com.example.moexmvp

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun SpreadHourlyVolatilityChartCard(
    report: SpreadHourlyVolatilityReport,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 132,
) {
    val visibleBars = remember(report) {
        report.bars.filter { it.deltaSampleCount >= 2 && it.volatility > 0.0 }
    }
    val maxVol = remember(visibleBars) {
        visibleBars.maxOfOrNull { it.volatility }?.takeIf { it > 0.0 } ?: 1.0
    }
    val subtitle = remember(report) { formatSpreadHourlyVolatilitySubtitle(report) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF171717), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Волатильность спреда по часам",
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            text = subtitle,
            color = Color(0xFF9FA8DA),
            fontSize = 10.sp,
            maxLines = 2,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeightDp.dp)
                .background(Color(0xFF0F1722), RoundedCornerShape(8.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            val leftPad = 34f
            val rightPad = 8f
            val topPad = 8f
            val bottomPad = 22f
            val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
            val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
            val slotW = plotW / 24f
            val barW = (slotW * 0.72f).coerceAtLeast(2f)

            val yTicks = buildSpreadVolatilityYTicks(maxVol)
            val yMax = yTicks.lastOrNull()?.coerceAtLeast(maxVol) ?: maxVol
            val yMin = 0.0

            fun yForValue(value: Double): Float {
                val range = (yMax - yMin).takeIf { it > 0.0 } ?: 1.0
                val rel = ((value - yMin) / range).toFloat().coerceIn(0f, 1f)
                return topPad + plotH * (1f - rel)
            }

            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#607D8B")
                textSize = 22f
            }
            for (tick in yTicks) {
                val y = yForValue(tick)
                drawLine(
                    color = Color(0xFF37474F),
                    start = Offset(leftPad, y),
                    end = Offset(size.width - rightPad, y),
                    strokeWidth = 1f,
                )
            }
            drawContext.canvas.nativeCanvas.apply {
                for (tick in yTicks) {
                    val y = yForValue(tick)
                    drawText(
                        String.format(Locale.US, "%.2f", tick),
                        2f,
                        y + 7f,
                        labelPaint,
                    )
                }
                for (hour in listOf(0, 3, 6, 9, 10, 12, 14, 16, 18, 21)) {
                    val xCenter = leftPad + slotW * hour + slotW / 2f
                    drawText(
                        String.format(Locale.US, "%02d", hour),
                        xCenter - 12f,
                        size.height - 4f,
                        labelPaint,
                    )
                }
            }

            for (bar in report.bars) {
                if (bar.volatility <= 0.0) continue
                val xCenter = leftPad + slotW * bar.hour + slotW / 2f
                val top = yForValue(bar.volatility)
                val bottom = yForValue(0.0)
                val barHeight = (bottom - top).coerceAtLeast(2f)
                val intensity = (bar.volatility / yMax).toFloat().coerceIn(0.15f, 1f)
                val color = Color(
                    red = 0.41f + 0.35f * intensity,
                    green = 0.82f - 0.25f * intensity,
                    blue = 0.68f - 0.35f * intensity,
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(xCenter - barW / 2f, top),
                    size = Size(barW, barHeight),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }
        }
        Text(
            text = "Ось Y: σ |Δspread%| между 15м барами · ось X: час MSK",
            color = Color(0xFF757575),
            fontSize = 9.sp,
        )
    }
}

private fun buildSpreadVolatilityYTicks(maxValue: Double): List<Double> {
    val safeMax = max(maxValue, 0.01)
    val step = when {
        safeMax <= 0.05 -> 0.01
        safeMax <= 0.15 -> 0.02
        safeMax <= 0.4 -> 0.05
        else -> 0.1
    }
    val top = ((safeMax / step).roundToInt() + 1) * step
    val ticks = mutableListOf<Double>()
    var v = 0.0
    while (v <= top + step * 0.01) {
        ticks.add(v)
        v += step
    }
    return ticks
}
