package com.example.moexmvp

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun ChartCard(
    title: String,
    series: List<ChartSeries>,
    labels: List<String>,
    chartHeightDp: Int = 180,
    rightAxisPercentBase: Double? = null,
    rightAxisRubPerSpreadPoint: Double? = null,
    yAxisTickFormatter: (Double) -> String = ::formatAxisValue,
    subtitle: String? = null,
    yScale: YAxisScale = YAxisScale.Auto,
    referenceLines: List<ChartReferenceLine> = emptyList(),
    pointMarkers: List<ChartPointMarker> = emptyList(),
    showLegend: Boolean = true,
    enableZoomPan: Boolean = false,
    markerScale: Float = 1f,
    showZoomHint: Boolean = false,
    rightPlotPaddingPx: Float = 16f,
    m15TimeLabels: Boolean = false,
    xLabelStyle: ChartXLabelStyle = ChartXLabelStyleTilted,
    landscapeMinimal: Boolean = false,
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f,
    onFullscreenClick: (() -> Unit)? = null,
    onExitFullscreenClick: (() -> Unit)? = null,
    /** Доп. строка под выбранной точкой (например PnL симуляции по Z). */
    tradeTapHintFormatter: ((Int) -> String?)? = null
) {
    val axisScale = remember(series, labels, yScale, referenceLines, m15TimeLabels) {
        val base = buildAxisScale(
            series = series,
            labels = labels,
            yScale = yScale,
            valueHints = referenceLines.map { it.value }
        )
        if (!m15TimeLabels) base
        else base.copy(xTicks = buildXTicksForM15Labels(labels))
    }
    val stats = remember(series) { buildChartStats(series) }
    var selectedIndex by remember(series, labels) { mutableStateOf<Int?>(null) }
    val cardPadding = if (landscapeMinimal) 4.dp else 10.dp
    val corner = if (landscapeMinimal) 0.dp else 12.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (landscapeMinimal) Modifier.fillMaxHeight() else Modifier)
            .background(Color(0xFF171717), RoundedCornerShape(corner))
            .padding(cardPadding),
        verticalArrangement = Arrangement.spacedBy(if (landscapeMinimal) 4.dp else 8.dp)
    ) {
        if (title.isNotBlank() || onFullscreenClick != null || onExitFullscreenClick != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (title.isNotBlank()) {
                        Text(
                            title,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = if (landscapeMinimal) 13.sp else 14.sp,
                        )
                    }
                    subtitle?.let {
                        Text(
                            text = it,
                            color = Color(0xFF80CBC4),
                            fontSize = if (landscapeMinimal) 9.sp else 10.sp,
                            lineHeight = 12.sp,
                        )
                    }
                }
                when {
                    onExitFullscreenClick != null -> {
                        IconButton(
                            onClick = onExitFullscreenClick,
                            modifier = Modifier.size(36.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = Color(0xFF90CAF9),
                            ),
                        ) {
                            Icon(
                                Icons.Filled.CloseFullscreen,
                                contentDescription = "Свернуть",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    onFullscreenClick != null -> {
                        IconButton(
                            onClick = onFullscreenClick,
                            modifier = Modifier.size(36.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = Color(0xFF90CAF9),
                            ),
                        ) {
                            Icon(
                                Icons.Filled.OpenInFull,
                                contentDescription = "На весь экран",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
        if (showZoomHint && enableZoomPan && !landscapeMinimal) {
            Text(
                text = "Масштаб: два пальца · сдвиг: перетаскивание · двойной тап: весь период",
                color = Color(0xFF9FA8DA),
                fontSize = 10.sp
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (landscapeMinimal) Modifier.weight(1f) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .then(
                        if (landscapeMinimal) {
                            Modifier.fillMaxHeight().width(46.dp)
                        } else {
                            Modifier.height(chartHeightDp.dp).width(54.dp)
                        },
                    ),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                axisScale.yTicks
                    .asReversed()
                    .forEach { tick ->
                        Text(
                            text = yAxisTickFormatter(tick),
                            fontSize = 10.sp,
                            color = Color(0xFFD7E3F4)
                        )
                    }
            }
            LineChart(
                series = series,
                yTicks = axisScale.yTicks,
                xTicks = axisScale.xTicks,
                selectedIndex = selectedIndex,
                onSelectIndex = { selectedIndex = it },
                referenceLines = referenceLines,
                pointMarkers = pointMarkers,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (landscapeMinimal) Modifier.fillMaxHeight()
                        else Modifier.height(chartHeightDp.dp),
                    ),
                enableZoomPan = enableZoomPan,
                markerScale = markerScale,
                rightPlotPaddingPx = rightPlotPaddingPx,
                initialWindowWidth = initialWindowWidth,
                initialWindowStart = initialWindowStart,
                xLabelStyle = xLabelStyle,
            )
            if (rightAxisPercentBase != null && rightAxisPercentBase != 0.0) {
                Column(
                    modifier = Modifier
                        .then(
                            if (landscapeMinimal) {
                                Modifier.fillMaxHeight().width(58.dp)
                            } else {
                                Modifier.height(chartHeightDp.dp).width(64.dp)
                            },
                        ),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    axisScale.yTicks
                        .asReversed()
                        .forEach { tick ->
                            Text(
                                text = formatPercentDeltaFromBase(tick, rightAxisPercentBase),
                                fontSize = 10.sp,
                                color = Color(0xFFD7E3F4)
                            )
                        }
                }
            } else if (rightAxisRubPerSpreadPoint != null) {
                Column(
                    modifier = Modifier
                        .then(
                            if (landscapeMinimal) {
                                Modifier.fillMaxHeight().width(58.dp)
                            } else {
                                Modifier.height(chartHeightDp.dp).width(64.dp)
                            },
                        ),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    axisScale.yTicks
                        .asReversed()
                        .forEach { tick ->
                            Text(
                                text = formatRubAxisValue(tick * rightAxisRubPerSpreadPoint),
                                fontSize = 10.sp,
                                color = Color(0xFFD7E3F4),
                                maxLines = 1,
                            )
                        }
                }
            }
        }
        if (showLegend && !landscapeMinimal) {
            Legend(
                series = series,
                referenceLines = referenceLines,
                pointMarkers = pointMarkers
            )
        }
        selectedIndex?.let { selected ->
            val label = labels.getOrNull(selected)
            val values = series.mapNotNull { chartSeries ->
                chartSeries.values.getOrNull(selected)?.let { value ->
                    buildString {
                        append("${chartSeries.name}: ${yAxisTickFormatter(value)}")
                        rightAxisRubPerSpreadPoint?.let { rubPer ->
                            append(" · чистый ")
                            append(formatRubAxisValue(value * rubPer))
                        }
                    }
                }
            }
            if (!label.isNullOrBlank() && values.isNotEmpty()) {
                Text(
                    text = "↕ $label | ${values.joinToString(" | ")}",
                    fontSize = 12.sp,
                    color = Color(0xFFF1F8FF)
                )
            }
            tradeTapHintFormatter?.invoke(selected)?.let { hint ->
                Text(
                    text = hint,
                    fontSize = 11.sp,
                    color = Color(0xFFFFE082),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (!landscapeMinimal) {
            Text(
                text = "Min: ${yAxisTickFormatter(stats.min)}   Max: ${yAxisTickFormatter(stats.max)}",
                fontSize = 12.sp,
                color = Color(0xFFD7E3F4)
            )
        }
    }
}

@Composable
internal fun CandlestickChartCard(
    title: String,
    candles: List<CandlePoint>,
    chartHeightDp: Int = 180,
    referenceLines: List<ChartReferenceLine> = emptyList(),
    pointMarkers: List<ChartPointMarker> = emptyList(),
    showLegend: Boolean = true,
    showMinMax: Boolean = true,
    enableZoomPan: Boolean = false,
    markerScale: Float = 1f,
    showZoomHint: Boolean = false,
    rightPlotPaddingPx: Float = 16f,
    rightPlotPaddingFraction: Float = 0f,
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f,
    tradeTapHintFormatter: ((Int) -> String?)? = null,
    landscapeMinimal: Boolean = false,
    xLabelStyle: ChartXLabelStyle = ChartXLabelStyleTilted,
    useDesktopStyle: Boolean = false,
    displayMode: ChartDisplayMode = ChartDisplayMode.Candles,
    showPlotlyToolbar: Boolean = false,
    trackpadGestures: Boolean = true,
    /** Меньше отступов между заголовком и графиком (без легенды / Min-Max / подсказки жестов). */
    compactLayout: Boolean = false,
) {
    val axisScale = remember(candles, referenceLines) {
        buildCandleAxisScale(candles, valueHints = referenceLines.map { it.value })
    }
    val dataYRange = remember(candles, referenceLines) {
        candleChartDataYRange(candles, referenceLines.map { it.value })
    }
    val viewport = remember(candles, initialWindowWidth, initialWindowStart, dataYRange) {
        ChartViewportState(
            initialWindowWidth = initialWindowWidth,
            initialWindowStart = initialWindowStart,
            dataYMin = dataYRange.first,
            dataYMax = dataYRange.second,
        )
    }
    LaunchedEffect(candles, initialWindowWidth, initialWindowStart) {
        viewport.resetWindow(initialWindowWidth, initialWindowStart)
    }
    var yZoom by remember(candles) { mutableFloatStateOf(1f) }
    var yViewCenter by remember(candles, dataYRange) {
        mutableDoubleStateOf(candleDefaultYViewCenter(dataYRange.first, dataYRange.second))
    }
    val useViewport = enableZoomPan
    if (useViewport) {
        // Подписка Compose на изменения viewport (кнопки toolbar / жесты).
        viewport.windowStart
        viewport.windowWidth
        viewport.yZoom
        viewport.yViewCenter
    }
    val effectiveYZoom = if (useViewport) viewport.yZoom else yZoom
    val effectiveYCenter = if (useViewport) viewport.yViewCenter else yViewCenter
    val visibleYRange = remember(dataYRange, effectiveYZoom, effectiveYCenter) {
        visibleCandleYRange(dataYRange.first, dataYRange.second, effectiveYZoom, effectiveYCenter)
    }
    val displayYTicks = remember(visibleYRange, enableZoomPan, axisScale.yTicks) {
        if (enableZoomPan) {
            val ticks = buildYTicks(visibleYRange.first, visibleYRange.second, count = 5)
            if (ticks.isNotEmpty()) ticks else listOf(visibleYRange.first, visibleYRange.second)
        } else {
            axisScale.yTicks.ifEmpty { listOf(dataYRange.first, dataYRange.second) }
        }
    }
    val stats = remember(candles) { buildCandleStats(candles) }
    var selectedIndex by remember(candles) { mutableStateOf<Int?>(null) }

    val cardBg = if (useDesktopStyle) DesktopChartColors.plotBackground else Color(0xFF171717)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (landscapeMinimal) Modifier.fillMaxHeight() else Modifier)
            .background(cardBg, RoundedCornerShape(if (landscapeMinimal) 0.dp else 12.dp))
            .padding(if (landscapeMinimal) 2.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(
            when {
                landscapeMinimal -> 2.dp
                compactLayout -> 4.dp
                else -> 8.dp
            }
        )
    ) {
        if (!landscapeMinimal && title.isNotBlank()) {
            Text(title, fontWeight = FontWeight.Bold, color = Color(0xFFE5E7EB))
        }
        if (showZoomHint && enableZoomPan && !landscapeMinimal && !compactLayout) {
            Text(
                text = if (useDesktopStyle) CHART_TRACKPAD_HINT else
                    "Масштаб: pinch — свечи и пороги по X и Y · сдвиг · двойной тап: сброс",
                color = Color(0xFF94A3B8),
                fontSize = 10.sp
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (landscapeMinimal) Modifier.weight(1f).fillMaxHeight()
                    else Modifier
                ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .then(
                            if (landscapeMinimal) Modifier.fillMaxHeight()
                            else Modifier.height(chartHeightDp.dp)
                        )
                        .width(if (landscapeMinimal) 42.dp else 54.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    displayYTicks
                        .asReversed()
                        .forEach { tick ->
                            Text(
                                text = formatAxisValue(tick),
                                fontSize = 10.sp,
                                color = if (useDesktopStyle) DesktopChartColors.axis else Color(0xFFD7E3F4)
                            )
                        }
                }
                CandlestickChart(
                    candles = candles,
                    yTicks = displayYTicks,
                    xTicks = axisScale.xTicks,
                    selectedIndex = selectedIndex,
                    onSelectIndex = { selectedIndex = it },
                    referenceLines = referenceLines,
                    pointMarkers = pointMarkers,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (landscapeMinimal) Modifier.fillMaxHeight()
                            else Modifier.height(chartHeightDp.dp)
                        ),
                    enableZoomPan = enableZoomPan,
                    markerScale = markerScale,
                    rightPlotPaddingPx = rightPlotPaddingPx,
                    rightPlotPaddingFraction = rightPlotPaddingFraction,
                    initialWindowWidth = initialWindowWidth,
                    initialWindowStart = initialWindowStart,
                    dataYMin = dataYRange.first,
                    dataYMax = dataYRange.second,
                    yZoom = effectiveYZoom,
                    yViewCenter = effectiveYCenter,
                    onYViewChange = if (enableZoomPan && !useViewport) {
                        { zoom, center -> yZoom = zoom; yViewCenter = center }
                    } else {
                        null
                    },
                    viewport = if (useViewport) viewport else null,
                    displayMode = displayMode,
                    useDesktopStyle = useDesktopStyle,
                    trackpadGestures = trackpadGestures,
                    xLabelStyle = xLabelStyle,
                )
            }
            if (enableZoomPan && useViewport && (showPlotlyToolbar || landscapeMinimal)) {
                ChartPlotlyToolbar(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 4.dp)
                        .zIndex(2f),
                    onZoomIn = { viewport.zoomX(0.82f) },
                    onZoomOut = { viewport.zoomX(1.22f) },
                    onReset = { viewport.resetWindow(initialWindowWidth, initialWindowStart) },
                    onPanLeft = { viewport.panX(-viewport.windowWidth * 0.12f) },
                    onPanRight = { viewport.panX(viewport.windowWidth * 0.12f) },
                )
            }
        }
        if (showLegend && !landscapeMinimal) {
            Legend(
                series = emptyList(),
                referenceLines = referenceLines,
                pointMarkers = pointMarkers
            )
        }
        if (!landscapeMinimal) {
            selectedIndex?.let { selected ->
                candles.getOrNull(selected)?.let { candle ->
                    val detail = if (displayMode == ChartDisplayMode.Line) {
                        "↕ ${candle.label} | Z ${formatAxisValue(candle.close)}"
                    } else {
                        "↕ ${candle.label} | O ${formatAxisValue(candle.open)} | " +
                            "H ${formatAxisValue(candle.high)} | L ${formatAxisValue(candle.low)} | " +
                            "C ${formatAxisValue(candle.close)}"
                    }
                    Text(
                        text = detail,
                        fontSize = 12.sp,
                        color = Color(0xFFF1F8FF)
                    )
                }
                tradeTapHintFormatter?.invoke(selected)?.let { hint ->
                    Text(
                        text = hint,
                        fontSize = 11.sp,
                        color = Color(0xFFFFE082),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (showMinMax) {
                Text(
                    text = "Min: ${formatAxisValue(stats.min)}   Max: ${formatAxisValue(stats.max)}",
                    fontSize = 12.sp,
                    color = Color(0xFFD7E3F4)
                )
            }
        }
    }
}

