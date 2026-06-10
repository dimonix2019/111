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

/** Корзина сделок по длительности (win%, сумма и средний PnL). */
internal data class StrategyTestDurationBucket(
    val title: String,
    val tradeCount: Int,
    val winCount: Int,
    val totalPnlRub: Double,
) {
    val winPercent: Double
        get() = if (tradeCount == 0) 0.0 else winCount * 100.0 / tradeCount
    val avgPnlRub: Double
        get() = if (tradeCount == 0) 0.0 else totalPnlRub / tradeCount
}

/** Сводка ≤48 ч / >48 ч и детальные корзины по длительности. */
internal data class StrategyTestDurationSummary(
    val short: StrategyTestDurationBucket,
    val long: StrategyTestDurationBucket,
    val detailBuckets: List<StrategyTestDurationBucket>,
)

private const val STRATEGY_TEST_MS_PER_HOUR = 3_600_000L
private const val STRATEGY_TEST_MS_PER_DAY = 24 * STRATEGY_TEST_MS_PER_HOUR
private const val STRATEGY_TEST_TWO_DAYS_MS = 2 * STRATEGY_TEST_MS_PER_DAY

internal fun strategyTestDurationBucketTitle(durationMs: Long): String = when {
    durationMs < STRATEGY_TEST_MS_PER_DAY -> "< 1 дн."
    durationMs <= STRATEGY_TEST_TWO_DAYS_MS -> "1–2 дн."
    durationMs <= 3 * STRATEGY_TEST_MS_PER_DAY -> "2–3 дн."
    durationMs <= 5 * STRATEGY_TEST_MS_PER_DAY -> "3–5 дн."
    else -> "> 5 дн."
}

private fun bucketFromTrades(title: String, trades: List<PortfolioClosedTrade>): StrategyTestDurationBucket =
    StrategyTestDurationBucket(
        title = title,
        tradeCount = trades.size,
        winCount = trades.count { it.pnlRubApprox > 0 },
        totalPnlRub = trades.sumOf { it.pnlRubApprox },
    )

/** Группировка закрытых сделок симуляции по длительности (вход → выход). */
internal fun buildStrategyTestDurationSummary(
    trades: List<PortfolioClosedTrade>,
): StrategyTestDurationSummary? {
    if (trades.isEmpty()) return null
    val shortTrades = mutableListOf<PortfolioClosedTrade>()
    val longTrades = mutableListOf<PortfolioClosedTrade>()
    val detailOrder = listOf("< 1 дн.", "1–2 дн.", "2–3 дн.", "3–5 дн.", "> 5 дн.")
    val detailAcc = detailOrder.associateWith { mutableListOf<PortfolioClosedTrade>() }.toMutableMap()
    for (trade in trades) {
        val durationMs = simTradeDurationMillis(trade.entryDate, trade.exitDate) ?: continue
        if (durationMs <= STRATEGY_TEST_TWO_DAYS_MS) shortTrades += trade else longTrades += trade
        detailAcc[strategyTestDurationBucketTitle(durationMs)]?.add(trade)
    }
    return StrategyTestDurationSummary(
        short = bucketFromTrades("≤ 2 сут", shortTrades),
        long = bucketFromTrades("> 2 сут", longTrades),
        detailBuckets = detailOrder.map { title ->
            bucketFromTrades(title, detailAcc[title].orEmpty())
        }.filter { it.tradeCount > 0 },
    )
}

/** Столбцы таблицы сделок на вкладке «Тест страт.». */
internal enum class StrategyTestTradesTableColumn(
    val title: String,
    val widthDp: Int,
) {
    Index("#", 22),
    Direction("Напр.", 34),
    Entry("Вход", 44),
    Exit("Выход", 44),
    Duration("Длит.", 38),
    Net("Чист.", 46),
    SpreadEntry("S%вх", 30),
    SpreadExit("S%вых", 30),
    SpreadDelta("Δпп", 28),
    Gross("Вал.", 42),
    Commission("Ком.", 38),
    Overnight("Овн.", 38),
    Risk("Риск", 58),
    ;

    companion object {
        val defaultVisible: Set<StrategyTestTradesTableColumn> = entries.toSet()
    }
}
