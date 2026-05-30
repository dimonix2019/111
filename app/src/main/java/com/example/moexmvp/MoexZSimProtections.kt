package com.example.moexmvp

import kotlin.math.max

/** Спред входа с учётом slippage (parity с strategy-web/zsim.py `_entry_spread`). */
internal fun zSimEntrySpread(
    spreadPercent: Double,
    direction: ZStrategyPosition,
    slippageSpreadPts: Double,
): Double {
    val slip = max(0.0, slippageSpreadPts)
    return when (direction) {
        ZStrategyPosition.Long -> spreadPercent + slip
        ZStrategyPosition.Short -> spreadPercent - slip
        ZStrategyPosition.Flat -> spreadPercent
    }
}

/** Спред выхода с учётом slippage (parity с strategy-web/zsim.py `_exit_spread`). */
internal fun zSimExitSpread(
    spreadPercent: Double,
    direction: ZStrategyPosition,
    slippageSpreadPts: Double,
): Double {
    val slip = max(0.0, slippageSpreadPts)
    return when (direction) {
        ZStrategyPosition.Long -> spreadPercent - slip
        ZStrategyPosition.Short -> spreadPercent + slip
        ZStrategyPosition.Flat -> spreadPercent
    }
}

internal fun zSimSpreadOk(
    spreadPercent: Double,
    minSpreadPct: Double,
    maxSpreadPct: Double,
): Boolean {
    if (minSpreadPct > 0 && spreadPercent < minSpreadPct) return false
    if (maxSpreadPct > 0 && spreadPercent > maxSpreadPct) return false
    return true
}

internal fun zSimEntryZOk(
    zScore: Double,
    direction: ZStrategyPosition,
    entryThreshold: Double,
    entryZBuffer: Double,
): Boolean {
    if (entryZBuffer <= 0.0) return true
    return when (direction) {
        ZStrategyPosition.Long -> zScore <= -(entryThreshold + entryZBuffer)
        ZStrategyPosition.Short -> zScore >= entryThreshold + entryZBuffer
        ZStrategyPosition.Flat -> false
    }
}

internal fun zSimStopLossHit(
    position: ZStrategyPosition,
    entrySpread: Double,
    barSpreadPercent: Double,
    effectiveNotionalRub: Double,
    maxLossSpreadPts: Double,
    maxLossRub: Double,
    exitCommissionRub: Double,
    overnightPerDayRub: Double,
    entryDate: String,
    barTradeDate: String,
): Boolean {
    if (position == ZStrategyPosition.Flat) return false
    val mtmPts = when (position) {
        ZStrategyPosition.Long -> barSpreadPercent - entrySpread
        ZStrategyPosition.Short -> entrySpread - barSpreadPercent
        ZStrategyPosition.Flat -> 0.0
    }
    if (maxLossSpreadPts > 0 && -mtmPts >= maxLossSpreadPts) return true
    if (maxLossRub > 0) {
        val gross = spreadPnlToRubApprox(mtmPts, effectiveNotionalRub)
        val overnightRub = overnightPerDayRub * overnightDays(entryDate, barTradeDate)
        val netLoss = -(gross - exitCommissionRub - overnightRub)
        if (netLoss >= maxLossRub) return true
    }
    return false
}
