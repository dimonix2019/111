package com.example.moexmvp

/** Снимок UI «Тест страт.» для мгновенного восстановления без повторной симуляции. */
internal data class StrategyTestVisibleSnapshot(
    val simKey: Long,
    val metrics: PortfolioMetrics,
    val chartTail: List<DataPoint>,
    val chartMarkers: List<ChartPointMarker>,
    val chartTradeSegments: List<TradingViewTradeSegment> = emptyList(),
    val tradeRiskAssessments: List<StrategyTestTradeRiskAssessment>,
    val durationSummary: StrategyTestDurationSummary?,
    val monthlyReturnSummary: StrategyTestMonthlyReturnSummary?,
    val spreadHourlyVolatility: SpreadHourlyVolatilityReport?,
)

internal fun MoexScreenState.strategyTestSimulationKey(): Long {
    val thresholds = resolveStrategyTestSimThresholds()
    val entry = thresholds.entry
    val exit = thresholds.exit
    var hash = 17L
    hash = 31 * hash + entry.toBits()
    hash = 31 * hash + exit.toBits()
    hash = 31 * hash + portfolioLeverage.toBits()
    hash = 31 * hash + portfolioCommissionPercent.toBits()
    hash = 31 * hash + strategyTestAccountSizeRub.toBits()
    hash = 31 * hash + strategyTestCapitalUsagePercent.toBits()
    hash = 31 * hash + strategyTestMaxLossDdPercent.toBits()
    hash = 31 * hash + strategyTestUseLiveZSignals.hashCode()
    hash = 31 * hash + strategyTestCompoundReturns.hashCode()
    hash = 31 * hash + strategyTestM15SessionCache.size
    hash = 31 * hash + (strategyTestM15SessionCache.firstOrNull()?.timestampMillis ?: 0L)
    hash = 31 * hash + (strategyTestM15SessionCache.lastOrNull()?.timestampMillis ?: 0L)
    return hash
}

internal fun MoexScreenState.strategyTestVisibleResultsFresh(): Boolean {
    if (strategyTestPortfolioMetrics == null || strategyTestM15ChartTail.size < 2) return false
    return strategyTestLastSimKey == strategyTestSimulationKey()
}

internal fun MoexScreenState.restoreStrategyTestVisibleFromSession(): Boolean {
    val snapshot = strategyTestVisibleSessionCache ?: return false
    if (snapshot.simKey != strategyTestSimulationKey()) return false
    applyStrategyTestVisibleSnapshot(snapshot)
    MoexDiagnostics.log(context, "strategy_test", "restore_session simKey=${snapshot.simKey}")
    return true
}

/** Сохраняет снимок и снимает тяжёлые списки из Compose state при уходе с вкладки. */
internal fun MoexScreenState.detachStrategyTestVisibleState() {
    if (strategyTestPortfolioMetrics != null && strategyTestM15ChartTail.size >= 2) {
        strategyTestVisibleSessionCache = StrategyTestVisibleSnapshot(
            simKey = strategyTestLastSimKey,
            metrics = strategyTestPortfolioMetrics!!,
            chartTail = strategyTestM15ChartTail,
            chartMarkers = strategyTestChartMarkers,
            chartTradeSegments = strategyTestChartTradeSegments,
            tradeRiskAssessments = strategyTestTradeRiskAssessments,
            durationSummary = strategyTestDurationSummary,
            monthlyReturnSummary = strategyTestMonthlyReturnSummary,
            spreadHourlyVolatility = strategyTestSpreadHourlyVolatility,
        )
    }
    strategyTestPortfolioMetrics = null
    strategyTestM15ChartTail = emptyList()
    strategyTestChartMarkers = emptyList()
    strategyTestChartTradeSegments = emptyList()
    strategyTestTradeRiskAssessments = emptyList()
    strategyTestDurationSummary = null
    strategyTestMonthlyReturnSummary = null
    strategyTestSpreadHourlyVolatility = null
    strategyTestSimComputing = false
    strategyTestM15Loading = false
    strategyTestError = null
}

internal fun MoexScreenState.applyStrategyTestVisibleSnapshot(snapshot: StrategyTestVisibleSnapshot) {
    strategyTestLastSimKey = snapshot.simKey
    strategyTestPortfolioMetrics = snapshot.metrics
    strategyTestM15ChartTail = snapshot.chartTail
    strategyTestChartMarkers = snapshot.chartMarkers
    strategyTestChartTradeSegments = snapshot.chartTradeSegments
    strategyTestTradeRiskAssessments = snapshot.tradeRiskAssessments
    strategyTestDurationSummary = snapshot.durationSummary
    strategyTestMonthlyReturnSummary = snapshot.monthlyReturnSummary
    strategyTestSpreadHourlyVolatility = snapshot.spreadHourlyVolatility
}

internal fun buildStrategyTestVisibleAnalytics(
    metrics: PortfolioMetrics,
    chartPoints: List<DataPoint>,
    m15PointsForRisk: List<DataPoint>,
    entryThreshold: Double,
): StrategyTestVisibleAnalytics {
    val closedTrades = metrics.closedTrades
    val tradeItems = buildStrategyTestTradeListFromSimulation(closedTrades)
    val tradeRiskAssessments = buildStrategyTestTradeRiskAssessmentsForItems(
        tradeItems = tradeItems,
        m15Points = m15PointsForRisk,
        entryThreshold = entryThreshold,
    )
    return StrategyTestVisibleAnalytics(
        chartMarkers = buildZScoreMarkersFromStrategyTestTrades(
            chartPoints,
            tradeItems,
            openPosition = metrics.openPosition,
        ),
        chartTradeSegments = buildTradingViewTradeSegmentsFromStrategyTest(
            tradeItems = tradeItems,
            displayPoints = chartPoints,
            openPosition = metrics.openPosition,
        ),
        tradeRiskAssessments = tradeRiskAssessments,
        durationSummary = buildStrategyTestDurationSummary(closedTrades),
        monthlyReturnSummary = buildStrategyTestMonthlyReturnSummary(
            tradeItems = tradeItems,
            notionalRub = metrics.notionalRub,
            tradeRiskAssessments = tradeRiskAssessments,
        ),
        spreadHourlyVolatility = buildSpreadHourlyVolatilityReport(chartPoints),
    )
}

internal data class StrategyTestVisibleAnalytics(
    val chartMarkers: List<ChartPointMarker>,
    val chartTradeSegments: List<TradingViewTradeSegment>,
    val tradeRiskAssessments: List<StrategyTestTradeRiskAssessment>,
    val durationSummary: StrategyTestDurationSummary?,
    val monthlyReturnSummary: StrategyTestMonthlyReturnSummary?,
    val spreadHourlyVolatility: SpreadHourlyVolatilityReport?,
)

internal fun buildM15BarIndexByLabel(points: List<DataPoint>): Map<String, Int> {
    if (points.isEmpty()) return emptyMap()
    val map = HashMap<String, Int>(points.size)
    for (i in points.indices) {
        map[points[i].tradeDate] = i
    }
    return map
}
