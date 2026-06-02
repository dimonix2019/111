package com.example.moexmvp

/** Сделка в списке «Тест страт.» (только симуляция на 15м ряду). */
internal data class StrategyTestTradeItem(
    val trade: PortfolioClosedTrade,
    val sourceLabel: String = "симуляция"
)

/** Сделки, попадающие в окно Z-графика (не весь 255д список). */
internal fun filterStrategyTestTradesForChart(
    chartPoints: List<DataPoint>,
    tradeItems: List<StrategyTestTradeItem>,
): List<StrategyTestTradeItem> {
    if (chartPoints.isEmpty() || tradeItems.isEmpty()) return emptyList()
    val minTs = chartPoints.first().timestampMillis - 15 * 60 * 1000L
    val maxTs = chartPoints.last().timestampMillis + 15 * 60 * 1000L
    return tradeItems.filter { item ->
        val entryMs = parseSimTradeExitMillis(item.trade.entryDate)
        val exitMs = parseSimTradeExitMillis(item.trade.exitDate)
        (entryMs != null && entryMs in minTs..maxTs) ||
            (exitMs != null && exitMs in minTs..maxTs)
    }
}

/**
 * Все закрытые сделки полной симуляции [buildZStrategyPortfolioMetrics] за период 15м ряда
 * (сейчас [PORTFOLIO_M15_LOOKBACK_DAYS] дн.), новые сверху.
 */
internal fun buildStrategyTestTradeListFromSimulation(
    simulationTrades: List<PortfolioClosedTrade>
): List<StrategyTestTradeItem> =
    simulationTrades
        .map { StrategyTestTradeItem(trade = it) }
        .sortedByDescending { parseSimTradeExitMillis(it.trade.exitDate) ?: 0L }
