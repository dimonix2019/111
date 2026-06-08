package com.example.moexmvp

internal data class SandboxClosedTradePnl(
    val grossRub: Double,
    val commissionRub: Double,
    val overnightRub: Double,
    val netRub: Double,
    val exitSpreadPercent: Double,
)

internal fun computeSandboxClosedTradePnl(
    execution: SandboxSpreadExecUi,
    exitSpreadPercent: Double,
    entryDateLabel: String,
    exitDateLabel: String,
    notionalRub: Double = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
    leverage: Double,
    commissionPercentPerSide: Double = 0.04,
): SandboxClosedTradePnl {
    val mtmSpread = openSpreadMtmPoints(
        signalType = execution.signalType,
        entrySpreadPercent = execution.entrySpreadPercent,
        currentSpreadPercent = exitSpreadPercent,
    )
    val effectiveNotionalRub = notionalRub * leverage
    val grossRub = spreadPnlToRubApprox(mtmSpread, effectiveNotionalRub)
    val (commissionRub, overnightRub) = portfolioTradeCommissionAndOvernightRub(
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
        entryDateLabel = entryDateLabel,
        exitDateLabel = exitDateLabel,
        includeExitCommission = true,
    )
    val netRub = grossRub - commissionRub - overnightRub
    return SandboxClosedTradePnl(
        grossRub = grossRub,
        commissionRub = commissionRub,
        overnightRub = overnightRub,
        netRub = netRub,
        exitSpreadPercent = exitSpreadPercent,
    )
}

internal fun portfolioDateLabelFromMskTableTime(timeMsk: String): String {
    val trimmed = timeMsk.trim()
    if (trimmed.length >= 10) return trimmed.take(10)
    return trimmed
}
