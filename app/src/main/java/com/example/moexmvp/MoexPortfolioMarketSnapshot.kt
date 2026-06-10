package com.example.moexmvp

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** Z-score, спрэд и время последней 15м точки (как на «Рынке»). */
internal data class PortfolioMarketBarSnapshot(
    val zScore: Double,
    val spreadPercent: Double,
    val timestampMillis: Long,
    val tradeDateLabel: String
) {
    val entrySpreadPercent: Double get() = spreadPercent
}

/**
 * Снимок последней 15м точки.
 * @param forTestEntry true — кнопки «Тестовая пара»: принудительный хвост MOEX + текущий 15м слот.
 */
internal suspend fun loadCurrentPortfolioMarketSnapshot(
    context: Context,
    forceNetworkRefresh: Boolean = true,
    forTestEntry: Boolean = false,
): PortfolioMarketBarSnapshot {
    val app = context.applicationContext
    val points = when {
        forTestEntry -> loadPortfolio15mPointsForTestEntry(app)
        forceNetworkRefresh -> loadZStrategySignalSeries(app, PortfolioM15LoadMode.INCREMENTAL)
        else -> loadZStrategySignalSeries(app, PortfolioM15LoadMode.CACHE_ONLY)
    }
    val last = points.lastOrNull()
    return if (last != null) {
        PortfolioMarketBarSnapshot(
            zScore = last.zScore,
            spreadPercent = last.spreadPercent,
            timestampMillis = last.timestampMillis,
            tradeDateLabel = last.tradeDate
        )
    } else {
        val now = System.currentTimeMillis()
        PortfolioMarketBarSnapshot(
            zScore = 0.0,
            spreadPercent = 0.0,
            timestampMillis = now,
            tradeDateLabel = Instant.ofEpochMilli(now).atZone(ZoneId.of("Europe/Moscow")).toLocalDate().toString()
        )
    }
}

internal suspend fun resolveSpreadPercentAtBar(
    context: Context,
    barTimestampMillis: Long,
    fallback: Double = 0.0
): Double {
    val points = loadZStrategySignalSeries(context, PortfolioM15LoadMode.CACHE_ONLY)
    if (points.isEmpty()) return fallback
    return points.minByOrNull { abs(it.timestampMillis - barTimestampMillis) }?.spreadPercent ?: fallback
}

/** Спрэд % на баре, ближайшем к [barTimestampMillis]. */
internal fun spreadPercentAtBar(
    points: List<DataPoint>,
    barTimestampMillis: Long,
    fallback: Double = 0.0
): Double {
    if (points.isEmpty()) return fallback
    return points.minByOrNull { abs(it.timestampMillis - barTimestampMillis) }?.spreadPercent ?: fallback
}

/** Время бара 15м, ближайшего к [timestampMillis] (для журнала исполнений и ledger). */
internal fun snapTimestampToNearestPortfolioBar(
    points: List<DataPoint>,
    timestampMillis: Long
): Long {
    if (points.isEmpty()) return timestampMillis
    return points.minByOrNull { abs(it.timestampMillis - timestampMillis) }?.timestampMillis
        ?: timestampMillis
}

internal const val PORTFOLIO_LEDGER_BAR_MATCH_TOLERANCE_MS = 30L * 60L * 1000L

internal fun ledgerEntryMatchesSignalBar(
    ledgerPairs: Set<Pair<StrategySignalType, Long>>,
    signalType: StrategySignalType,
    timestampMillis: Long,
    toleranceMs: Long = PORTFOLIO_LEDGER_BAR_MATCH_TOLERANCE_MS
): Boolean =
    ledgerPairs.any { (type, barTs) ->
        type == signalType && abs(barTs - timestampMillis) <= toleranceMs
    }

/**
 * Спрэд на входе: из записи сделки или с 15м-бара входа.
 * [entrySpreadPercent] == 0 — legacy/битая запись; иначе PnL завышается (весь текущий спрэд × плечо).
 */
internal fun resolveEntrySpreadPercent(
    entrySpreadPercent: Double,
    barTimestampMillis: Long,
    points: List<DataPoint>
): Double {
    if (entrySpreadPercent != 0.0) return entrySpreadPercent
    if (points.isEmpty()) return 0.0
    return spreadPercentAtBar(points, barTimestampMillis, 0.0)
}

/** Изменение спрэда в п.п. для открытой позиции. */
internal fun openSpreadMtmPoints(
    signalType: StrategySignalType,
    entrySpreadPercent: Double,
    currentSpreadPercent: Double
): Double = when (signalType) {
    StrategySignalType.EnterLong -> currentSpreadPercent - entrySpreadPercent
    StrategySignalType.EnterShort -> entrySpreadPercent - currentSpreadPercent
    else -> 0.0
}

/** Валовый нереализованный PnL (без комиссии выхода). */
internal fun estimateOpenSpreadMtmGrossRub(
    signalType: StrategySignalType,
    entrySpreadPercent: Double,
    currentSpreadPercent: Double,
    notionalRub: Double,
    leverage: Double,
    points: List<DataPoint> = emptyList(),
    barTimestampMillis: Long = 0L
): Double {
    if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return 0.0
    val entrySpread = resolveEntrySpreadPercent(entrySpreadPercent, barTimestampMillis, points)
    val mtmSpread = openSpreadMtmPoints(signalType, entrySpread, currentSpreadPercent)
    return spreadPnlToRubApprox(mtmSpread, notionalRub * leverage)
}

/** Оценка с вычетом комиссии входа (одна сторона), как в симуляции портфеля. */
internal fun estimateOpenSpreadMtmNetRub(
    signalType: StrategySignalType,
    entrySpreadPercent: Double,
    currentSpreadPercent: Double,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
    points: List<DataPoint> = emptyList(),
    barTimestampMillis: Long = 0L
): Double {
    val gross = estimateOpenSpreadMtmGrossRub(
        signalType, entrySpreadPercent, currentSpreadPercent,
        notionalRub, leverage, points, barTimestampMillis
    )
    val commissionRub = notionalRub * leverage * (commissionPercentPerSide / 100.0)
    return gross - commissionRub
}
