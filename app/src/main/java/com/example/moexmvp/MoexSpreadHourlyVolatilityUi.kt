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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

@Composable
internal fun SpreadHourlyVolatilityChartCard(
    report: SpreadHourlyVolatilityReport,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 132,
) {
    val tradingHours = remember { spreadHourlyVolatilityTradingHoursRange().toList() }
    val displayBars = remember(report) {
        filterSpreadHourlyVolatilityForDisplay(report.bars)
            .filter { it.deltaSampleCount >= 2 && it.volatility > 0.0 }
    }
    val yRange = remember(displayBars) { spreadVolatilityDisplayYRange(displayBars) }
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
        if (displayBars.isEmpty() || yRange == null) {
            Text(
                text = "Недостаточно соседних 15м баров для почасовой σ",
                color = Color(0xFF757575),
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            val (yMin, yMax) = yRange
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
                val slotCount = tradingHours.size.coerceAtLeast(1)
                val slotW = plotW / slotCount
                val barW = (slotW * 0.72f).coerceAtLeast(2f)

                val yTicks = buildSpreadVolatilityYTicks(yMin, yMax)
                val axisMax = yTicks.lastOrNull() ?: yMax

                fun yForValue(value: Double): Float {
                    val range = (axisMax - yMin).takeIf { it > 0.0 } ?: 1.0
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
                    for (hour in listOf(8, 10, 12, 14, 16, 18, 21)) {
                        val slotIndex = tradingHours.indexOf(hour)
                        if (slotIndex < 0) continue
                        val xCenter = leftPad + slotW * slotIndex + slotW / 2f
                        drawText(
                            String.format(Locale.US, "%02d", hour),
                            xCenter - 12f,
                            size.height - 4f,
                            labelPaint,
                        )
                    }
                }

                for (bar in displayBars) {
                    val slotIndex = tradingHours.indexOf(bar.hour)
                    if (slotIndex < 0) continue
                    val xCenter = leftPad + slotW * slotIndex + slotW / 2f
                    val top = yForValue(bar.volatility)
                    val bottom = yForValue(yMin)
                    val barHeight = (bottom - top).coerceAtLeast(2f)
                    val span = (axisMax - yMin).takeIf { it > 0.0 } ?: 1.0
                    val intensity = ((bar.volatility - yMin) / span).toFloat().coerceIn(0.15f, 1f)
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
        }
        Text(
            text = "Ось Y: σ |Δspread%| между 15м барами · ось X: час MSK (8–23)",
            color = Color(0xFF757575),
            fontSize = 9.sp,
        )
    }
}

internal fun spreadVolatilityDisplayYRange(
    visibleBars: List<SpreadHourlyVolatilityBar>,
): Pair<Double, Double>? {
    if (visibleBars.isEmpty()) return null
    val rawMin = visibleBars.minOf { it.volatility }
    val rawMax = visibleBars.maxOf { it.volatility }
    if (rawMax <= 0.0) return null
    val yMax = rawMax
    val yMin = if (rawMax - rawMin < 1e-9) {
        max(0.0, rawMax * 0.85)
    } else {
        rawMin
    }
    return if (yMax > yMin) yMin to yMax else null
}

internal fun buildSpreadVolatilityYTicks(minValue: Double, maxValue: Double): List<Double> {
    if (maxValue <= minValue) return listOf(minValue, maxValue)
    val span = maxValue - minValue
    val step = when {
        span <= 0.02 -> 0.005
        span <= 0.05 -> 0.01
        span <= 0.15 -> 0.02
        span <= 0.4 -> 0.05
        else -> 0.1
    }
    val start = floor(minValue / step) * step
    val top = ceil(maxValue / step - 1e-12) * step
    val ticks = mutableListOf<Double>()
    var v = start
    while (v <= top + step * 0.001) {
        ticks.add(v)
        v += step
    }
    if (ticks.isEmpty() || ticks.last() < maxValue - 1e-9) {
        ticks.add(top)
    }
    return ticks.distinct().sorted()
}
