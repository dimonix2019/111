package com.example.moexmvp

/** Сделка в списке «Тест страт.» (только симуляция на 15м ряду). */
internal data class StrategyTestTradeItem(
    val trade: PortfolioClosedTrade,
    val sourceLabel: String = "симуляция"
)

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
