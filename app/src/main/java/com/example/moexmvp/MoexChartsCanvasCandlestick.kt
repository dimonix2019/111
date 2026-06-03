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
    initialWindowStart: Float = 0f,
    dataYMin: Double? = null,
    dataYMax: Double? = null,
    yZoom: Float = 1f,
    yViewCenter: Double = 0.0,
    onYViewChange: ((zoom: Float, viewCenter: Double) -> Unit)? = null,
    viewport: ChartViewportState? = null,
    displayMode: ChartDisplayMode = ChartDisplayMode.Candles,
    useDesktopStyle: Boolean = false,
    trackpadGestures: Boolean = true,
    xLabelStyle: ChartXLabelStyle = ChartXLabelStyleTilted,
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

        val baseMin = dataYMin ?: yTicks.minOrNull() ?: candles.minOfOrNull { it.low } ?: 0.0
        val baseMax = dataYMax ?: yTicks.maxOrNull() ?: candles.maxOfOrNull { it.high } ?: 1.0
        val effectiveYZoom = viewport?.yZoom ?: yZoom
        val effectiveYCenter = viewport?.yViewCenter ?: yViewCenter
        val (visMin, visMax) = if (enableZoomPan && (onYViewChange != null || viewport != null)) {
            visibleCandleYRange(baseMin, baseMax, effectiveYZoom, effectiveYCenter)
        } else {
            baseMin to baseMax
        }
        val range = (visMax - visMin).takeIf { it > 0.0 } ?: 1.0
        val leftPadding = 16f
        val topPadding = 12f
        val bottomPadding = xLabelStyle.bottomPaddingPx
        val maxIndex = candles.lastIndex.coerceAtLeast(0)

        var windowStart by remember(candles) { mutableFloatStateOf(0f) }
        var windowWidth by remember(candles) { mutableFloatStateOf(1f) }
        LaunchedEffect(candles, initialWindowWidth, initialWindowStart, enableZoomPan, viewport) {
            if (viewport != null) return@LaunchedEffect
            if (enableZoomPan) {
                windowWidth = initialWindowWidth.coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
                windowStart = coerceChartWindowStart(initialWindowStart, windowWidth)
            } else {
                windowStart = 0f
                windowWidth = 1f
            }
        }

        val wStart = if (enableZoomPan) (viewport?.windowStart ?: windowStart) else 0f
        val wWidth = if (enableZoomPan) {
            (viewport?.windowWidth ?: windowWidth).coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
        } else {
            1f
        }

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

        val plotBg = if (useDesktopStyle) DesktopChartColors.plotBackground else Color(0xFF0F1722)
        val gridColor = if (useDesktopStyle) DesktopChartColors.gridMajor else Color(0xFF30455A)
        val gridMinorColor = if (useDesktopStyle) DesktopChartColors.gridMinor else Color(0xFF2A3D50)
        val axisColor = if (useDesktopStyle) DesktopChartColors.axis else Color(0xFF8AA6C1)
        val selectionColor = if (useDesktopStyle) DesktopChartColors.selection else Color(0xFF90CAF9)
        val lineColor = if (useDesktopStyle) DesktopChartColors.zLine else Color(0xFF69F0AE)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(plotBg, RoundedCornerShape(8.dp))
            .then(
                if (enableZoomPan && viewport != null) {
                    Modifier.trackpadChartGestures(
                        enabled = true,
                        leftPadding = leftPadding,
                        rightPadding = rightPadding,
                        topPadding = topPadding,
                        bottomPadding = bottomPadding,
                        dataYMin = baseMin,
                        dataYMax = baseMax,
                        viewport = viewport,
                        captureTouchGestures = trackpadGestures,
                    )
                } else if (enableZoomPan) {
                    Modifier.pointerInput(
                        candles,
                        maxIndex,
                        rightPadding,
                        baseMin,
                        baseMax,
                        yZoom,
                        yViewCenter
                    ) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val chartWidthPx = chartW(size.width.toFloat())
                            val chartHeightPx =
                                (size.height - topPadding - bottomPadding).coerceAtLeast(1f)
                            if (chartWidthPx <= 1f || chartHeightPx <= 1f) return@detectTransformGestures
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
                                if (onYViewChange != null) {
                                    val centroidYRel =
                                        ((centroid.y - topPadding) / chartHeightPx).coerceIn(0f, 1f)
                                    val (newZoom, newCenter) = yViewAfterPinchZoom(
                                        dataMin = baseMin,
                                        dataMax = baseMax,
                                        yZoom = yZoom,
                                        viewCenter = yViewCenter,
                                        pinchZoom = zoom,
                                        centroidYRel = centroidYRel
                                    )
                                    onYViewChange(newZoom, newCenter)
                                }
                            } else if (pan.x != 0f || pan.y != 0f) {
                                if (pan.x != 0f) {
                                    val panFrac = -pan.x / chartWidthPx * windowWidth
                                    windowStart = coerceChartWindowStart(windowStart + panFrac, windowWidth)
                                }
                                if (pan.y != 0f && onYViewChange != null) {
                                    val fullSpan = (baseMax - baseMin).coerceAtLeast(1e-9)
                                    val visSpan = fullSpan / yZoom.coerceIn(1f, CHART_Y_ZOOM_MAX)
                                    val deltaY = pan.y / chartHeightPx * visSpan
                                    onYViewChange(yZoom, yViewCenter + deltaY)
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .pointerInput(candles, wStart, wWidth, maxIndex, enableZoomPan, rightPadding, viewport) {
                detectTapGestures(
                    onDoubleTap = if (enableZoomPan) {
                        { _: Offset ->
                            if (viewport != null) {
                                viewport.resetWindow(initialWindowWidth, initialWindowStart)
                            } else {
                                windowStart = 0f
                                windowWidth = 1f
                                onYViewChange?.invoke(
                                    1f,
                                    candleDefaultYViewCenter(baseMin, baseMax)
                                )
                            }
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
        val clipPlotY = enableZoomPan && (onYViewChange != null || viewport != null)
        fun yForValue(value: Double): Float {
            val rel = ((value - visMin) / range).toFloat()
            val clamped = if (clipPlotY) rel else rel.coerceIn(0f, 1f)
            return topPadding + h * (1f - clamped)
        }
        val xStretch = if (enableZoomPan) (1f / wWidth).coerceIn(1f, CHART_Y_ZOOM_MAX) else 1f
        val yStretch = if (enableZoomPan && (onYViewChange != null || viewport != null)) {
            effectiveYZoom.coerceIn(1f, CHART_Y_ZOOM_MAX)
        } else {
            1f
        }
        val candleStrokeMul = kotlin.math.sqrt(xStretch * yStretch).coerceIn(1f, 4f)

        yTicks.forEach { tick ->
            val y = yForValue(tick)
            drawLine(
                color = gridColor,
                start = Offset(leftPadding, y),
                end = Offset(leftPadding + w, y),
                strokeWidth = 1.5f
            )
        }

        xTicks.forEach { tick ->
            val frac = fracForIndex(tick.index)
            if (frac < wStart - 0.02f || frac > wStart + wWidth + 0.02f) return@forEach
            val x = xForIndexDraw(tick.index)
            drawLine(
                color = gridMinorColor,
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + h),
                strokeWidth = 1.5f
            )
        }

        drawLine(
            color = axisColor,
            start = Offset(leftPadding, topPadding + h),
            end = Offset(leftPadding + w, topPadding + h),
            strokeWidth = 2f
        )
        drawLine(
            color = axisColor,
            start = Offset(leftPadding, topPadding),
            end = Offset(leftPadding, topPadding + h),
            strokeWidth = 2f
        )

        val visibleCount = (candles.size * wWidth).coerceAtLeast(1f)
        val candleWidth = chartCandleBodyWidthPx(w, visibleCount)
        clipRect(
            left = leftPadding,
            top = topPadding,
            right = leftPadding + w,
            bottom = topPadding + h
        ) {
            referenceLines.forEach { reference ->
                val y = yForValue(reference.value)
                drawLine(
                    color = reference.color,
                    start = Offset(leftPadding, y),
                    end = Offset(leftPadding + w, y),
                    strokeWidth = 2f * candleStrokeMul,
                    pathEffect = PathEffect.dashPathEffect(
                        intervals = floatArrayOf(
                            reference.dashOnPx * candleStrokeMul,
                            reference.dashOffPx * candleStrokeMul
                        )
                    )
                )
            }
            if (useDesktopStyle && displayMode == ChartDisplayMode.Line) {
                val zeroY = yForValue(0.0)
                if (zeroY in topPadding..(topPadding + h)) {
                    drawLine(
                        color = DesktopChartColors.zeroLine,
                        start = Offset(leftPadding, zeroY),
                        end = Offset(leftPadding + w, zeroY),
                        strokeWidth = 1.5f,
                    )
                }
            }
            val drawRange = visibleCandleDrawIndexRange(maxIndex, wStart, wWidth)
            if (displayMode == ChartDisplayMode.Line) {
                val path = Path()
                var started = false
                for (index in drawRange) {
                    val candle = candles.getOrNull(index) ?: continue
                    val x = xForIndexDraw(index)
                    val y = yForValue(candle.close)
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }
                if (started) {
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = 2.5f * candleStrokeMul.coerceAtMost(2f), cap = StrokeCap.Round),
                    )
                }
            } else {
            for (index in drawRange) {
                val candle = candles.getOrNull(index) ?: continue
                val x = xForIndexDraw(index)
                val openY = yForValue(candle.open)
                val highY = yForValue(candle.high)
                val lowY = yForValue(candle.low)
                val closeY = yForValue(candle.close)
                val rising = candle.close >= candle.open
                val risingColor = if (useDesktopStyle) Color(0xFF22C55E) else Color(0xFF69F0AE)
                val fallingColor = if (useDesktopStyle) Color(0xFFEF4444) else Color(0xFFFF5252)
                val color = if (rising) risingColor else fallingColor

                val wickW = (1.5f * candleStrokeMul).coerceIn(1f, 3f)
                val bodyTop = min(openY, closeY)
                val bodyBottom = max(openY, closeY)
                if (highY < bodyTop - 0.5f) {
                    drawLine(
                        color = color,
                        start = Offset(x, highY),
                        end = Offset(x, bodyTop),
                        strokeWidth = wickW
                    )
                }
                if (lowY > bodyBottom + 0.5f) {
                    drawLine(
                        color = color,
                        start = Offset(x, bodyBottom),
                        end = Offset(x, lowY),
                        strokeWidth = wickW
                    )
                }

                val bodyHeight = max(bodyBottom - bodyTop, 2f * candleStrokeMul)
                drawRect(
                    color = color,
                    topLeft = Offset(x - candleWidth / 2f, bodyTop),
                    size = Size(candleWidth, bodyHeight)
                )
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
                    scale = markerScale
                )
            }
        }
        drawReferenceLineLabels(
            referenceLines = referenceLines,
            leftPadding = leftPadding,
            topPadding = topPadding,
            chartWidth = w,
            chartHeight = h,
            yForValue = ::yForValue
        )

        pointMarkers.forEach { marker ->
            if (marker.index < 0 || marker.index > maxIndex) return@forEach
            val frac = fracForIndex(marker.index)
            if (frac < wStart - 0.001f || frac > wStart + wWidth + 0.001f) return@forEach
            val x = xForIndexDraw(marker.index)
            val y = yForValue(marker.value)
            val markerCenter = Offset(x, y)
            marker.badgeText?.let { badge ->
                drawMarkerBadge(markerCenter, badge, markerScale)
            }
        }

        val selected = selectedIndex?.coerceIn(0, maxIndex)
        selected?.let { idx ->
            val x = xForIndexDraw(idx)
            drawLine(
                color = selectionColor,
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + h),
                strokeWidth = 2f
            )
        }

        if (xTicks.isNotEmpty()) {
            val labelPaint = Paint().apply {
                isAntiAlias = true
                color = axisColor.toArgb()
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
