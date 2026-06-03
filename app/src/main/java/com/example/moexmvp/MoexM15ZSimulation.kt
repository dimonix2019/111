package com.example.moexmvp

/**
 * Общий 15м ряд и симуляция Z для «Портфель» (сводка/сверка) и «Тест страт.» —
 * одинаковые данные и [buildZStrategyPortfolioMetrics], если пороги совпадают.
 */

internal fun List<DataPoint>.sufficientForZSimulation(): Boolean =
    size >= Z_SCORE_ROLLING_MIN_BARS + 2

/** Синхронизирует полный кэш 255д между вкладками. */
internal fun MoexScreenState.syncSharedM15Series(points: List<DataPoint>) {
    if (!points.sufficientForZSimulation()) return
    portfolioM15Points = points
    if (!strategyTestM15SessionCache.sufficientForZSimulation() ||
        strategyTestM15SessionCache.size < points.size
    ) {
        strategyTestM15SessionCache = points
    }
}

internal fun MoexScreenState.preferredM15SeriesForZSimulation(): List<DataPoint> =
    when {
        strategyTestM15SessionCache.sufficientForZSimulation() -> strategyTestM15SessionCache
        portfolioM15Points.sufficientForZSimulation() -> portfolioM15Points
        else -> emptyList()
    }

internal fun buildPortfolioTabSimulationMetrics(
    points: List<DataPoint>,
    entryThreshold: Double,
    exitThreshold: Double,
    dynamicCalculatedDate: String?,
    notionalRub: Double = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
    leverage: Double,
    commissionPercentPerSide: Double,
): PortfolioMetrics? {
    if (points.size < 2) return null
    val entry = entryThreshold.coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    val exit = exitThreshold.coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    return buildZStrategyPortfolioMetrics(
        points = points,
        thresholds = DynamicThresholds(entry, exit, dynamicCalculatedDate),
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
        periodDescription = "Портфель · симуляция Z · ${PORTFOLIO_M15_LOOKBACK_DAYS}д",
        compoundReturns = false,
        exitMode = ZStrategyExitMode.FixedThreshold,
    )
}

internal fun portfolioThresholdsMatchStrategyTest(
    portfolioEntry: Double,
    portfolioExit: Double,
    testEntry: Double,
    testExit: Double,
): Boolean =
    kotlin.math.abs(portfolioEntry - testEntry) <= 0.009 &&
        kotlin.math.abs(portfolioExit - testExit) <= 0.009
