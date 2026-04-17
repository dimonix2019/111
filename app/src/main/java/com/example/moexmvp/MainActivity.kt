package com.example.moexmvp

import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MoexScreen()
                }
            }
        }
    }
}

private val httpClient = OkHttpClient()
private val tradeDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val updatedAtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val candleTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val intradayLabelFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun MoexScreen() {
    var selectedPeriod by remember { mutableStateOf(Period.OneDay) }
    var realtimeEnabled by remember { mutableStateOf(true) }
    var realtimeInterval by remember { mutableStateOf(RealtimeInterval.FiveSeconds) }
    var spreadScaleMode by remember { mutableStateOf(SpreadScaleMode.Auto) }
    var isRefreshing by remember { mutableStateOf(false) }
    var realtimeError by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }
    val refreshMutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    suspend fun refreshData(showLoading: Boolean) {
        refreshMutex.withLock {
            if (showLoading) {
                state = UiState.Loading
            } else {
                isRefreshing = true
            }

            when (val next = loadState(selectedPeriod)) {
                is UiState.Success -> {
                    state = next
                    realtimeError = null
                }

                is UiState.Empty -> {
                    state = UiState.Empty
                    realtimeError = null
                }

                is UiState.Error -> {
                    // Do not wipe an already visible chart on background realtime refresh.
                    if (showLoading || state !is UiState.Success) {
                        state = next
                    } else {
                        realtimeError = next.message
                    }
                }

                UiState.Loading -> Unit
            }
            isRefreshing = false
        }
    }

    LaunchedEffect(selectedPeriod) {
        refreshData(showLoading = true)
    }

    LaunchedEffect(realtimeEnabled, realtimeInterval, selectedPeriod) {
        if (!realtimeEnabled) return@LaunchedEffect
        while (true) {
            delay(realtimeInterval.millis)
            refreshData(showLoading = false)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "TATN / TATNP (MOEX ISS)",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
        item {
            PeriodSelector(
                selected = selectedPeriod,
                onSelect = { selectedPeriod = it }
            )
        }
        item {
            Button(onClick = {
                scope.launch {
                    refreshData(showLoading = state !is UiState.Success)
                }
            }) {
                Text("Refresh")
            }
        }
        item {
            RealtimeControls(
                enabled = realtimeEnabled,
                selectedInterval = realtimeInterval,
                isRefreshing = isRefreshing,
                onToggle = { realtimeEnabled = !realtimeEnabled },
                onSelectInterval = { realtimeInterval = it }
            )
        }
        item {
            SpreadScaleControls(
                mode = spreadScaleMode,
                onModeChange = { spreadScaleMode = it }
            )
        }
        if (realtimeError != null && state is UiState.Success) {
            item {
                Text(
                    text = "Realtime warning: $realtimeError",
                    color = Color(0xFFB71C1C),
                    fontSize = 12.sp
                )
            }
        }
        when (val current = state) {
            is UiState.Loading -> item {
                LoadingState()
            }

            is UiState.Error -> item {
                ErrorState(current.message) {
                    scope.launch { refreshData(showLoading = true) }
                }
            }

            is UiState.Empty -> item {
                EmptyState()
            }

            is UiState.Success -> {
                item {
                    SummaryBlock(
                        points = current.points,
                        loadedAt = current.loadedAt
                    )
                }
                item {
                    CandlestickChartCard(
                        title = "График 1A: свечи TATN",
                        candles = current.tatnCandles
                    )
                }
                item {
                    CandlestickChartCard(
                        title = "График 1B: свечи TATNP",
                        candles = current.tatnpCandles
                    )
                }
                item {
                    ChartCard(
                        title = "График 2: spread = (TATN / TATNP - 1) * 100",
                        series = listOf(
                            ChartSeries("Spread %", Color(0xFF2E7D32), current.points.map { it.spreadPercent })
                        ),
                        labels = current.points.map { it.tradeDate },
                        yScale = if (spreadScaleMode == SpreadScaleMode.Fixed) {
                            YAxisScale.Fixed(min = 0.0, max = 15.0)
                        } else {
                            YAxisScale.Auto
                        }
                    )
                }
                item {
                    ChartCard(
                        title = "График 3: diff = TATN - TATNP",
                        series = listOf(
                            ChartSeries("Diff", Color(0xFF6A1B9A), current.points.map { it.diff })
                        ),
                        labels = current.points.map { it.tradeDate }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFEBEE), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Error", color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
        Text(message)
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("No data for selected period")
    }
}

@Composable
private fun SummaryBlock(points: List<DataPoint>, loadedAt: String) {
    val latest = points.lastOrNull() ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Latest: ${latest.tradeDate}", fontWeight = FontWeight.Bold)
        Text("Updated at: $loadedAt")
        Text("TATN: ${"%.2f".format(latest.tatnClose)}")
        Text("TATNP: ${"%.2f".format(latest.tatnpClose)}")
        Text("Spread: ${"%.2f".format(latest.spreadPercent)}%")
        Text("Diff: ${"%.2f".format(latest.diff)}")
    }
}

@Composable
private fun PeriodSelector(selected: Period, onSelect: (Period) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Period.entries.forEach { period ->
            val active = period == selected
            Button(
                onClick = { onSelect(period) },
                modifier = Modifier.height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            ) {
                Text(text = period.label)
            }
        }
    }
}

@Composable
private fun RealtimeControls(
    enabled: Boolean,
    selectedInterval: RealtimeInterval,
    isRefreshing: Boolean,
    onToggle: () -> Unit,
    onSelectInterval: (RealtimeInterval) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Realtime", fontWeight = FontWeight.Bold)
            Button(
                onClick = onToggle,
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (enabled) Color(0xFF2E7D32) else Color(0xFF616161),
                    contentColor = Color.White
                )
            ) {
                Text(if (enabled) "ON" else "OFF")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RealtimeInterval.entries.forEach { interval ->
                val active = interval == selectedInterval
                Button(
                    onClick = { onSelectInterval(interval) },
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                ) {
                    Text(interval.label)
                }
            }
        }
        Text(
            text = if (isRefreshing) "Status: updating..." else "Status: up to date",
            fontSize = 12.sp,
            color = if (isRefreshing) Color(0xFF1565C0) else Color(0xFF2E7D32)
        )
    }
}

