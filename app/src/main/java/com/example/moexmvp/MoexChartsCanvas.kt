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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun LineChart(
    series: List<ChartSeries>,
    yTicks: List<Double>,
    xTicks: List<XAxisTick>,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    referenceLines: List<ChartReferenceLine> = emptyList(),
    pointMarkers: List<ChartPointMarker> = emptyList(),
    modifier: Modifier = Modifier
) {
    if (series.isEmpty() || series.all { it.values.isEmpty() }) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No chart data", color = Color(0xFFD7E3F4))
        }
        return
    }

    val allValues = series.flatMap { it.values }
    val min = yTicks.minOrNull() ?: allValues.minOrNull() ?: 0.0
    val max = yTicks.maxOrNull() ?: allValues.maxOrNull() ?: 1.0
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
    val leftPadding = 16f
    val rightPadding = 16f
    val topPadding = 12f
    val bottomPadding = 60f

    Canvas(
        modifier = modifier
            .background(Color(0xFF0F1722), RoundedCornerShape(8.dp))
            .pointerInput(series) {
                detectTapGestures { tapOffset ->
                    val maxIndex = series.maxOfOrNull { it.values.lastIndex }?.coerceAtLeast(0) ?: 0
                    onSelectIndex(
                        resolveSelectedIndex(
                            tapX = tapOffset.x,
                            canvasWidth = size.width.toFloat(),
                            leftPadding = leftPadding,
                            rightPadding = rightPadding,
                            maxIndex = maxIndex
                        )
                    )
                }
            }
    ) {
        val w = size.width - leftPadding - rightPadding
        val h = size.height - topPadding - bottomPadding

        val maxIndex = series.maxOfOrNull { it.values.lastIndex }?.coerceAtLeast(0) ?: 0
        fun xForIndex(index: Int): Float {
            return if (maxIndex == 0) {
                leftPadding + (w / 2f)
            } else {
                leftPadding + ((index.toFloat() / maxIndex) * w)
            }
        }

        yTicks.forEach { tick ->
            val y = topPadding + h - (((tick - min) / range).toFloat() * h)
            drawLine(
                color = Color(0xFF30455A),
                start = Offset(leftPadding, y),
                end = Offset(leftPadding + w, y),
                strokeWidth = 1.5f
            )
        }
        referenceLines.forEach { reference ->
            val y = topPadding + h - (((reference.value - min) / range).toFloat() * h)
            drawLine(
                color = reference.color,
                start = Offset(leftPadding, y),
                end = Offset(leftPadding + w, y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(reference.dashOnPx, reference.dashOffPx)
                )
            )
        }

        xTicks.forEach { tick ->
            val x = xForIndex(tick.index)
            drawLine(
                color = Color(0xFF2A3D50),
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + h),
                strokeWidth = 1.5f
            )
        }

        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(leftPadding, topPadding + h),
            end = Offset(leftPadding + w, topPadding + h),
            strokeWidth = 2f
        )
        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(leftPadding, topPadding),
            end = Offset(leftPadding, topPadding + h),
            strokeWidth = 2f
        )
        xTicks.forEach { tick ->
            val x = xForIndex(tick.index)
            drawLine(
                color = Color(0xFF8AA6C1),
                start = Offset(x, topPadding + h),
                end = Offset(x, topPadding + h + 8f),
                strokeWidth = 1.5f
            )
        }

        val selected = selectedIndex?.coerceIn(0, maxIndex)
        selected?.let { idx ->
            val x = xForIndex(idx)
            drawLine(
                color = Color(0xFF90CAF9),
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + h),
                strokeWidth = 2f
            )
        }

        series.forEach { line ->
            val values = line.values
            if (values.isEmpty()) return@forEach
            if (values.size == 1) {
                val y = topPadding + h - (((values.first() - min) / range).toFloat() * h)
                drawCircle(
                    color = line.color,
                    radius = 6f,
                    center = Offset(leftPadding + (w / 2f), y)
                )
                return@forEach
            }

            val path = Path()
            val localMaxIndex = values.lastIndex.coerceAtLeast(1)
            values.forEachIndexed { index, value ->
                val x = leftPadding + (index.toFloat() / localMaxIndex) * w
                val y = topPadding + h - (((value - min) / range).toFloat() * h)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = line.color,
                style = Stroke(width = line.lineWidth, cap = StrokeCap.Round)
            )

            selected?.let { idx ->
                if (idx <= values.lastIndex) {
                    val x = xForIndex(idx)
                    val y = topPadding + h - (((values[idx] - min) / range).toFloat() * h)
                    drawCircle(
                        color = line.color,
                        radius = 5f,
                        center = Offset(x, y)
                    )
                }
            }
        }
        pointMarkers.forEach { marker ->
            if (marker.index < 0 || marker.index > maxIndex) return@forEach
            val x = xForIndex(marker.index)
            val y = topPadding + h - (((marker.value - min) / range).toFloat() * h)
            drawMarkerShape(
                shape = marker.shape,
                center = Offset(x, y),
                color = marker.color
            )
        }

        if (xTicks.isNotEmpty()) {
            val labelPaint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.rgb(221, 236, 255)
                textSize = 10.sp.toPx()
                textAlign = Paint.Align.RIGHT
            }
            xTicks.forEach { tick ->
                val x = xForIndex(tick.index)
                val y = topPadding + h + 34f
                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.rotate(-55f, x, y)
                drawContext.canvas.nativeCanvas.drawText(tick.label, x, y, labelPaint)
                drawContext.canvas.nativeCanvas.restore()
            }
        }
    }
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarkerShape(
    shape: ChartMarkerShape,
    center: Offset,
    color: Color
) {
    val radius = 9f
    val halfBase = 8f
    val halfHeight = 9f
    when (shape) {
        ChartMarkerShape.Circle -> drawCircle(color = color, radius = radius, center = center)
        ChartMarkerShape.TriangleUp -> {
            val path = Path().apply {
                moveTo(center.x, center.y - halfHeight)
                lineTo(center.x - halfBase, center.y + halfHeight)
                lineTo(center.x + halfBase, center.y + halfHeight)
                close()
            }
            drawPath(path = path, color = color)
        }
        ChartMarkerShape.TriangleDown -> {
            val path = Path().apply {
                moveTo(center.x, center.y + halfHeight)
                lineTo(center.x - halfBase, center.y - halfHeight)
                lineTo(center.x + halfBase, center.y - halfHeight)
                close()
            }
            drawPath(path = path, color = color)
        }
        ChartMarkerShape.Diamond -> {
            val path = Path().apply {
                moveTo(center.x, center.y - halfHeight)
                lineTo(center.x - halfBase, center.y)
                lineTo(center.x, center.y + halfHeight)
                lineTo(center.x + halfBase, center.y)
                close()
            }
            drawPath(path = path, color = color)
        }
    }
}

