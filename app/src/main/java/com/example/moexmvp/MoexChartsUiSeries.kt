package com.example.moexmvp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Последняя 15м свеча на «Рынок» с live Z из 1м (ещё не закрыта для сигналов). */
internal data class MarketsFormingBarHint(
    val barLabel: String,
    val barTimeSec: Long,
    val liveZ: Double,
    /** Z последнего закрытого 15м бара до live-патча (если есть). */
    val baseCloseZ: Double?,
)

internal fun resolveMarketsFormingBarHint(
    liveZ: Double?,
    liveBarAt: String?,
    patchedPoints: List<DataPoint>,
    basePoints: List<DataPoint>,
): MarketsFormingBarHint? {
    if (liveZ == null || patchedPoints.isEmpty()) return null
    val last = patchedPoints.last()
    val barAt = liveBarAt?.trim()?.takeIf { it.isNotEmpty() } ?: last.tradeDate
    if (last.tradeDate != barAt) return null
    val isCurrentBucket = last.tradeDate == currentM15BucketTradeDate()
    val baseLast = basePoints.lastOrNull()
    val baseClose = baseLast?.takeIf { it.tradeDate == last.tradeDate }?.zScore
    val livePatchesLast = baseLast == null ||
        baseLast.tradeDate != last.tradeDate ||
        abs(baseClose!! - liveZ) > 1e-9
    if (!isCurrentBucket && !livePatchesLast) return null
    return MarketsFormingBarHint(
        barLabel = last.tradeDate,
        barTimeSec = m15CandleLabelToUnixSec(last.tradeDate),
        liveZ = liveZ,
        baseCloseZ = baseClose,
    )
}

internal fun formatMarketsFormingBarHint(hint: MarketsFormingBarHint): String {
    val slot = hint.barLabel.substringAfter(' ', hint.barLabel)
    val live = String.format(Locale.US, "%+.2f", hint.liveZ)
    val base = hint.baseCloseZ
    return if (base != null && abs(hint.liveZ - base) > 0.05) {
        val closed = String.format(Locale.US, "%+.2f", base)
        "● Формируется $slot: live Z $live (закрытый 15м $closed). Сигнал — после закрытия бара."
    } else {
        "● Последняя свеча формируется (live Z из 1м). Сигнал — только после закрытия 15м."
    }
}

/** Сборка 15м Z-ряда для UI в фоне (смена 1W/1M не блокирует main thread). */
internal suspend fun buildM15ZChartSeriesForUi(
    simPoints: List<DataPoint>,
): Pair<List<DataPoint>, List<CandlePoint>> {
    if (simPoints.isEmpty()) return emptyList<DataPoint>() to emptyList<CandlePoint>()
    val ordered = ensureAscendingM15Points(simPoints)
    val capped = capPointsBeforeChartBuild(ordered)
    return buildM15ZChartDisplay(capped)
}

/**
 * Хвост Z-графика: live Z только на формирующийся 15м слот [liveBarAt].
 * Не патчим последний закрытый MOEX-бар — иначе «переезжающая» свеча на графике.
 */
internal fun applyLiveZToM15ChartSeries(
    points: List<DataPoint>,
    candles: List<CandlePoint>,
    liveZ: Double?,
    liveBarAt: String? = null,
): Pair<List<DataPoint>, List<CandlePoint>> {
    if (liveZ == null || points.isEmpty()) return points to candles
    val targetLabel = liveBarAt?.trim()?.takeIf { it.isNotEmpty() } ?: points.last().tradeDate
    val lastPt = points.last()
    return when {
        lastPt.tradeDate == targetLabel -> patchLastM15ChartBarWithLiveZ(points, candles, liveZ)
        isM15BarLabelAfter(lastPt.tradeDate, targetLabel) -> points to candles
        else -> appendFormingM15ChartBar(points, candles, targetLabel, liveZ)
    }
}

private fun patchLastM15ChartBarWithLiveZ(
    points: List<DataPoint>,
    candles: List<CandlePoint>,
    liveZ: Double,
): Pair<List<DataPoint>, List<CandlePoint>> {
    val lastPt = points.last()
    if (abs(lastPt.zScore - liveZ) < 1e-9 && candles.isEmpty()) {
        return points to candles
    }
    val patchedPts = points.toMutableList()
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

private fun appendFormingM15ChartBar(
    points: List<DataPoint>,
    candles: List<CandlePoint>,
    targetLabel: String,
    liveZ: Double,
): Pair<List<DataPoint>, List<CandlePoint>> {
    val lastPt = points.last()
    val openZ = lastPt.zScore
    val bucketMs = m15CandleLabelToUnixSec(targetLabel) * 1000L
    val newPt = lastPt.copy(
        timestampMillis = bucketMs,
        tradeDate = targetLabel,
        zScore = liveZ,
    )
    val newCandle = CandlePoint(
        label = targetLabel,
        open = openZ,
        high = maxOf(openZ, liveZ),
        low = minOf(openZ, liveZ),
        close = liveZ,
    )
    return points + newPt to candles + newCandle
}

/** true, если [later] — более новый 15м слот, чем [earlier]. */
internal fun isM15BarLabelAfter(later: String, earlier: String): Boolean {
    return runCatching {
        m15CandleLabelToUnixSec(later) > m15CandleLabelToUnixSec(earlier)
    }.getOrDefault(false)
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