@Composable
private fun SpreadScaleControls(mode: SpreadScaleMode, onModeChange: (SpreadScaleMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Spread scale", fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SpreadScaleMode.entries.forEach { candidate ->
                val active = candidate == mode
                Button(
                    onClick = { onModeChange(candidate) },
                    modifier = Modifier.height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                ) {
                    Text(candidate.label)
                }
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    series: List<ChartSeries>,
    labels: List<String>,
    yScale: YAxisScale = YAxisScale.Auto
) {
    val axisScale = remember(series, labels, yScale) { buildAxisScale(series, labels, yScale) }
    val stats = remember(series) { buildChartStats(series) }
    var selectedIndex by remember(series, labels) { mutableStateOf<Int?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF7F9FC), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .height(180.dp)
                    .width(54.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                axisScale.yTicks
                    .asReversed()
                    .forEach { tick ->
                        Text(
                            text = formatAxisValue(tick),
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
            }
            LineChart(
                series = series,
                yTicks = axisScale.yTicks,
                xTicks = axisScale.xTicks,
                selectedIndex = selectedIndex,
                onSelectIndex = { selectedIndex = it },
                modifier = Modifier
                    .weight(1f)
                    .height(180.dp)
            )
        }
        Legend(series = series)
        selectedIndex?.let { selected ->
            val label = labels.getOrNull(selected)
            val values = series.mapNotNull { chartSeries ->
                chartSeries.values.getOrNull(selected)?.let { value ->
                    "${chartSeries.name}: ${formatAxisValue(value)}"
                }
            }
            if (!label.isNullOrBlank() && values.isNotEmpty()) {
                Text(
                    text = "↕ $label | ${values.joinToString(" | ")}",
                    fontSize = 12.sp,
                    color = Color(0xFF424242)
                )
            }
        }
        Text(
            text = "Min: ${formatAxisValue(stats.min)}   Max: ${formatAxisValue(stats.max)}",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

@Composable
private fun CandlestickChartCard(title: String, candles: List<CandlePoint>) {
    val axisScale = remember(candles) { buildCandleAxisScale(candles) }
    val stats = remember(candles) { buildCandleStats(candles) }
    var selectedIndex by remember(candles) { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF7F9FC), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .height(180.dp)
                    .width(54.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                axisScale.yTicks
                    .asReversed()
                    .forEach { tick ->
                        Text(
                            text = formatAxisValue(tick),
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
            }
            CandlestickChart(
                candles = candles,
                yTicks = axisScale.yTicks,
                xTicks = axisScale.xTicks,
                selectedIndex = selectedIndex,
                onSelectIndex = { selectedIndex = it },
                modifier = Modifier
                    .weight(1f)
                    .height(180.dp)
            )
        }
        selectedIndex?.let { selected ->
            candles.getOrNull(selected)?.let { candle ->
                Text(
                    text = "↕ ${candle.label} | O ${formatAxisValue(candle.open)} | " +
                        "H ${formatAxisValue(candle.high)} | L ${formatAxisValue(candle.low)} | " +
                        "C ${formatAxisValue(candle.close)}",
                    fontSize = 12.sp,
                    color = Color(0xFF424242)
                )
            }
        }
        Text(
            text = "Min: ${formatAxisValue(stats.min)}   Max: ${formatAxisValue(stats.max)}",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

@Composable
private fun Legend(series: List<ChartSeries>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        series.forEach {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .height(14.dp)
                        .background(it.color, RoundedCornerShape(3.dp))
                )
                Text("  ${it.name}")
            }
        }
    }
}

@Composable
private fun LineChart(
    series: List<ChartSeries>,
    yTicks: List<Double>,
    xTicks: List<XAxisTick>,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (series.isEmpty() || series.all { it.values.isEmpty() }) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No chart data")
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
            .background(Color.White, RoundedCornerShape(8.dp))
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
                color = Color(0xFFE0E0E0),
                start = Offset(leftPadding, y),
                end = Offset(leftPadding + w, y),
                strokeWidth = 1.5f
            )
        }

        xTicks.forEach { tick ->
            val x = xForIndex(tick.index)
            drawLine(
                color = Color(0xFFEEEEEE),
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + h),
                strokeWidth = 1.5f
            )
        }

        drawLine(
            color = Color(0xFFBDBDBD),
            start = Offset(leftPadding, topPadding + h),
            end = Offset(leftPadding + w, topPadding + h),
            strokeWidth = 2f
        )
        drawLine(
            color = Color(0xFFBDBDBD),
            start = Offset(leftPadding, topPadding),
            end = Offset(leftPadding, topPadding + h),
            strokeWidth = 2f
        )
        xTicks.forEach { tick ->
            val x = xForIndex(tick.index)
            drawLine(
                color = Color(0xFFBDBDBD),
                start = Offset(x, topPadding + h),
                end = Offset(x, topPadding + h + 8f),
                strokeWidth = 1.5f
            )
        }

        val selected = selectedIndex?.coerceIn(0, maxIndex)
        selected?.let { idx ->
            val x = xForIndex(idx)
            drawLine(
                color = Color(0xFF616161),
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
                style = Stroke(width = 4f, cap = StrokeCap.Round)
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

        if (xTicks.isNotEmpty()) {
            val labelPaint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.GRAY
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

@Composable
private fun CandlestickChart(
    candles: List<CandlePoint>,
    yTicks: List<Double>,
    xTicks: List<XAxisTick>,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (candles.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No chart data")
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
            .background(Color.White, RoundedCornerShape(8.dp))
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
                color = Color(0xFFE0E0E0),
                start = Offset(leftPadding, y),
                end = Offset(leftPadding + w, y),
                strokeWidth = 1.5f
            )
        }

        xTicks.forEach { tick ->
            val x = xForIndex(tick.index)
            drawLine(
                color = Color(0xFFEEEEEE),
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + h),
                strokeWidth = 1.5f
            )
        }

        drawLine(
            color = Color(0xFFBDBDBD),
            start = Offset(leftPadding, topPadding + h),
            end = Offset(leftPadding + w, topPadding + h),
            strokeWidth = 2f
        )
        drawLine(
            color = Color(0xFFBDBDBD),
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
            val color = if (rising) Color(0xFF2E7D32) else Color(0xFFC62828)

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
                color = Color(0xFF616161),
                start = Offset(x, topPadding),
                end = Offset(x, topPadding + h),
                strokeWidth = 2f
            )
        }

        if (xTicks.isNotEmpty()) {
            val labelPaint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.GRAY
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

private fun resolveSelectedIndex(
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

private fun buildChartStats(series: List<ChartSeries>): ChartStats {
    val allValues = series.flatMap { it.values }
    if (allValues.isEmpty()) {
        return ChartStats(min = 0.0, max = 0.0)
    }
    return ChartStats(
        min = allValues.minOrNull() ?: 0.0,
        max = allValues.maxOrNull() ?: 0.0
    )
}

private fun buildCandleStats(candles: List<CandlePoint>): ChartStats {
    if (candles.isEmpty()) {
        return ChartStats(min = 0.0, max = 0.0)
    }
    return ChartStats(
        min = candles.minOfOrNull { it.low } ?: 0.0,
        max = candles.maxOfOrNull { it.high } ?: 0.0
    )
}

private fun buildCandleAxisScale(candles: List<CandlePoint>): AxisScale {
    if (candles.isEmpty()) {
        return AxisScale(
            yTicks = emptyList(),
            xTicks = emptyList()
        )
    }
    val min = candles.minOfOrNull { it.low } ?: 0.0
    val max = candles.maxOfOrNull { it.high } ?: 1.0
    val normalizedMax = if (max <= min) min + 1.0 else max
    return AxisScale(
        yTicks = buildYTicks(min, normalizedMax, count = 5),
        xTicks = buildXTicks(candles.map { it.label })
    )
}

private fun buildAxisScale(series: List<ChartSeries>, labels: List<String>, yScale: YAxisScale): AxisScale {
    val allValues = series.flatMap { it.values }
    if (allValues.isEmpty()) {
        return AxisScale(
            yTicks = emptyList(),
            xTicks = buildXTicks(labels)
        )
    }

    val (min, max) = when (yScale) {
        is YAxisScale.Fixed -> {
            if (yScale.max > yScale.min) {
                yScale.min to yScale.max
            } else {
                yScale.min to (yScale.min + 1.0)
            }
        }

        YAxisScale.Auto -> {
            val rawMin = allValues.minOrNull() ?: 0.0
            val rawMax = allValues.maxOrNull() ?: 1.0
            if (rawMin == rawMax) {
                val padding = (abs(rawMin) * 0.02).coerceAtLeast(1.0)
                (rawMin - padding) to (rawMax + padding)
            } else {
                rawMin to rawMax
            }
        }
    }

    return AxisScale(
        yTicks = buildYTicks(min, max, count = 5),
        xTicks = buildXTicks(labels)
    )
}

private fun buildYTicks(min: Double, max: Double, count: Int): List<Double> {
    if (count <= 1) return listOf(min)
    val step = (max - min) / (count - 1)
    return (0 until count).map { index ->
        min + step * index
    }
}

private fun buildXTicks(labels: List<String>, desiredCount: Int = 5): List<XAxisTick> {
    if (labels.isEmpty()) return emptyList()
    if (labels.size == 1) return listOf(XAxisTick(index = 0, label = labels.first()))

    val count = minOf(desiredCount, labels.size)
    val maxIndex = labels.lastIndex
    val uniqueIndices = linkedSetOf<Int>()
    for (i in 0 until count) {
        val index = ((i.toDouble() / (count - 1)) * maxIndex).roundToInt()
        uniqueIndices += index
    }

    return uniqueIndices
        .sorted()
        .map { index -> XAxisTick(index = index, label = labels[index]) }
}

private fun formatAxisValue(value: Double): String {
    val precision = when {
        abs(value) >= 1000 -> 0
        abs(value) >= 100 -> 1
        abs(value) >= 1 -> 2
        else -> 3
    }
    return String.format(Locale.US, "%.${precision}f", value)
}

private suspend fun loadState(period: Period): UiState = withContext(Dispatchers.IO) {
    try {
        val data = fetchData(period)
        if (data.points.isEmpty()) {
            UiState.Empty
        } else {
            UiState.Success(
                points = data.points,
                loadedAt = LocalDateTime.now().format(updatedAtFormatter),
                tatnCandles = data.tatnCandles,
                tatnpCandles = data.tatnpCandles
            )
        }
    } catch (t: Throwable) {
        UiState.Error(t.message ?: "Unknown error")
    }
}

private fun loadCloseSeries(
    secId: String,
    from: LocalDate,
    till: LocalDate
): Map<LocalDate, Double> {
    val pageSize = 100
    var start = 0
    val result = linkedMapOf<LocalDate, Double>()

    while (true) {
        val url = buildString {
            append("https://iss.moex.com/iss/history/engines/stock/markets/shares/boards/TQBR/securities/")
            append(secId)
            append(".json?iss.meta=off&history.columns=TRADEDATE,CLOSE")
            append("&from=").append(from)
            append("&till=").append(till)
            append("&limit=").append(pageSize)
            append("&start=").append(start)
        }
        val request = Request.Builder().url(url).build()
        val page = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while loading $secId")
            }
            val body = response.body?.string().orEmpty()
            parseHistoryPage(body)
        }
        if (page.rows.isEmpty()) break
        result.putAll(page.rows)
        if (!shouldContinuePagination(start, pageSize, page.rows.size, page.total)) break
        start += pageSize
    }

    return result
}

private fun fetchData(period: Period): FetchedData {
    val till = LocalDate.now()
    val from = period.from(till)

    val tatnBars = loadCandleSeries("TATN", period, from, till)
    val tatnpBars = loadCandleSeries("TATNP", period, from, till)

    val tatnByTime = tatnBars.associateBy { it.timestamp }
    val tatnpByTime = tatnpBars.associateBy { it.timestamp }
    val alignedTimes = (tatnByTime.keys + tatnpByTime.keys).sorted()

    val points = alignedTimes.mapNotNull { time ->
        val tatn = tatnByTime[time]?.close ?: return@mapNotNull null
        val tatnp = tatnpByTime[time]?.close ?: return@mapNotNull null
        if (tatnp == 0.0) return@mapNotNull null

        val spread = (tatn / tatnp - 1.0) * 100.0
        DataPoint(
            tradeDate = formatLabelForPeriod(time, period),
            tatnClose = tatn,
            tatnpClose = tatnp,
            spreadPercent = spread,
            diff = tatn - tatnp
        )
    }

    return FetchedData(
        points = points,
        tatnCandles = tatnBars.map {
            CandlePoint(
                label = formatLabelForPeriod(it.timestamp, period),
                open = it.open,
                high = it.high,
                low = it.low,
                close = it.close
            )
        },
        tatnpCandles = tatnpBars.map {
            CandlePoint(
                label = formatLabelForPeriod(it.timestamp, period),
                open = it.open,
                high = it.high,
                low = it.low,
                close = it.close
            )
        }
    )
}

private fun formatLabelForPeriod(timestamp: LocalDateTime, period: Period): String {
    return if (period == Period.OneDay) {
        timestamp.toLocalTime().format(intradayLabelFormatter)
    } else {
        timestamp.toLocalDate().format(tradeDateFormatter)
    }
}

private fun loadCandleSeries(
    secId: String,
    period: Period,
    from: LocalDate,
    till: LocalDate
): List<CandleBar> {
    val raw = when (period) {
        Period.OneDay -> loadCandleBars(secId, from, till, interval = 1)
        else -> loadCandleBars(secId, from, till, interval = 24)
    }
    val transformed = if (period == Period.OneDay) {
        aggregateTo15MinuteBars(raw)
    } else {
        raw
    }
    return transformed.sortedBy { it.timestamp }
}

private fun loadCandleBars(
    secId: String,
    from: LocalDate,
    till: LocalDate,
    interval: Int
): List<CandleBar> {
    val pageSize = 500
    var start = 0
    val allBars = mutableListOf<CandleBar>()

    while (true) {
        val url = buildString {
            append("https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/")
            append(secId)
            append("/candles.json?iss.meta=off&candles.columns=begin,open,high,low,close")
            append("&interval=").append(interval)
            append("&from=").append(from)
            append("&till=").append(till)
            append("&limit=").append(pageSize)
            append("&start=").append(start)
        }
        val request = Request.Builder().url(url).build()
        val page = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} while loading candles for $secId")
            }
            parseCandleBars(response.body?.string().orEmpty())
        }
        if (page.isEmpty()) break
        allBars += page
        if (page.size < pageSize) break
        start += pageSize
    }

    return allBars
}

