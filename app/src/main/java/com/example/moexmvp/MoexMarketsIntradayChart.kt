package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val INTRADAY_TATN_LINE_COLOR = Color(0xFF64B5F6)
internal val INTRADAY_TATNP_LINE_COLOR = Color(0xFFFFB74D)

internal data class AlignedIntraday1mQuotes(
    val labels: List<String>,
    val tatnCloses: List<Double>,
    val tatnpCloses: List<Double>,
)

/** Объединяет 1м ряды TATN/TATNP по метке времени (МСК). */
internal fun alignIntraday1mCloseSeries(
    tatn: List<CandlePoint>,
    tatnp: List<CandlePoint>,
): AlignedIntraday1mQuotes? {
    if (tatn.isEmpty() && tatnp.isEmpty()) return null
    val tatnByLabel = tatn.associateBy { it.label }
    val tatnpByLabel = tatnp.associateBy { it.label }
    val orderedLabels = (tatnByLabel.keys + tatnpByLabel.keys).sorted()
    if (orderedLabels.isEmpty()) return null
    val labels = mutableListOf<String>()
    val tatnCloses = mutableListOf<Double>()
    val tatnpCloses = mutableListOf<Double>()
    var lastTatn: Double? = null
    var lastTatnp: Double? = null
    orderedLabels.forEach { label ->
        val t = tatnByLabel[label]?.close ?: lastTatn
        val p = tatnpByLabel[label]?.close ?: lastTatnp
        if (t == null || p == null) return@forEach
        labels += label
        tatnCloses += t
        tatnpCloses += p
        lastTatn = t
        lastTatnp = p
    }
    if (labels.isEmpty()) return null
    return AlignedIntraday1mQuotes(labels, tatnCloses, tatnpCloses)
}

@Composable
internal fun IntradayQuotesLineChartCard(
    title: String,
    tatn: List<CandlePoint>,
    tatnp: List<CandlePoint>,
    chartHeightDp: Int = 220,
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val aligned = remember(tatn, tatnp) { alignIntraday1mCloseSeries(tatn, tatnp) }
    if (aligned == null) return
    val series = remember(aligned) {
        listOf(
            ChartSeries("TATN", INTRADAY_TATN_LINE_COLOR, aligned.tatnCloses, lineWidth = 2.5f),
            ChartSeries("TATNP", INTRADAY_TATNP_LINE_COLOR, aligned.tatnpCloses, lineWidth = 2.5f),
        )
    }
    val axisScale = remember(series, aligned.labels) {
        buildAxisScale(series = series, labels = aligned.labels, yScale = YAxisScale.Auto)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF171717), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = Color.White)
        Text(
            text = "Масштаб: два пальца · сдвиг · двойной тап: весь день",
            color = Color(0xFF9FA8DA),
            fontSize = 10.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .height(chartHeightDp.dp)
                    .width(54.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                axisScale.yTicks
                    .asReversed()
                    .forEach { tick ->
                        Text(
                            text = formatAxisValue(tick),
                            fontSize = 10.sp,
                            color = Color(0xFFD7E3F4),
                        )
                    }
            }
            LineChart(
                series = series,
                yTicks = axisScale.yTicks,
                xTicks = axisScale.xTicks,
                selectedIndex = null,
                onSelectIndex = {},
                modifier = Modifier
                    .weight(1f)
                    .height(chartHeightDp.dp),
                enableZoomPan = true,
                rightPlotPaddingFraction = CHART_RIGHT_PLOT_PADDING_FRACTION,
                initialWindowWidth = initialWindowWidth,
                initialWindowStart = initialWindowStart,
                showLastValueLabels = true,
                xLabelStyle = ChartXLabelStyleHorizontal,
            )
        }
        Legend(series = series, referenceLines = emptyList(), pointMarkers = emptyList())
    }
}
