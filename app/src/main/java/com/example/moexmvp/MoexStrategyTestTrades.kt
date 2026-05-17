package com.example.moexmvp

import java.util.Locale

/** Сделка в списке «Тест страт.» с указанием источника. */
internal data class StrategyTestTradeItem(
    val trade: PortfolioClosedTrade,
    val sourceLabel: String
)

/**
 * Срез 15м ряда для симуляции в окне портфеля: с одним баром до начала окна (для пересечения prev→last).
 */
internal fun pointsForStrategyTestWindow(
    points: List<DataPoint>,
    windowStartMillis: Long = portfolioTradesWindowStartMillis()
): List<DataPoint> {
    if (points.isEmpty()) return emptyList()
    val firstInWindow = points.indexOfFirst { it.timestampMillis >= windowStartMillis }
    if (firstInWindow < 0) {
        val tail = points.takeLast(2)
        return if (tail.size >= 2) tail else emptyList()
    }
    val start = (firstInWindow - 1).coerceAtLeast(0)
    return points.subList(start, points.size)
}

internal fun confirmedTableRowsToClosedTrades(
    rows: List<PortfolioConfirmedTradeTableRow>
): List<PortfolioClosedTrade> =
    rows.map { row ->
        val direction = when (row.directionLabel.lowercase(Locale.US)) {
            "short" -> ZStrategyPosition.Short
            else -> ZStrategyPosition.Long
        }
        PortfolioClosedTrade(
            direction = direction,
            entryDate = row.entryTimeMsk,
            exitDate = row.exitTimeMsk,
            entrySpreadPercent = 0.0,
            exitSpreadPercent = 0.0,
            pnlSpreadPoints = 0.0,
            grossPnlRubApprox = row.grossPnlRubApprox,
            pnlRubApprox = row.netPnlRubApprox
        )
    }

private fun closedTradeMergeKey(t: PortfolioClosedTrade): String =
    "${t.direction.name}|${t.entryDate}|${t.exitDate}"

/**
 * Список для UI тестера: симуляция на окне 3 дн. (с FLAT) + сделки журнала/демо портфеля без дублей.
 */
internal fun buildStrategyTestTradeList(
    simulationTrades: List<PortfolioClosedTrade>,
    journalTrades: List<PortfolioClosedTrade>,
    windowStartMillis: Long = portfolioTradesWindowStartMillis()
): List<StrategyTestTradeItem> {
    val simInWindow = filterSimClosedTradesInWindow(simulationTrades, windowStartMillis)
    val journalInWindow = filterSimClosedTradesInWindow(journalTrades, windowStartMillis)
    val simKeys = simInWindow.map { closedTradeMergeKey(it) }.toSet()
    val out = mutableListOf<StrategyTestTradeItem>()
    simInWindow.asReversed().forEach { t ->
        out += StrategyTestTradeItem(t, "симуляция 3 дн.")
    }
    journalInWindow.asReversed().forEach { t ->
        val key = closedTradeMergeKey(t)
        if (key !in simKeys) {
            out += StrategyTestTradeItem(t, "журнал / демо")
        }
    }
    return out.sortedByDescending { parseSimTradeExitMillis(it.trade.exitDate) ?: 0L }
}