internal fun parseCandleBars(body: String): List<CandleBar> {
    val rows = JSONObject(body)
        .optJSONObject("candles")
        ?.optJSONArray("data")
        ?: JSONArray()

    val result = mutableListOf<CandleBar>()
    for (i in 0 until rows.length()) {
        val row = rows.optJSONArray(i) ?: continue
        val beginAt = runCatching {
            LocalDateTime.parse(row.optString(0), candleTimeFormatter)
        }.getOrNull() ?: continue

        val open = toDouble(row.opt(1)) ?: continue
        val high = toDouble(row.opt(2)) ?: continue
        val low = toDouble(row.opt(3)) ?: continue
        val close = toDouble(row.opt(4)) ?: continue

        result += CandleBar(
            timestamp = beginAt,
            open = open,
            high = high,
            low = low,
            close = close
        )
    }
    return result
}

private fun toDouble(value: Any?): Double? {
    return when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}

internal fun parseCandleCloseSeries(body: String): Map<LocalDateTime, Double> {
    return parseCandleBars(body).associate { it.timestamp to it.close }
}

internal fun aggregateTo15Minutes(source: Map<LocalDateTime, Double>): Map<LocalDateTime, Double> {
    if (source.isEmpty()) return emptyMap()
    val bars = source.entries
        .sortedBy { it.key }
        .map { (ts, close) ->
            CandleBar(timestamp = ts, open = close, high = close, low = close, close = close)
        }
    return aggregateTo15MinuteBars(bars).associate { it.timestamp to it.close }
}

