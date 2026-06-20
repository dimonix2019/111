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
    return buildM15ZChartDisplay(capped)
}

/** Хвост Z-графика: close последней свечи = live Z из 1м котировок. */
internal fun applyLiveZToM15ChartSeries(
    points: List<DataPoint>,
    candles: List<CandlePoint>,
    liveZ: Double?,
): Pair<List<DataPoint>, List<CandlePoint>> {
    if (liveZ == null || points.isEmpty()) return points to candles
    val patchedPts = points.toMutableList()
    val lastPt = patchedPts.last()
    if (kotlin.math.abs(lastPt.zScore - liveZ) < 1e-9 && candles.isEmpty()) {
        return points to candles
    }
    patchedPts[patchedPts.lastIndex] = lastPt.copy(zScore = liveZ)
    if (candles.isEmpty()) return patchedPts to candles
    val patchedCandles = candles.toMutableList()
    val lastC = patchedCandles.last()
    val open = lastC.open
    patchedCandles[patchedCandles.lastIndex] = lastC.copy(
        close = liveZ,
        high = maxOf(open, liveZ),
        low = minOf(open, liveZ),
    )
    return patchedPts to patchedCandles
}

@Composable
internal fun rememberM15ZChartSeries(
    simPoints: List<DataPoint>,
    dataEpoch: Int = 0,
): Pair<List<DataPoint>, List<CandlePoint>> {
    val series by produceState(
        initialValue = emptyList<DataPoint>() to emptyList<CandlePoint>(),
        simPoints,
        dataEpoch,
    ) {
        value = withContext(Dispatchers.Default) {
            buildM15ZChartSeriesForUi(simPoints)
        }
    }
    return series
}
