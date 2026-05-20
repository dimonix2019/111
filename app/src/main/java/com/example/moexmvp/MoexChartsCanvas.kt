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


@Composable
internal fun LineChart(
    series: List<ChartSeries>,
    yTicks: List<Double>,
    xTicks: List<XAxisTick>,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    referenceLines: List<ChartReferenceLine> = emptyList(),
    pointMarkers: List<ChartPointMarker> = emptyList(),
    modifier: Modifier = Modifier,
    enableZoomPan: Boolean = false,
    markerScale: Float = 1f,
    rightPlotPaddingPx: Float = 16f
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
    val rightPadding = rightPlotPaddingPx.coerceAtLeast(16f)
    val topPadding = 12f
    val bottomPadding = 60f

    val maxIndex = series.maxOfOrNull { it.values.lastIndex }?.coerceAtLeast(0) ?: 0

    var windowStart by remember(series) { mutableFloatStateOf(0f) }
    var windowWidth by remember(series) { mutableFloatStateOf(1f) }
    LaunchedEffect(series) {
        windowStart = 0f
        windowWidth = 1f
    }

    val wStart = if (enableZoomPan) windowStart else 0f
    val wWidth = if (enableZoomPan) windowWidth.coerceIn(CHART_ZOOM_MIN_WINDOW, 1f) else 1f

    fun fracForIndex(index: Int): Float =
        if (maxIndex <= 0) 0f else index / maxIndex.toFloat()

    fun chartW(pxWidth: Float): Float =
        (pxWidth - leftPadding - rightPadding).coerceAtLeast(1f)

    fun xForIndex(index: Int, pxWidth: Float): Float {
        val w = chartW(pxWidth)
        val frac = fracForIndex(index)
        val rel = (frac - wStart) / wWidth
        return leftPadding + rel * w
    }

    fun indexFromTapX(tapX: Float, pxWidth: Float): Int {
        if (maxIndex <= 0) return 0
        val w = chartW(pxWidth)
        val rel = ((tapX - leftPadding) / w).coerceIn(0f, 1f)
        val frac = wStart + rel * wWidth
        return (frac * maxIndex).roundToInt().coerceIn(0, maxIndex)
    }

    val chartModifier = modifier
        .background(Color(0xFF0F1722), RoundedCornerShape(8.dp))
        .then(
            if (enableZoomPan) {
                Modifier.pointerInput(series, maxIndex) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val chartWidthPx = chartW(size.width.toFloat())
                        if (chartWidthPx <= 1f) return@detectTransformGestures
                        if (kotlin.math.abs(zoom - 1f) > 0.001f) {
                            val newW = (windowWidth / zoom).coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
                            val centroidRel =
                                ((centroid.x - leftPadding) / chartWidthPx).coerceIn(0f, 1f)
                            val dataFracUnderCentroid = windowStart + centroidRel * windowWidth
                            var newStart = dataFracUnderCentroid - centroidRel * newW
                            newStart = newStart.coerceIn(0f, 1f - newW)
                            windowWidth = newW
                            windowStart = newStart
                        } else if (pan.x != 0f) {
                            val panFrac = -pan.x / chartWidthPx * windowWidth
                            windowStart = (windowStart + panFrac).coerceIn(0f, 1f - windowWidth)
                        }
                    }
                }
            } else {
                Modifier
            }
        )
        .pointerInput(series, wStart, wWidth, maxIndex, enableZoomPan) {
            detectTapGestures(
                onDoubleTap = if (enableZoomPan) {
                    { _: Offset ->
                        windowStart = 0f
                        windowWidth = 1f
                    }
                } else {
                    null
                },
                onTap = { tapOffset ->
                    onSelectIndex(
                        indexFromTapX(tapOffset.x, size.width.toFloat())
                    )
                }
            )
        }

    Canvas(modifier = chartModifier) {
        val w = chartW(size.width)
        val h = size.height - topPadding - bottomPadding

        fun xForIndexDraw(index: Int): Float = xForIndex(index, size.width)

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
        drawReferenceLineLabels(
            referenceLines = referenceLines,
            min = min,
            range = range,
            leftPadding = leftPadding,
            topPadding = topPadding,
            chartWidth = w,
            chartHeight = h
        )

        xTicks.forEach { tick ->
            val frac = fracForIndex(tick.index)
            if (frac < wStart - 0.02f || frac > wStart + wWidth + 0.02f) return@forEach
            val x = xForIndexDraw(tick.index)
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
            val frac = fracForIndex(tick.index)
            if (frac < wStart - 0.02f || frac > wStart + wWidth + 0.02f) return@forEach
            val x = xForIndexDraw(tick.index)
            drawLine(
                color = Color(0xFF8AA6C1),
                start = Offset(x, topPadding + h),
                end = Offset(x, topPadding + h + 8f),
                strokeWidth = 1.5f
            )
        }

        val selected = selectedIndex?.coerceIn(0, maxIndex)
        selected?.let { idx ->
            val x = xForIndexDraw(idx)
            drawLine(
                color = Color(0xFF90CAF9),
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + h),
                strokeWidth = 2f
            )
        }

        clipRect(
            left = leftPadding,
            top = topPadding,
            right = leftPadding + w,
            bottom = topPadding + h
        ) {
            series.forEach { line ->
                val values = line.values
                if (values.isEmpty()) return@forEach
                if (values.size == 1) {
                    val y = topPadding + h - (((values.first() - min) / range).toFloat() * h)
                    drawCircle(
                        color = line.color,
                        radius = 6f,
                        center = Offset(xForIndexDraw(0), y)
                    )
                    return@forEach
                }

            val path = Path()
            values.forEachIndexed { index, value ->
                    val x = xForIndexDraw(index)
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
                        val x = xForIndexDraw(idx)
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
                val frac = fracForIndex(marker.index)
                if (frac < wStart - 0.001f || frac > wStart + wWidth + 0.001f) return@forEach
                val x = xForIndexDraw(marker.index)
                val y = topPadding + h - (((marker.value - min) / range).toFloat() * h)
                drawMarkerShape(
                    shape = marker.shape,
                    center = Offset(x, y),
                    color = marker.color,
                    scale = markerScale
                )
            }
        }

        if (xTicks.isNotEmpty()) {
            val labelPaint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.rgb(221, 236, 255)
                textSize = 10.sp.toPx()
                textAlign = Paint.Align.RIGHT
            }
            xTicks.forEach { tick ->
                val frac = fracForIndex(tick.index)
                if (frac < wStart - 0.02f || frac > wStart + wWidth + 0.02f) return@forEach
                val x = xForIndexDraw(tick.index)
                val y = topPadding + h + 34f
                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.rotate(-55f, x, y)
                drawContext.canvas.nativeCanvas.drawText(tick.label, x, y, labelPaint)
                drawContext.canvas.nativeCanvas.restore()
            }
        }
    }
}

