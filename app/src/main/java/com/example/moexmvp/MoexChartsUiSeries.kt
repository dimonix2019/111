package com.example.moexmvp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Сборка 15м Z-ряда для UI в фоне (смена 1W/1M не блокирует main thread). */
internal suspend fun buildM15ZChartSeriesForUi(
    simPoints: List<DataPoint>,
): Pair<List<DataPoint>, List<CandlePoint>> {
    if (simPoints.isEmpty()) return emptyList<DataPoint>() to emptyList<CandlePoint>()
    val ordered = ensureAscendingM15Points(simPoints)
    val capped = capPointsBeforeChartBuild(ordered)
    // Только тело Z (open/close между барами). Без 10м spread-OHLC внутри 15м — иначе длинные тени.
    return buildM15ZChartDisplay(capped)
}

@Composable
internal fun rememberM15ZChartSeries(
    simPoints: List<DataPoint>,
    dataEpoch: Int = 0,
    liveZ: Double? = null,
): Pair<List<DataPoint>, List<CandlePoint>> {
    val series by produceState(
        initialValue = emptyList<DataPoint>() to emptyList<CandlePoint>(),
        simPoints,
        dataEpoch,
        liveZ,
        simPoints.lastOrNull()?.spreadPercent,
    ) {
        value = withContext(Dispatchers.Default) {
            buildM15ZChartSeriesForUi(simPoints)
        }
    }
    return series
}
