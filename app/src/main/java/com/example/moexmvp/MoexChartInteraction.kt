package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Цвета графика strategy-web / Plotly (тёмная тема). */
internal object DesktopChartColors {
    val plotBackground = Color(0xFF0B111B)
    val plotArea = Color(0xFF111827)
    val gridMajor = Color(0xFF1F2937)
    val gridMinor = Color(0xFF374151)
    val axis = Color(0xFF94A3B8)
    val zLine = Color(0xFF60A5FA)
    val zeroLine = Color(0xFF475569)
    val entryThreshold = Color(0xFFFBBF24)
    val exitThreshold = Color(0xFF10B981)
    val selection = Color(0xFF90CAF9)
}

internal enum class ChartDisplayMode {
    Candles,
    /** Линия Z-score — как Plotly на strategy-web. */
    Line,
}

internal class ChartViewportState(
    initialWindowWidth: Float,
    initialWindowStart: Float,
    dataYMin: Double,
    dataYMax: Double,
) {
    var windowStart by mutableFloatStateOf(initialWindowStart.coerceIn(0f, 1f))
    var windowWidth by mutableFloatStateOf(
        initialWindowWidth.coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
    )
    var yZoom by mutableFloatStateOf(1f)
    var yViewCenter by mutableDoubleStateOf(candleDefaultYViewCenter(dataYMin, dataYMax))
    var isInteracting by mutableStateOf(false)

    private val dataYMinRef = dataYMin
    private val dataYMaxRef = dataYMax

    fun resetWindow(initialWidth: Float, initialStart: Float) {
        windowWidth = initialWidth.coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
        val startUpper = (1f - windowWidth).coerceAtLeast(0f)
        windowStart = initialStart.coerceIn(0f, startUpper)
        yZoom = 1f
        yViewCenter = candleDefaultYViewCenter(dataYMinRef, dataYMaxRef)
    }

    fun zoomX(factor: Float, focusFrac: Float = 0.5f) {
        val f = focusFrac.coerceIn(0f, 1f)
        val newW = (windowWidth * factor).coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
        val anchor = windowStart + f * windowWidth
        windowStart = (anchor - f * newW).coerceIn(0f, 1f - newW)
        windowWidth = newW
    }

    fun zoomY(factor: Float) {
        yZoom = (yZoom * factor).coerceIn(1f, CHART_Y_ZOOM_MAX)
    }

    fun panX(panFrac: Float) {
        windowStart = (windowStart + panFrac).coerceIn(0f, 1f - windowWidth)
    }

    fun panY(deltaY: Double) {
        yViewCenter += deltaY
    }

    fun applyPinchZoom(
        zoom: Float,
        centroidXRel: Float,
        centroidYRel: Float,
        dataYMin: Double,
        dataYMax: Double,
    ) {
        if (abs(zoom - 1f) <= 0.001f) return
        val cx = centroidXRel.coerceIn(0f, 1f)
        val newW = (windowWidth / zoom).coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
        val anchor = windowStart + cx * windowWidth
        windowStart = (anchor - cx * newW).coerceIn(0f, 1f - newW)
        windowWidth = newW
        val (newZoom, newCenter) = yViewAfterPinchZoom(
            dataMin = dataYMin,
            dataMax = dataYMax,
            yZoom = yZoom,
            viewCenter = yViewCenter,
            pinchZoom = zoom,
            centroidYRel = centroidYRel,
        )
        yZoom = newZoom
        yViewCenter = newCenter
    }
}

internal fun chartNestedScrollConnection(isInteracting: () -> Boolean): NestedScrollConnection =
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!isInteracting()) return Offset.Zero
            return available
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!isInteracting()) return Velocity.Zero
            return available
        }
    }

/**
 * Управление как тачпад / Plotly pan: 1 палец — сдвиг, 2 пальца — масштаб от центра жеста.
 */
