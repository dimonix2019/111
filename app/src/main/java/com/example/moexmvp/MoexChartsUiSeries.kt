package com.example.moexmvp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Сборка 15м Z-ряда для UI в фоне (смена 1W/1M не блокирует main thread). */
/** Порог: догрузка 10м OHLC с MOEX только для коротких рядов (1D/1W/1M). */
internal const val M15_CHART_SPREAD_OHLC_MAX_POINTS = 500

internal suspend fun buildM15ZChartSeriesForUi(
    simPoints: List<DataPoint>,
): Pair<List<DataPoint>, List<CandlePoint>> {
    if (simPoints.isEmpty()) return emptyList<DataPoint>() to emptyList<CandlePoint>()
    val ordered = ensureAscendingM15Points(simPoints)
    val capped = capPointsBeforeChartBuild(ordered)
    val base = buildM15ZChartDisplay(capped)
    if (capped.size > M15_CHART_SPREAD_OHLC_MAX_POINTS) return base
    return runCatching {
        buildM15ZChartDisplayWithSpreadOhlc(capped)
    }.getOrNull() ?: base
}

@Composable
internal fun rememberM15ZChartSeries(
    simPoints: List<DataPoint>,
): Pair<List<DataPoint>, List<CandlePoint>> {
    val series by produceState(
        initialValue = emptyList<DataPoint>() to emptyList<CandlePoint>(),
        simPoints,
    ) {
        value = withContext(Dispatchers.Default) {
            buildM15ZChartSeriesForUi(simPoints)
        }
    }
    return series
}
