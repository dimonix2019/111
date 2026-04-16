package com.example.moexmvp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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

@Composable
private fun MoexScreen() {
    var selectedPeriod by remember { mutableStateOf(Period.OneMonth) }
    var refreshToken by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }

    LaunchedEffect(selectedPeriod, refreshToken) {
        state = UiState.Loading
        state = loadState(selectedPeriod)
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
            Button(onClick = { refreshToken += 1 }) {
                Text("Refresh")
            }
        }
        when (val current = state) {
            is UiState.Loading -> item {
                LoadingState()
            }

            is UiState.Error -> item {
                ErrorState(current.message) { refreshToken += 1 }
            }

            is UiState.Empty -> item {
                EmptyState()
            }

            is UiState.Success -> {
                item {
                    SummaryBlock(current.points)
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
private fun SummaryBlock(points: List<DataPoint>) {
    val latest = points.lastOrNull() ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Latest: ${latest.tradeDate}", fontWeight = FontWeight.Bold)
        Text("TATN: ${"%.2f".format(latest.tatnClose)}")
        Text("TATNP: ${"%.2f".format(latest.tatnpClose)}")
        Text("Spread: ${"%.2f".format(latest.spreadPercent)}%")
        Text("Diff: ${"%.2f".format(latest.diff)}")
    }
}

@Composable
private fun PeriodSelector(selected: Period, onSelect: (Period) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun ChartCard(title: String, series: List<ChartSeries>, labels: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF7F9FC), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold)
        LineChart(series = series, modifier = Modifier.fillMaxWidth().height(180.dp))
        Legend(series = series)
        if (labels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(labels.first(), fontSize = 11.sp, color = Color.Gray)
                Text(labels.last(), fontSize = 11.sp, color = Color.Gray)
            }
        }
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
private fun LineChart(series: List<ChartSeries>, modifier: Modifier = Modifier) {
    if (series.isEmpty() || series.all { it.values.isEmpty() }) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No chart data")
        }
        return
    }

    val allValues = series.flatMap { it.values }
    val min = allValues.minOrNull() ?: 0.0
    val max = allValues.maxOrNull() ?: 1.0
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0

    Canvas(modifier = modifier.background(Color.White, RoundedCornerShape(8.dp))) {
        val leftPadding = 20f
        val rightPadding = 20f
        val topPadding = 16f
        val bottomPadding = 20f
        val w = size.width - leftPadding - rightPadding
        val h = size.height - topPadding - bottomPadding

        drawLine(
            color = Color.LightGray,
            start = Offset(leftPadding, topPadding + h),
            end = Offset(leftPadding + w, topPadding + h),
            strokeWidth = 2f
        )
        drawLine(
            color = Color.LightGray,
            start = Offset(leftPadding, topPadding),
            end = Offset(leftPadding, topPadding + h),
            strokeWidth = 2f
        )

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
            values.forEachIndexed { index, value ->
                val x = leftPadding + (index.toFloat() / (values.lastIndex.coerceAtLeast(1))) * w
                val y = topPadding + h - (((value - min) / range).toFloat() * h)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = line.color,
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
        }
    }
}

private suspend fun loadState(period: Period): UiState = withContext(Dispatchers.IO) {
    try {
        val data = fetchData(period)
        if (data.isEmpty()) UiState.Empty else UiState.Success(data)
    } catch (t: Throwable) {
        UiState.Error(t.message ?: "Unknown error")
    }
}

private fun loadCloseSeries(
    secId: String,
    from: LocalDate,
    till: LocalDate
): Map<LocalDate, Double> {
    val url = buildString {
        append("https://iss.moex.com/iss/history/engines/stock/markets/shares/boards/TQBR/securities/")
        append(secId)
        append(".json?iss.meta=off&history.columns=TRADEDATE,CLOSE")
        append("&from=").append(from)
        append("&till=").append(till)
        append("&limit=100")
    }
    val request = Request.Builder().url(url).build()
    httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code} while loading $secId")
        }
        val body = response.body?.string().orEmpty()
        return parseCloseSeries(body)
    }
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

private fun parseCloseSeries(body: String): Map<LocalDate, Double> {
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

private sealed interface UiState {
    data object Loading : UiState
    data class Error(val message: String) : UiState
    data object Empty : UiState
    data class Success(val points: List<DataPoint>) : UiState
}

private enum class Period(val label: String, private val months: Long) {
    OneMonth("1M", 1),
    ThreeMonths("3M", 3),
    SixMonths("6M", 6),
    OneYear("1Y", 12);

    fun from(till: LocalDate): LocalDate {
        return if (this == OneYear) till.minus(365, ChronoUnit.DAYS) else till.minusMonths(months)
    }
}
