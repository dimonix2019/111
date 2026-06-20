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
    rightPlotPaddingPx: Float = 16f,
    rightPlotPaddingFraction: Float = 0f,
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f,
    showLastValueLabels: Boolean = false,
    xLabelStyle: ChartXLabelStyle = ChartXLabelStyleTilted,
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
    val topPadding = 12f
    val bottomPadding = xLabelStyle.bottomPaddingPx

    val maxIndex = series.maxOfOrNull { it.values.lastIndex }?.coerceAtLeast(0) ?: 0

    var layoutWidthPx by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(
        modifier = modifier.onSizeChanged { layoutWidthPx = it.width.toFloat() },
    ) {
        val density = LocalDensity.current
        val plotWidthPx = layoutWidthPx.takeIf { it > 0f }
            ?: with(density) { maxWidth.toPx() }
        val rightPadding = remember(plotWidthPx, rightPlotPaddingPx, rightPlotPaddingFraction) {
            chartRightPlotPaddingPx(
                plotWidthPx = plotWidthPx,
                minPx = rightPlotPaddingPx.coerceAtLeast(16f),
                fraction = rightPlotPaddingFraction,
            )
        }

        var windowStart by remember(series) { mutableFloatStateOf(0f) }
        var windowWidth by remember(series) { mutableFloatStateOf(1f) }
        LaunchedEffect(series, initialWindowWidth, initialWindowStart, enableZoomPan) {
            if (enableZoomPan) {
                windowWidth = initialWindowWidth.coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
                windowStart = coerceChartWindowStart(initialWindowStart, windowWidth)
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

        val chartModifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1722), RoundedCornerShape(8.dp))
            .then(
                if (enableZoomPan) {
                    Modifier.pointerInput(series, maxIndex, rightPadding) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val chartWidthPx = chartW(size.width.toFloat())
                            if (chartWidthPx <= 1f) return@detectTransformGestures
                            if (kotlin.math.abs(zoom - 1f) > 0.001f) {
                                val newW = (windowWidth / zoom).coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
                                val centroidRel =
                                    ((centroid.x - leftPadding) / chartWidthPx).coerceIn(0f, 1f)
                                val dataFracUnderCentroid = windowStart + centroidRel * windowWidth
                                val newStart = coerceChartWindowStart(
                                    dataFracUnderCentroid - centroidRel * newW,
                                    newW,
                                )
                                windowWidth = newW
                                windowStart = newStart
                            } else if (pan.x != 0f) {
                                val panFrac = -pan.x / chartWidthPx * windowWidth
                                windowStart = coerceChartWindowStart(windowStart + panFrac, windowWidth)
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .pointerInput(series, wStart, wWidth, maxIndex, enableZoomPan, rightPadding) {
                detectTapGestures(
                    onDoubleTap = if (enableZoomPan) {
                        { _: Offset ->
                            windowWidth = initialWindowWidth.coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
                            windowStart = coerceChartWindowStart(initialWindowStart, windowWidth)
                        }
                    } else {
                        null
                    },
                    onTap = { tapOffset ->
                        onSelectIndex(
                            indexFromTapX(tapOffset.x, size.width.toFloat()),
                        )
                    },
                )
            }

        Canvas(modifier = chartModifier) {
            val w = chartW(size.width)
            val h = size.height - topPadding - bottomPadding

            fun xForIndexDraw(index: Int): Float = xForIndex(index, size.width)
            fun yForValue(value: Double): Float {
                val rel = ((value - min) / range).toFloat().coerceIn(0f, 1f)
                return topPadding + h * (1f - rel)
            }

            yTicks.forEach { tick ->
                val y = yForValue(tick)
                drawLine(
                    color = Color(0xFF30455A),
                    start = Offset(leftPadding, y),
                    end = Offset(leftPadding + w, y),
                    strokeWidth = 1.5f,
                )
            }
            referenceLines.forEach { reference ->
                val y = yForValue(reference.value)
                drawLine(
                    color = reference.color,
                    start = Offset(leftPadding, y),
                    end = Offset(leftPadding + w, y),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(
                        intervals = floatArrayOf(reference.dashOnPx, reference.dashOffPx),
                    ),
                )
            }
            drawReferenceLineLabels(
                referenceLines = referenceLines,
                leftPadding = leftPadding,
                topPadding = topPadding,
                chartWidth = w,
                chartHeight = h,
                yForValue = ::yForValue,
            )

            xTicks.forEach { tick ->
                val frac = fracForIndex(tick.index)
                if (frac < wStart - 0.02f || frac > wStart + wWidth + 0.02f) return@forEach
                val x = xForIndexDraw(tick.index)
                drawLine(
                    color = Color(0xFF2A3D50),
                    start = Offset(x, topPadding),
                    end = Offset(x, topPadding + h),
                    strokeWidth = 1.5f,
                )
            }

            drawLine(
                color = Color(0xFF8AA6C1),
                start = Offset(leftPadding, topPadding + h),
                end = Offset(leftPadding + w, topPadding + h),
                strokeWidth = 2f,
            )
            drawLine(
                color = Color(0xFF8AA6C1),
                start = Offset(leftPadding, topPadding),
                end = Offset(leftPadding, topPadding + h),
                strokeWidth = 2f,
            )
            xTicks.forEach { tick ->
                val frac = fracForIndex(tick.index)
                if (frac < wStart - 0.02f || frac > wStart + wWidth + 0.02f) return@forEach
                val x = xForIndexDraw(tick.index)
                drawLine(
                    color = Color(0xFF8AA6C1),
                    start = Offset(x, topPadding + h),
                    end = Offset(x, topPadding + h + 8f),
                    strokeWidth = 1.5f,
                )
            }

            val selected = selectedIndex?.coerceIn(0, maxIndex)
            selected?.let { idx ->
                val x = xForIndexDraw(idx)
                drawLine(
                    color = Color(0xFF90CAF9),
                    start = Offset(x, topPadding),
                    end = Offset(x, topPadding + h),
                    strokeWidth = 2f,
                )
            }

            clipRect(
                left = leftPadding,
                top = topPadding,
                right = leftPadding + w,
                bottom = topPadding + h,
            ) {
                series.forEach { line ->
                    val values = line.values
                    if (values.isEmpty()) return@forEach
                    if (values.size == 1) {
                        val y = yForValue(values.first())
                        drawCircle(
                            color = line.color,
                            radius = 6f,
                            center = Offset(xForIndexDraw(0), y),
                        )
                        return@forEach
                    }

                    val path = Path()
                    values.forEachIndexed { index, value ->
                        val x = xForIndexDraw(index)
                        val y = yForValue(value)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = line.color,
                        style = Stroke(width = line.lineWidth, cap = StrokeCap.Round),
                    )

                    selected?.let { idx ->
                        if (idx <= values.lastIndex) {
                            val x = xForIndexDraw(idx)
                            val y = yForValue(values[idx])
                            drawCircle(
                                color = line.color,
                                radius = 5f,
                                center = Offset(x, y),
                            )
                        }
                    }
                }

                pointMarkers.forEach { marker ->
                    if (marker.index < 0 || marker.index > maxIndex) return@forEach
                    val frac = fracForIndex(marker.index)
                    if (frac < wStart - 0.001f || frac > wStart + wWidth + 0.001f) return@forEach
                    val x = xForIndexDraw(marker.index)
                    val y = yForValue(marker.value)
                    drawMarkerShape(
                        shape = marker.shape,
                        center = Offset(x, y),
                        color = marker.color,
                        scale = markerScale,
                    )
                }
            }

            if (showLastValueLabels && rightPadding > 0f) {
                drawSeriesLastValueLabels(
                    series = series,
                    leftPadding = leftPadding,
                    chartWidth = w,
                    rightPadding = rightPadding,
                    topPadding = topPadding,
                    chartHeight = h,
                    yForValue = ::yForValue,
                )
            }

            if (xTicks.isNotEmpty()) {
                val labelPaint = Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.rgb(221, 236, 255)
                    textSize = (if (xLabelStyle.horizontal) 9f else 10f).sp.toPx()
                    textAlign = if (xLabelStyle.horizontal) Paint.Align.CENTER else Paint.Align.RIGHT
                }
                val labelBaselineY = size.height - xLabelStyle.baselineFromBottomPx
                xTicks.forEach { tick ->
                    val frac = fracForIndex(tick.index)
                    if (frac < wStart - 0.02f || frac > wStart + wWidth + 0.02f) return@forEach
                    val x = xForIndexDraw(tick.index)
                    if (xLabelStyle.rotationDeg == 0f) {
                        drawContext.canvas.nativeCanvas.drawText(tick.label, x, labelBaselineY, labelPaint)
                    } else {
                        drawContext.canvas.nativeCanvas.save()
                        drawContext.canvas.nativeCanvas.rotate(xLabelStyle.rotationDeg, x, labelBaselineY)
                        drawContext.canvas.nativeCanvas.drawText(tick.label, x, labelBaselineY, labelPaint)
                        drawContext.canvas.nativeCanvas.restore()
                    }
                }
            }
        }
    }
}

/** Подписи последних значений серий у правого края (как lastValueVisible в Z-score). */
internal fun DrawScope.drawSeriesLastValueLabels(
    series: List<ChartSeries>,
    leftPadding: Float,
    chartWidth: Float,
    rightPadding: Float,
    topPadding: Float,
    chartHeight: Float,
    yForValue: (Double) -> Float,
) {
    if (series.isEmpty() || rightPadding <= 0f) return
    val textSizePx = 9.sp.toPx()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    val labelX = leftPadding + chartWidth + 4f
    val labelMaxX = leftPadding + chartWidth + rightPadding - 2f
    val yMin = topPadding + textSizePx * 0.85f
    val yMax = topPadding + chartHeight - 2f
    val occupiedYRanges = mutableListOf<Pair<Float, Float>>()
    series.forEach { line ->
        val lastValue = line.values.lastOrNull() ?: return@forEach
        val valueText = formatAxisValue(lastValue)
        paint.color = line.color.copy(alpha = 0.95f).toArgb()
        var textY = (yForValue(lastValue) - 4f).coerceIn(yMin, yMax)
        val fm = paint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val minY = textY + fm.ascent
        val maxY = textY + fm.descent
        val overlaps = occupiedYRanges.any { (oyMin, oyMax) ->
            minY < oyMax && maxY > oyMin
        }
        if (overlaps) {
            textY = (textY + textHeight * 0.65f).coerceAtMost(yMax)
        }
        val textWidth = paint.measureText(valueText)
        val drawX = if (textWidth > labelMaxX - labelX) labelX else labelX
        drawContext.canvas.nativeCanvas.drawText(valueText, drawX, textY, paint)
        val adjustedFm = paint.fontMetrics
        occupiedYRanges += (textY + adjustedFm.ascent) to (textY + adjustedFm.descent)
    }
}

/** Подписи порогов Z у правого края линии (вход ±entry, выход ±exit). */
internal fun DrawScope.drawReferenceLineLabels(
    referenceLines: List<ChartReferenceLine>,
    leftPadding: Float,
    topPadding: Float,
    chartWidth: Float,
    chartHeight: Float,
    yForValue: (Double) -> Float
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
        val lineY = yForValue(reference.value)
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

