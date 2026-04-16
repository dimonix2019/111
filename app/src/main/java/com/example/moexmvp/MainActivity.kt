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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
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

@Composable
private fun MoexScreen() {
    var selectedPeriod by remember { mutableStateOf(Period.OneMonth) }
    var realtimeEnabled by remember { mutableStateOf(true) }
    var realtimeInterval by remember { mutableStateOf(RealtimeInterval.FiveSeconds) }
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
                    ChartCard(
                        title = "График 1: цены TATN / TATNP",
                        series = listOf(
                            ChartSeries("TATN", Color(0xFF1565C0), current.points.map { it.tatnClose }),
                            ChartSeries("TATNP", Color(0xFFD84315), current.points.map { it.tatnpClose })
                        ),
                        labels = current.points.map { it.tradeDate }
                    )
                }
                item {
                    ChartCard(
                        title = "График 2: spread = (TATN / TATNP - 1) * 100",
                        series = listOf(
                            ChartSeries("Spread %", Color(0xFF2E7D32), current.points.map { it.spreadPercent })
                        ),
                        labels = current.points.map { it.tradeDate }
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
private fun ChartCard(title: String, series: List<ChartSeries>, labels: List<String>) {
    val axisScale = remember(series, labels) { buildAxisScale(series, labels) }
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
                modifier = Modifier
                    .weight(1f)
                    .height(180.dp)
            )
        }
        Legend(series = series)
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

    Canvas(modifier = modifier.background(Color.White, RoundedCornerShape(8.dp))) {
        val leftPadding = 16f
        val rightPadding = 16f
        val topPadding = 12f
        val bottomPadding = 52f
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
                drawContext.canvas.nativeCanvas.rotate(-40f, x, y)
                drawContext.canvas.nativeCanvas.drawText(tick.label, x, y, labelPaint)
                drawContext.canvas.nativeCanvas.restore()
            }
        }
    }
}

private fun buildAxisScale(series: List<ChartSeries>, labels: List<String>): AxisScale {
    val allValues = series.flatMap { it.values }
    if (allValues.isEmpty()) {
        return AxisScale(
            yTicks = emptyList(),
            xTicks = buildXTicks(labels)
        )
    }

    val rawMin = allValues.minOrNull() ?: 0.0
    val rawMax = allValues.maxOrNull() ?: 1.0

    val min: Double
    val max: Double
    if (rawMin == rawMax) {
        val padding = (abs(rawMin) * 0.02).coerceAtLeast(1.0)
        min = rawMin - padding
        max = rawMax + padding
    } else {
        min = rawMin
        max = rawMax
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
        if (data.isEmpty()) {
            UiState.Empty
        } else {
            UiState.Success(
                points = data,
                loadedAt = LocalDateTime.now().format(updatedAtFormatter)
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

private fun fetchData(period: Period): List<DataPoint> {
    val till = LocalDate.now()
    val from = period.from(till)

    val tatn = loadCloseSeries("TATN", from, till)
    val tatnp = loadCloseSeries("TATNP", from, till)

    val allDates = (tatn.keys + tatnp.keys).sorted()
    return allDates.mapNotNull { date ->
        val t1 = tatn[date] ?: return@mapNotNull null
        val t2 = tatnp[date] ?: return@mapNotNull null
        if (t2 == 0.0) return@mapNotNull null

        val spread = (t1 / t2 - 1.0) * 100.0
        val diff = t1 - t2
        DataPoint(
            tradeDate = date.format(tradeDateFormatter),
            tatnClose = t1,
            tatnpClose = t2,
            spreadPercent = spread,
            diff = diff
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

private data class ChartSeries(
    val name: String,
    val color: Color,
    val values: List<Double>
)

private data class AxisScale(
    val yTicks: List<Double>,
    val xTicks: List<XAxisTick>
)

private data class XAxisTick(
    val index: Int,
    val label: String
)

internal data class HistoryPage(
    val rows: Map<LocalDate, Double>,
    val total: Int?
)

private sealed interface UiState {
    data object Loading : UiState
    data class Error(val message: String) : UiState
    data object Empty : UiState
    data class Success(
        val points: List<DataPoint>,
        val loadedAt: String
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