/** Подписи порогов Z у правого края линии (вход ±entry, выход ±exit). */
private fun DrawScope.drawReferenceLineLabels(
    referenceLines: List<ChartReferenceLine>,
    min: Double,
    range: Double,
    leftPadding: Float,
    topPadding: Float,
    chartWidth: Float,
    chartHeight: Float
) {
    if (referenceLines.isEmpty()) return
    val textSizePx = 9.sp.toPx()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        textAlign = Paint.Align.RIGHT
    }
    val labelInset = chartWidth * CHART_RIGHT_PLOT_PADDING_FRACTION
    val xRight = leftPadding + chartWidth - labelInset - 4f
    val yMin = topPadding + textSizePx * 0.85f
    val yMax = topPadding + chartHeight - 2f
    referenceLines.forEach { reference ->
        val lineY = topPadding + chartHeight - (((reference.value - min) / range).toFloat() * chartHeight)
        val textY = (lineY - 5f).coerceIn(yMin, yMax)
        paint.color = reference.color.copy(alpha = 0.92f).toArgb()
        drawContext.canvas.nativeCanvas.drawText(reference.label, xRight, textY, paint)
    }
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarkerShape(
    shape: ChartMarkerShape,
    center: Offset,
    color: Color,
    scale: Float = 1f
) {
    val outline = Color(0xFF0D1117)
    val strokeW = (2f * scale).coerceAtLeast(1.5f)
    val radius = 9f * scale
    val halfBase = 8f * scale
    val halfHeight = 9f * scale
    when (shape) {
        ChartMarkerShape.Circle -> {
            drawCircle(color = color, radius = radius, center = center)
            drawCircle(color = outline, radius = radius, center = center, style = Stroke(width = strokeW))
        }
        ChartMarkerShape.TriangleUp -> {
            val path = Path().apply {
                moveTo(center.x, center.y - halfHeight)
                lineTo(center.x - halfBase, center.y + halfHeight)
                lineTo(center.x + halfBase, center.y + halfHeight)
                close()
            }
            drawPath(path = path, color = color)
            drawPath(path = path, color = outline, style = Stroke(width = strokeW))
        }
        ChartMarkerShape.TriangleDown -> {
            val path = Path().apply {
                moveTo(center.x, center.y + halfHeight)
                lineTo(center.x - halfBase, center.y - halfHeight)
                lineTo(center.x + halfBase, center.y - halfHeight)
                close()
            }
            drawPath(path = path, color = color)
            drawPath(path = path, color = outline, style = Stroke(width = strokeW))
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
            drawPath(path = path, color = outline, style = Stroke(width = strokeW))
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
    modifier: Modifier = Modifier,
    referenceLines: List<ChartReferenceLine> = emptyList(),
    pointMarkers: List<ChartPointMarker> = emptyList(),
    enableZoomPan: Boolean = false,
    markerScale: Float = 1f,
    rightPlotPaddingPx: Float = 16f,
    rightPlotPaddingFraction: Float = 0f,
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f
) {
    if (candles.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No chart data", color = Color(0xFFD7E3F4))
        }
        return
    }

    var layoutWidthPx by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        modifier = modifier.onSizeChanged { layoutWidthPx = it.width.toFloat() }
    ) {
        val density = LocalDensity.current
        val plotWidthPx = layoutWidthPx.takeIf { it > 0f }
            ?: with(density) { maxWidth.toPx() }
        val rightPadding = remember(plotWidthPx, rightPlotPaddingPx, rightPlotPaddingFraction) {
            chartRightPlotPaddingPx(
                plotWidthPx = plotWidthPx,
                minPx = rightPlotPaddingPx.coerceAtLeast(16f),
                fraction = rightPlotPaddingFraction
            )
        }

        val min = yTicks.minOrNull() ?: candles.minOfOrNull { it.low } ?: 0.0
        val max = yTicks.maxOrNull() ?: candles.maxOfOrNull { it.high } ?: 1.0
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        val leftPadding = 16f
        val topPadding = 12f
        val bottomPadding = 60f
        val maxIndex = candles.lastIndex.coerceAtLeast(0)

        var windowStart by remember(candles) { mutableFloatStateOf(0f) }
        var windowWidth by remember(candles) { mutableFloatStateOf(1f) }
        LaunchedEffect(candles, initialWindowWidth, initialWindowStart, enableZoomPan) {
            if (enableZoomPan) {
                windowWidth = initialWindowWidth.coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
                windowStart = initialWindowStart.coerceIn(0f, 1f - windowWidth)
            } else {
                windowStart = 0f
                windowWidth = 1f
            }
        }

        val wStart = if (enableZoomPan) windowStart else 0f
        val wWidth = if (enableZoomPan) windowWidth.coerceIn(CHART_ZOOM_MIN_WINDOW, 1f) else 1f

        fun fracForIndex(index: Int): Float =
            if (maxIndex <= 0) 0f else index / maxIndex.toFloat()

        fun chartW(pxWidth: Float): Float =
            (pxWidth - leftPadding - rightPadding).coerceAtLeast(1f)

        fun xForIndex(index: Int, pxWidth: Float): Float {
            val rel = (fracForIndex(index) - wStart) / wWidth
            return leftPadding + rel * chartW(pxWidth)
        }

        fun indexFromTapX(tapX: Float, pxWidth: Float): Int {
            if (maxIndex <= 0) return 0
            val rel = ((tapX - leftPadding) / chartW(pxWidth)).coerceIn(0f, 1f)
            val frac = wStart + rel * wWidth
            return (frac * maxIndex).roundToInt().coerceIn(0, maxIndex)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F1722), RoundedCornerShape(8.dp))
            .then(
                if (enableZoomPan) {
                    Modifier.pointerInput(candles, maxIndex, rightPadding) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val chartWidthPx = chartW(size.width.toFloat())
                            if (chartWidthPx <= 1f) return@detectTransformGestures
                            if (kotlin.math.abs(zoom - 1f) > 0.001f) {
                                val newW = (windowWidth / zoom).coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
                                val centroidRel =
                                    ((centroid.x - leftPadding) / chartWidthPx).coerceIn(0f, 1f)
                                val dataFracUnderCentroid = windowStart + centroidRel * windowWidth
                                var newStart = dataFracUnderCentroid - centroidRel * newW
                                newStart = newStart.coerceIn(0f, 1f - newW)
                                windowWidth = newW
                                windowStart = newStart
                            } else if (pan.x != 0f) {
                                val panFrac = -pan.x / chartWidthPx * windowWidth
                                windowStart = (windowStart + panFrac).coerceIn(0f, 1f - windowWidth)
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .pointerInput(candles, wStart, wWidth, maxIndex, enableZoomPan, rightPadding) {
                detectTapGestures(
                    onDoubleTap = if (enableZoomPan) {
                        { _: Offset ->
                            windowStart = 0f
                            windowWidth = 1f
                        }
                    } else {
                        null
                    },
                    onTap = { tapOffset ->
                    onSelectIndex(
                        indexFromTapX(tapOffset.x, size.width.toFloat())
                    )
                    }
                )
            }
        ) {
        val w = chartW(size.width)
        val h = size.height - topPadding - bottomPadding
        fun xForIndexDraw(index: Int): Float = xForIndex(index, size.width)

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
        drawReferenceLineLabels(
            referenceLines = referenceLines,
            min = min,
            range = range,
            leftPadding = leftPadding,
            topPadding = topPadding,
            chartWidth = w,
            chartHeight = h
        )

        xTicks.forEach { tick ->
            val frac = fracForIndex(tick.index)
            if (frac < wStart - 0.02f || frac > wStart + wWidth + 0.02f) return@forEach
            val x = xForIndexDraw(tick.index)
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

        val visibleCount = (candles.size * wWidth).coerceAtLeast(1f)
        val candleWidth = max(2f, min(14f, (w / (visibleCount + 1f)) * 0.7f))
        clipRect(
            left = leftPadding,
            top = topPadding,
            right = leftPadding + w,
            bottom = topPadding + h
        ) {
            candles.forEachIndexed { index, candle ->
                val frac = fracForIndex(index)
                if (frac < wStart - 0.001f || frac > wStart + wWidth + 0.001f) return@forEachIndexed
                val x = xForIndexDraw(index)
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

            pointMarkers.forEach { marker ->
                if (marker.index < 0 || marker.index > maxIndex) return@forEach
                val frac = fracForIndex(marker.index)
                if (frac < wStart - 0.001f || frac > wStart + wWidth + 0.001f) return@forEach
                val x = xForIndexDraw(marker.index)
                val y = topPadding + h - (((marker.value - min) / range).toFloat() * h)
                drawMarkerShape(
                    shape = marker.shape,
                    center = Offset(x, y),
                    color = marker.color,
                    scale = markerScale
                )
            }
        }

        pointMarkers.forEach { marker ->
            if (marker.index < 0 || marker.index > maxIndex) return@forEach
            val frac = fracForIndex(marker.index)
            if (frac < wStart - 0.001f || frac > wStart + wWidth + 0.001f) return@forEach
            val x = xForIndexDraw(marker.index)
            val y = topPadding + h - (((marker.value - min) / range).toFloat() * h)
            val markerCenter = Offset(x, y)
            marker.badgeText?.let { badge ->
                drawMarkerBadge(markerCenter, badge, markerScale)
            }
        }

        val selected = selectedIndex?.coerceIn(0, maxIndex)
        selected?.let { idx ->
            val x = xForIndexDraw(idx)
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
                val frac = fracForIndex(tick.index)
                if (frac < wStart - 0.02f || frac > wStart + wWidth + 0.02f) return@forEach
                val x = xForIndexDraw(tick.index)
                val y = topPadding + h + 34f
                drawContext.canvas.nativeCanvas.save()
                drawContext.canvas.nativeCanvas.rotate(-55f, x, y)
                drawContext.canvas.nativeCanvas.drawText(tick.label, x, y, labelPaint)
                drawContext.canvas.nativeCanvas.restore()
            }
        }
        }
    }
}

private fun DrawScope.drawMarkerBadge(
    markerCenter: Offset,
    text: String,
    scale: Float
) {
    val textSizePx = (11f * scale).coerceIn(9f, 15f)
    val x = markerCenter.x + 11f * scale
    val y = markerCenter.y - 12f * scale
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        textAlign = Paint.Align.LEFT
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = android.graphics.Color.argb(230, 0, 0, 0)
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        textAlign = Paint.Align.LEFT
        color = android.graphics.Color.argb(255, 255, 255, 255)
    }
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawText(text, x, y, outline)
    canvas.drawText(text, x, y, fill)
}

/**
 * Equity (столбцы, ₽) в верхней половине; Drawdown (отрицательный, линия) в нижней.
 * Нулевая линия по центру; подписи сумм слева, месяцы по оси X под углом.
 */
@Composable
internal fun EquityDrawdownComboChart(
    labels: List<String>,
    equityRub: List<Double>,
    drawdownRub: List<Double>,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 280
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

    val leftPadding = 62f
    val rightPadding = 12f
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

        fun xForIndex(index: Int): Float =
            if (maxIndex == 0) leftPadding + w / 2f
            else leftPadding + (index.toFloat() / maxIndex) * w

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