internal fun Modifier.trackpadChartGestures(
    enabled: Boolean,
    leftPadding: Float,
    rightPadding: Float,
    topPadding: Float,
    bottomPadding: Float,
    dataYMin: Double,
    dataYMax: Double,
    viewport: ChartViewportState,
    /** false внутри LazyColumn — не перехватывать вертикальную прокрутку (зум через toolbar). */
    captureTouchGestures: Boolean = true,
): Modifier {
    if (!enabled || !captureTouchGestures) return this
    return this
        .nestedScroll(chartNestedScrollConnection { viewport.isInteracting })
        .pointerInput(viewport) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var interacting = false
                val chartWidthPx = (size.width - leftPadding - rightPadding).coerceAtLeast(1f)
                val chartHeightPx = (size.height - topPadding - bottomPadding).coerceAtLeast(1f)
                var pinchBaseDistance = 0f
                var pinchBaseWidth = viewport.windowWidth
                var pinchBaseStart = viewport.windowStart
                var pinchBaseYZoom = viewport.yZoom
                var pinchBaseYCenter = viewport.yViewCenter

                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Main)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break

                    if (pressed.size >= 2) {
                        interacting = true
                        viewport.isInteracting = true
                        val p0 = pressed[0].position
                        val p1 = pressed[1].position
                        val centroid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                        val dist = (p0 - p1).getDistance().coerceAtLeast(1f)
                        if (pinchBaseDistance <= 0f) {
                            pinchBaseDistance = dist
                            pinchBaseWidth = viewport.windowWidth
                            pinchBaseStart = viewport.windowStart
                            pinchBaseYZoom = viewport.yZoom
                            pinchBaseYCenter = viewport.yViewCenter
                        } else {
                            val scale = (dist / pinchBaseDistance).coerceIn(0.2f, 5f)
                            val cx = ((centroid.x - leftPadding) / chartWidthPx).coerceIn(0f, 1f)
                            val cy = ((centroid.y - topPadding) / chartHeightPx).coerceIn(0f, 1f)
                            val newW = (pinchBaseWidth / scale).coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
                            val anchor = pinchBaseStart + cx * pinchBaseWidth
                            viewport.windowStart = (anchor - cx * newW).coerceIn(0f, 1f - newW)
                            viewport.windowWidth = newW
                            val (newYZoom, newYCenter) = yViewAfterPinchZoom(
                                dataMin = dataYMin,
                                dataMax = dataYMax,
                                yZoom = pinchBaseYZoom,
                                viewCenter = pinchBaseYCenter,
                                pinchZoom = scale,
                                centroidYRel = cy,
                            )
                            viewport.yZoom = newYZoom
                            viewport.yViewCenter = newYCenter
                        }
                        pressed.forEach { it.consume() }
                    } else {
                        val change = pressed.first()
                        val delta = change.positionChange()
                        if (delta != Offset.Zero && chartWidthPx > 1f) {
                            if (abs(delta.x) >= abs(delta.y)) {
                                interacting = true
                                viewport.isInteracting = true
                                val panFrac = -delta.x / chartWidthPx * viewport.windowWidth
                                viewport.panX(panFrac)
                                change.consume()
                            } else if (chartHeightPx > 1f && abs(delta.y) > 2f) {
                                interacting = true
                                viewport.isInteracting = true
                                val fullSpan = (dataYMax - dataYMin).coerceAtLeast(1e-9)
                                val visSpan = fullSpan / viewport.yZoom.coerceIn(1f, CHART_Y_ZOOM_MAX)
                                val deltaY = delta.y / chartHeightPx * visSpan
                                viewport.panY(deltaY)
                                change.consume()
                            }
                        }
                    }
                } while (true)
                viewport.isInteracting = false
            }
        }
}

@Composable
internal fun ChartPlotlyToolbar(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    onPanLeft: () -> Unit,
    onPanRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(DesktopChartColors.plotArea.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChartToolbarButton(Icons.Default.Remove, "Уменьшить", onZoomOut)
        ChartToolbarButton(Icons.Default.Add, "Увеличить", onZoomIn)
        ChartToolbarButton(Icons.Default.Home, "Сброс", onReset)
        ChartToolbarButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "←", onPanLeft)
        ChartToolbarButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "→", onPanRight)
    }
}

@Composable
private fun ChartToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = DesktopChartColors.axis,
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

internal const val CHART_TRACKPAD_HINT =
    "1 палец — сдвиг (вверх/вниз как тачпад) · 2 пальца — масштаб · двойной тап — сброс · кнопки справа"