internal fun aggregateTo15MinuteBars(source: List<CandleBar>): List<CandleBar> {
    if (source.isEmpty()) return emptyList()

    val grouped = linkedMapOf<LocalDateTime, MutableList<CandleBar>>()
    source.sortedBy { it.timestamp }.forEach { bar ->
        val minuteBucket = (bar.timestamp.minute / 15) * 15
        val bucketTime = bar.timestamp.withMinute(minuteBucket).withSecond(0).withNano(0)
        grouped.getOrPut(bucketTime) { mutableListOf() }.add(bar)
    }

    return grouped.map { (bucketTime, bars) ->
        CandleBar(
            timestamp = bucketTime,
            open = bars.first().open,
            high = bars.maxOf { it.high },
            low = bars.minOf { it.low },
            close = bars.last().close
        )
    }
}

internal fun parseCloseSeries(body: String): Map<LocalDate, Double> {
    val rows = JSONObject(body)
        .optJSONObject("history")
        ?.optJSONArray("data")
        ?: JSONArray()

    val result = linkedMapOf<LocalDate, Double>()
    for (i in 0 until rows.length()) {
        val row = rows.optJSONArray(i) ?: continue
        val date = runCatching {
            LocalDate.parse(row.optString(0), tradeDateFormatter)
        }.getOrNull() ?: continue

        val close = when (val rawClose = row.opt(1)) {
            is Number -> rawClose.toDouble()
            is String -> rawClose.toDoubleOrNull()
            else -> null
        } ?: continue

        result[date] = close
    }
    return result
}