@Composable
internal fun CandlestickChart(
    candles: List<CandlePoint>,
    yTicks: List<Double>,
    xTicks: List<XAxisTick>,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (candles.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No chart data", color = Color(0xFFD7E3F4))
        }
        return
    }

    val min = yTicks.minOrNull() ?: candles.minOfOrNull { it.low } ?: 0.0
    val max = yTicks.maxOrNull() ?: candles.maxOfOrNull { it.high } ?: 1.0
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
    val leftPadding = 16f
    val rightPadding = 16f
    val topPadding = 12f
    val bottomPadding = 60f

    Canvas(
        modifier = modifier
            .background(Color(0xFF0F1722), RoundedCornerShape(8.dp))
            .pointerInput(candles) {
                detectTapGestures { tapOffset ->
                    onSelectIndex(
                        resolveSelectedIndex(
                            tapX = tapOffset.x,
                            canvasWidth = size.width.toFloat(),
                            leftPadding = leftPadding,
                            rightPadding = rightPadding,
                            maxIndex = candles.lastIndex.coerceAtLeast(0)
                        )
                    )
                }
            }
    ) {
        val w = size.width - leftPadding - rightPadding
        val h = size.height - topPadding - bottomPadding
        val maxIndex = candles.lastIndex.coerceAtLeast(0)
        fun xForIndex(index: Int): Float {
            return if (maxIndex == 0) {
                leftPadding + (w / 2f)
            } else {
                leftPadding + ((index.toFloat() / maxIndex) * w)
            }
        }

        yTicks.forEach { tick ->
            val y = topPadding + h - (((tick - min) / range).toFloat() * h)
            drawLine(
                color = Color(0xFF30455A),
                start = Offset(leftPadding, y),
                end = Offset(leftPadding + w, y),
                strokeWidth = 1.5f
            )
        }

        xTicks.forEach { tick ->
            val x = xForIndex(tick.index)
            drawLine(
                color = Color(0xFF2A3D50),
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + h),
                strokeWidth = 1.5f
            )
        }

        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(leftPadding, topPadding + h),
            end = Offset(leftPadding + w, topPadding + h),
            strokeWidth = 2f
        )
        drawLine(
            color = Color(0xFF8AA6C1),
            start = Offset(leftPadding, topPadding),
            end = Offset(leftPadding, topPadding + h),
            strokeWidth = 2f
        )

        val candleWidth = max(4f, min(14f, (w / (candles.size + 1)) * 0.7f))
        candles.forEachIndexed { index, candle ->
            val x = xForIndex(index)
            val openY = topPadding + h - (((candle.open - min) / range).toFloat() * h)
            val highY = topPadding + h - (((candle.high - min) / range).toFloat() * h)
            val lowY = topPadding + h - (((candle.low - min) / range).toFloat() * h)
            val closeY = topPadding + h - (((candle.close - min) / range).toFloat() * h)
            val rising = candle.close >= candle.open
            val color = if (rising) Color(0xFF69F0AE) else Color(0xFFFF5252)

            drawLine(
                color = color,
                start = Offset(x, highY),
                end = Offset(x, lowY),
                strokeWidth = 2f
            )

            val bodyTop = min(openY, closeY)
            val bodyHeight = max(abs(closeY - openY), 2f)
            drawRect(
                color = color,
                topLeft = Offset(x - candleWidth / 2f, bodyTop),
                size = Size(candleWidth, bodyHeight)
            )
        }

        val selected = selectedIndex?.coerceIn(0, maxIndex)
        selected?.let { idx ->
            val x = xForIndex(idx)
            drawLine(
                color = Color(0xFF90CAF9),
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + h),
                strokeWidth = 2f
            )
        }

        if (xTicks.isNotEmpty()) {
            val labelPaint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.rgb(221, 236, 255)
                textSize = 10.sp.toPx()
                textAlign = Paint.Align.RIGHT
            }
            xTicks.forEach { tick ->
                val x = xForIndex(tick.index)
                val y = topPadding + h + 34f
                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.rotate(-55f, x, y)
                drawContext.canvas.nativeCanvas.drawText(tick.label, x, y, labelPaint)
                drawContext.canvas.nativeCanvas.restore()
            }
        }
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
