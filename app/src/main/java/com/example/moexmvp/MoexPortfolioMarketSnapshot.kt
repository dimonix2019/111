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

/** Свежий снимок рынка для тестовых входов (подгрузка MOEX incremental). */
internal suspend fun loadCurrentPortfolioMarketSnapshot(
    context: Context,
    forceNetworkRefresh: Boolean = true
): PortfolioMarketBarSnapshot {
    val app = context.applicationContext
    val till = LocalDate.now()
    val from = till.minusDays(10)
    val mode = if (forceNetworkRefresh) PortfolioM15LoadMode.INCREMENTAL else PortfolioM15LoadMode.CACHE_ONLY
    val points = loadPortfolio15mDataPoints(app, from, till, mode)
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
    val till = LocalDate.now()
    val from = till.minusDays(10)
    val points = loadPortfolio15mDataPoints(context, from, till, PortfolioM15LoadMode.CACHE_ONLY)
    if (points.isEmpty()) return fallback
    return points.minByOrNull { abs(it.timestampMillis - barTimestampMillis) }?.spreadPercent ?: fallback
}

/** Нереализованный PnL открытой спрэд-сделки по текущему спрэду (оценка, без комиссии выхода). */
internal fun estimateOpenSpreadMtmRub(
    signalType: StrategySignalType,
    entrySpreadPercent: Double,
    currentSpreadPercent: Double,
    notionalRub: Double,
    leverage: Double
): Double {
    if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return 0.0
    val mtmSpread = when (signalType) {
        StrategySignalType.EnterLong -> currentSpreadPercent - entrySpreadPercent
        StrategySignalType.EnterShort -> entrySpreadPercent - currentSpreadPercent
        else -> 0.0
    }
    return spreadPnlToRubApprox(mtmSpread, notionalRub * leverage)
}