internal fun parseHistoryPage(body: String): HistoryPage {
    val root = JSONObject(body)
    val total = root
        .optJSONObject("history.cursor")
        ?.optJSONArray("data")
        ?.optJSONArray(0)
        ?.let { cursorRow ->
            if (cursorRow.length() > 1) cursorRow.optInt(1, -1) else -1
        }
        ?.takeIf { it > 0 }

    return HistoryPage(
        rows = parseCloseSeries(body),
        total = total
    )
}

internal fun shouldContinuePagination(
    start: Int,
    pageSize: Int,
    pageRows: Int,
    total: Int?
): Boolean {
    if (total != null) {
        val nextStart = start + pageRows
        return nextStart < total
    }
    // Fallback when cursor total is absent.
    return pageRows >= pageSize
}

private data class DataPoint(
    val tradeDate: String,
    val tatnClose: Double,
    val tatnpClose: Double,
    val spreadPercent: Double,
    val diff: Double
)

private data class CandlePoint(
    val label: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

private data class FetchedData(
    val points: List<DataPoint>,
    val tatnCandles: List<CandlePoint>,
    val tatnpCandles: List<CandlePoint>
)

private data class ChartSeries(
    val name: String,
    val color: Color,
    val values: List<Double>
)

private data class AxisScale(
    val yTicks: List<Double>,
    val xTicks: List<XAxisTick>
)

private data class ChartStats(
    val min: Double,
    val max: Double
)

private data class XAxisTick(
    val index: Int,
    val label: String
)

private sealed interface YAxisScale {
    data object Auto : YAxisScale
    data class Fixed(val min: Double, val max: Double) : YAxisScale
}

internal data class HistoryPage(
    val rows: Map<LocalDate, Double>,
    val total: Int?
)

internal data class CandleBar(
    val timestamp: LocalDateTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

private sealed interface UiState {
    data object Loading : UiState
    data class Error(val message: String) : UiState
    data object Empty : UiState
    data class Success(
        val points: List<DataPoint>,
        val loadedAt: String,
        val tatnCandles: List<CandlePoint>,
        val tatnpCandles: List<CandlePoint>
    ) : UiState
}

private enum class Period(val label: String, private val months: Long) {
    OneDay("1D", 0),
    OneWeek("1W", 0),
    OneMonth("1M", 1),
    ThreeMonths("3M", 3),
    SixMonths("6M", 6),
    OneYear("1Y", 12);

    fun from(till: LocalDate): LocalDate {
        return when (this) {
            OneDay -> till.minus(1, ChronoUnit.DAYS)
            OneWeek -> till.minus(7, ChronoUnit.DAYS)
            OneYear -> till.minus(365, ChronoUnit.DAYS)
            else -> till.minusMonths(months)
        }
    }
}

private enum class RealtimeInterval(val label: String, val millis: Long) {
    FiveSeconds("5s", 5_000L),
    TenSeconds("10s", 10_000L),
    FifteenSeconds("15s", 15_000L)
}

private enum class SpreadScaleMode(val label: String) {
    Auto("Auto"),
    Fixed("Fixed 0..15%")
}
