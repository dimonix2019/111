package com.example.moexmvp

import kotlin.math.abs
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Сверка «Портфель» ↔ «Тест страт.»: полный 15м ряд (до 255д в кэше теста) для корректного Z,
 * на портфеле показываем только последние N календарных дней (выбор на вкладке).
 */

internal val portfolioCompareZone: ZoneId = ZoneId.of("Europe/Moscow")

internal fun List<DataPoint>.sufficientForZSimulation(): Boolean =
    size >= Z_SCORE_ROLLING_MIN_BARS + 2

/** Только кэш «Тест страт.» (полный ряд), не подменяет короткий ряд портфеля. */
internal fun MoexScreenState.syncStrategyTestM15Cache(points: List<DataPoint>) {
    if (!points.sufficientForZSimulation()) return
    if (!strategyTestM15SessionCache.sufficientForZSimulation() ||
        strategyTestM15SessionCache.size < points.size
    ) {
        strategyTestM15SessionCache = points
        clearStrategyTestHourlyVolCache()
    }
}

/** Полный ряд для симуляции: приоритет кэша теста (255д), иначе загрузка портфеля (~30д). */
internal fun MoexScreenState.m15SeriesForZSimulation(): List<DataPoint> =
    when {
        strategyTestM15SessionCache.sufficientForZSimulation() -> strategyTestM15SessionCache
        portfolioM15Points.sufficientForZSimulation() -> portfolioM15Points
        else -> emptyList()
    }

internal fun tradeLabelOnOrAfterCutoff(label: String, cutoff: LocalDate): Boolean {
    val d = chartLabelToLocalDate(label) ?: return false
    return !d.isBefore(cutoff)
}

internal fun filterPortfolioMetricsToRecentDays(
    metrics: PortfolioMetrics,
    lookbackDays: Long,
): PortfolioMetrics {
    val days = normalizePortfolioLookbackDays(lookbackDays)
    val cutoff = LocalDate.now(portfolioCompareZone).minusDays(days - 1)
    val closed = metrics.closedTrades.filter {
        tradeLabelOnOrAfterCutoff(it.exitDate, cutoff) ||
            tradeLabelOnOrAfterCutoff(it.entryDate, cutoff)
    }
    val open = metrics.openPosition?.takeIf { tradeLabelOnOrAfterCutoff(it.entryDate, cutoff) }
    val realizedRub = closed.sumOf { it.pnlRubApprox }
    val realizedSpread = closed.sumOf { it.pnlSpreadPoints }
    val unrealized = open?.unrealizedRubApprox ?: 0.0
    val totalRub = realizedRub + unrealized
    val wins = closed.count { it.pnlRubApprox > 0 }
    val losses = closed.count { it.pnlRubApprox < 0 }
    val winRate = if (closed.isNotEmpty()) wins.toDouble() / closed.size else 0.0
    val grossWin = closed.filter { it.pnlRubApprox > 0 }.sumOf { it.pnlRubApprox }
    val grossLoss = closed.filter { it.pnlRubApprox < 0 }.sumOf { it.pnlRubApprox }
    val profitFactor = if (grossLoss < 0) grossWin / abs(grossLoss) else null
    return metrics.copy(
        periodDescription = metrics.periodDescription +
            " · окно ${days}д (МСК)",
        closedTrades = closed,
        openPosition = open,
        cumulativeRealizedSpread = realizedSpread,
        cumulativeRealizedRubApprox = realizedRub,
        unrealizedRubApprox = unrealized,
        totalPnlSpread = realizedSpread + (open?.unrealizedPnlSpread ?: 0.0),
        totalPnlRubApprox = totalRub,
        totalReturnPercent = if (metrics.notionalRub > 0) totalRub / metrics.notionalRub * 100.0 else 0.0,
        winCount = wins,
        lossCount = losses,
        winRate = winRate,
        profitFactor = profitFactor,
        avgWinRub = closed.filter { it.pnlRubApprox > 0 }.map { it.pnlRubApprox }.average().takeIf { !it.isNaN() } ?: 0.0,
        avgLossRub = closed.filter { it.pnlRubApprox < 0 }.map { it.pnlRubApprox }.average().takeIf { !it.isNaN() } ?: 0.0,
        largestWinRub = closed.maxOfOrNull { it.pnlRubApprox }?.coerceAtLeast(0.0) ?: 0.0,
        largestLossRub = closed.minOfOrNull { it.pnlRubApprox }?.coerceAtMost(0.0) ?: 0.0,
        equityCurveLabels = emptyList(),
        equityCurveRub = emptyList(),
        drawdownCurveRub = emptyList(),
    )
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
        periodDescription = "Портфель · симуляция Z",
        compoundReturns = false,
        exitMode = ZStrategyExitMode.FixedThreshold,
    )
}

/** Симуляция на полном ряду (кэш теста), сводка — только сделки за последний месяц. */
internal fun MoexScreenState.buildPortfolioTabSimulationMetricsForDisplay(
    entryThreshold: Double,
    exitThreshold: Double,
    dynamicCalculatedDate: String?,
    notionalRub: Double = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
    leverage: Double,
    commissionPercentPerSide: Double,
): PortfolioMetrics? {
    val points = m15SeriesForZSimulation()
    if (points.isEmpty()) return null
    val raw = buildPortfolioTabSimulationMetrics(
        points = points,
        entryThreshold = entryThreshold,
        exitThreshold = exitThreshold,
        dynamicCalculatedDate = dynamicCalculatedDate,
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
    ) ?: return null
    return filterPortfolioMetricsToRecentDays(raw, portfolioLookbackDays)
}

internal fun portfolioThresholdsMatchStrategyTest(
    portfolioEntry: Double,
    portfolioExit: Double,
    testEntry: Double,
    testExit: Double,
): Boolean =
    kotlin.math.abs(portfolioEntry - testEntry) <= 0.009 &&
        kotlin.math.abs(portfolioExit - testExit) <= 0.009
