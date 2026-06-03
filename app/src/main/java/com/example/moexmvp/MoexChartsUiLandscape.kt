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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
internal fun LandscapeZScoreFullscreenPane(
    selectedPeriod: Period,
    onPeriodSelect: (Period) -> Unit,
    candles: List<CandlePoint>,
    referenceLines: List<ChartReferenceLine>,
    pointMarkers: List<ChartPointMarker>,
    modifier: Modifier = Modifier,
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f,
    tradeTapHintFormatter: ((Int) -> String?)? = null,
    emptyContent: @Composable () -> Unit = { EmptyState() },
    useDesktopStyle: Boolean = true,
    displayMode: ChartDisplayMode = ChartDisplayMode.Candles,
    showPlotlyToolbar: Boolean = true,
    trackpadGestures: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        MarketsPeriodSelector(
            selected = selectedPeriod,
            onSelect = onPeriodSelect,
        )
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val chartH = maxHeight.value
                .takeIf { it.isFinite() && it > 0f }
                ?.roundToInt()
                ?.coerceIn(80, 720)
                ?: 320
            if (candles.isNotEmpty()) {
                CandlestickChartCard(
                    title = "",
                    candles = candles,
                    chartHeightDp = chartH,
                    referenceLines = referenceLines,
                    pointMarkers = pointMarkers,
                    showLegend = false,
                    enableZoomPan = true,
                    markerScale = 1.4f,
                    rightPlotPaddingFraction = 0.04f,
                    showZoomHint = false,
                    landscapeMinimal = true,
                    xLabelStyle = ChartXLabelStyleHorizontal,
                    initialWindowWidth = initialWindowWidth,
                    initialWindowStart = initialWindowStart,
                    tradeTapHintFormatter = tradeTapHintFormatter,
                    useDesktopStyle = useDesktopStyle,
                    displayMode = displayMode,
                    showPlotlyToolbar = showPlotlyToolbar,
                    trackpadGestures = trackpadGestures,
                )
            } else {
                emptyContent()
            }
        }
    }
}

@Composable
internal fun Legend(
    series: List<ChartSeries>,
    referenceLines: List<ChartReferenceLine> = emptyList(),
    pointMarkers: List<ChartPointMarker> = emptyList()
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        series.forEach { item ->
            LegendItem(
                color = item.color,
                label = item.name,
                square = true
            )
        }
        referenceLines.forEach { reference ->
            LegendItem(
                color = reference.color,
                label = reference.label,
                square = false
            )
        }
        pointMarkers
            .distinctBy { it.label }
            .forEach { marker ->
                LegendItem(
                    color = marker.color,
                    label = marker.label,
                    square = true
                )
            }
    }
}

@Composable
internal fun LegendItem(
    color: Color,
    label: String,
    square: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(if (square) 14.dp else 4.dp)
                .background(
                    color = color,
                    shape = if (square) CircleShape else RoundedCornerShape(2.dp)
                )
        )
        Text("  $label", color = Color(0xFFF1F8FF))
    }
}
@Composable
internal fun StrategyTestEquityDrawdownChartCard(
    labels: List<String>,
    equityRub: List<Double>,
    drawdownRub: List<Double>,
    modifier: Modifier = Modifier,
    chartHeightDp: Int = 220
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF171717), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Equity и просадка (симуляция, ₽)",
            fontWeight = FontWeight.Bold,
            color = Color.White,
            fontSize = 14.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("■ Equity", color = Color(0xFF4FC3F7), fontSize = 10.sp)
            Text("— Drawdown", color = Color(0xFFFFAB40), fontSize = 10.sp)
        }
        Text(
            text = "Верх: Equity (₽) от нулевой линии. Низ: Drawdown (отрицательный, ₽). По оси X — месяцы.",
            color = Color(0xFF9E9E9E),
            fontSize = 9.sp,
            lineHeight = 12.sp
        )
        EquityDrawdownComboChart(
            labels = labels,
            equityRub = equityRub,
            drawdownRub = drawdownRub,
            chartHeightDp = chartHeightDp
        )
    }
}
