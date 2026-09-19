package com.example.moexmvp

import java.util.Locale

internal val STRATEGY_TEST_PROFIT_TAKE_PERCENTS = listOf(2.0, 3.0, 5.0)

internal data class StrategyTestProfitTakeRow(
    val label: String,
    val profitTakePercent: Double?,
    val pnlRub: Double,
    val trades: Int,
    val maxDrawdownRub: Double,
    val winRate: Double,
    val deltaVsBaselineRub: Double,
)

internal fun buildStrategyTestProfitTakeCompare(
    simPoints: List<DataPoint>,
    thresholds: DynamicThresholds,
    accountSizeRub: Double,
    capitalUsagePercent: Double,
    leverageForLots: Double,
    commissionPercentPerSide: Double,
    compoundReturns: Boolean,
    baseSimOptions: ZStrategySimOptions,
    profitTakePercents: List<Double> = STRATEGY_TEST_PROFIT_TAKE_PERCENTS,
): List<StrategyTestProfitTakeRow>? {
    if (simPoints.size < 2) return null
    val prodLike = ZStrategyProdLikeSizing(
        accountSizeRub = accountSizeRub,
        capitalUsagePercent = capitalUsagePercent,
        leverageForLots = leverageForLots,
    )
    fun runSim(options: ZStrategySimOptions): PortfolioMetrics? =
        buildZStrategyPortfolioMetrics(
            points = simPoints,
            thresholds = thresholds,
            notionalRub = accountSizeRub,
            leverage = 1.0,
            commissionPercentPerSide = commissionPercentPerSide,
            periodDescription = "strategy-test profit-take",
            compoundReturns = compoundReturns,
            exitMode = ZStrategyExitMode.FixedThreshold,
            simOptions = options,
            prodLikeSizing = prodLike,
        )

    val baseline = runSim(baseSimOptions) ?: return null
    val baselinePnl = baseline.totalPnlRubApprox
    val rows = mutableListOf(
        StrategyTestProfitTakeRow(
            label = "Z-выход (база)",
            profitTakePercent = null,
            pnlRub = baselinePnl,
            trades = baseline.closedTrades.size,
            maxDrawdownRub = baseline.maxDrawdownRubApprox,
            winRate = baseline.winRate,
            deltaVsBaselineRub = 0.0,
        ),
    )
    for (pct in profitTakePercents) {
        val m = runSim(baseSimOptions.copy(forcedProfitTakePercent = pct)) ?: continue
        rows += StrategyTestProfitTakeRow(
            label = "закрытие +${pct.toInt()}%",
            profitTakePercent = pct,
            pnlRub = m.totalPnlRubApprox,
            trades = m.closedTrades.size,
            maxDrawdownRub = m.maxDrawdownRubApprox,
            winRate = m.winRate,
            deltaVsBaselineRub = m.totalPnlRubApprox - baselinePnl,
        )
    }
    return rows
}

internal fun formatStrategyTestProfitTakeSubtitle(rows: List<StrategyTestProfitTakeRow>): String {
    val best = rows.maxByOrNull { it.pnlRub } ?: return ""
    val base = rows.firstOrNull { it.profitTakePercent == null }
    return buildString {
        append("лучший: ${best.label} ${formatRubSigned(best.pnlRub)}")
        if (base != null && best.profitTakePercent != null) {
            append(" (Δ ${formatRubSigned(best.pnlRub - base.pnlRub)})")
        }
    }
}

internal fun formatStrategyTestZRegimeSubtitle(snapshot: ZRegimeAdaptiveSnapshot): String {
    val cur = snapshot.currentWindow?.let { zRegimeLabelRu(it.regime) } ?: "—"
    val fc = snapshot.nextForecast?.let {
        "${zRegimeLabelRu(it.predicted)} ${"%.0f".format(Locale.US, it.confidence * 100)}%"
    } ?: "—"
    val four = snapshot.liveAppliedFour
    return "$cur → прогноз $fc · пороги ${four.entryLong}/${four.exitLong}"
}

internal fun resolveStrategyTestZRegimeSnapshot(
    fullPoints: List<DataPoint>,
): ZRegimeAdaptiveSnapshot? {
    if (fullPoints.size < 40) return null
    val prepared = prepareM15PointsForZStrategySim(fullPoints)
    return resolveZRegimeAdaptiveSnapshot(prepared)
}
