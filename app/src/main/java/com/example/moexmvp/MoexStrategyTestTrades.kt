package com.example.moexmvp

import android.content.Context

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

/** Один столбец доходности по месяцу выхода сделки. */
internal data class StrategyTestMonthlyReturnBar(
    val yearMonth: java.time.YearMonth,
    val label: String,
    val returnPercent: Double,
    val pnlRub: Double,
    val tradeCount: Int,
)

/** Срез доходности по закрытым сделкам симуляции. */
internal data class StrategyTestReturnSlice(
    val tradeCount: Int,
    val totalPnlRub: Double,
    val totalReturnPercent: Double,
    val avgMonthlyReturnPercent: Double,
    val monthCount: Int,
)

/** Среднемесячная доходность и сценарий без сделок в красной зоне (High/Critical). */
internal data class StrategyTestMonthlyReturnSummary(
    val notionalRub: Double,
    val allTrades: StrategyTestReturnSlice,
    val withoutRedZone: StrategyTestReturnSlice,
    val redZoneTradeCount: Int,
    val monthlyBars: List<StrategyTestMonthlyReturnBar>,
)

private fun yearMonthFromSimExit(exitDate: String, zone: java.time.ZoneId = moexZoneId): java.time.YearMonth? {
    val ms = parseSimTradeExitMillis(exitDate) ?: return null
    return java.time.YearMonth.from(java.time.Instant.ofEpochMilli(ms).atZone(zone))
}

private val strategyTestMonthBarLabelFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MM.yy")

private fun formatStrategyTestMonthBarLabel(yearMonth: java.time.YearMonth): String =
    yearMonth.format(strategyTestMonthBarLabelFormatter)

internal fun buildStrategyTestMonthlyReturnBars(
    trades: List<PortfolioClosedTrade>,
    notionalRub: Double,
): List<StrategyTestMonthlyReturnBar> {
    if (trades.isEmpty() || notionalRub <= 0.0) return emptyList()
    return trades
        .groupBy { yearMonthFromSimExit(it.exitDate) }
        .mapNotNull { (month, monthTrades) ->
            month ?: return@mapNotNull null
            val pnl = monthTrades.sumOf { it.pnlRubApprox }
            StrategyTestMonthlyReturnBar(
                yearMonth = month,
                label = formatStrategyTestMonthBarLabel(month),
                returnPercent = pnl / notionalRub * 100.0,
                pnlRub = pnl,
                tradeCount = monthTrades.size,
            )
        }
        .sortedBy { it.yearMonth }
}

private fun buildStrategyTestReturnSlice(
    trades: List<PortfolioClosedTrade>,
    notionalRub: Double,
): StrategyTestReturnSlice {
    if (trades.isEmpty() || notionalRub <= 0.0) {
        return StrategyTestReturnSlice(
            tradeCount = 0,
            totalPnlRub = 0.0,
            totalReturnPercent = 0.0,
            avgMonthlyReturnPercent = 0.0,
            monthCount = 0,
        )
    }
    val totalPnl = trades.sumOf { it.pnlRubApprox }
    val monthlyReturns = trades
        .groupBy { yearMonthFromSimExit(it.exitDate) }
        .mapNotNull { (month, monthTrades) ->
            month?.let { monthTrades.sumOf { t -> t.pnlRubApprox } / notionalRub * 100.0 }
        }
    return StrategyTestReturnSlice(
        tradeCount = trades.size,
        totalPnlRub = totalPnl,
        totalReturnPercent = totalPnl / notionalRub * 100.0,
        avgMonthlyReturnPercent = if (monthlyReturns.isEmpty()) 0.0 else monthlyReturns.average(),
        monthCount = monthlyReturns.size,
    )
}

/** Доходность по месяцам выхода; «красная зона» = [isOpenTradeRedRiskZone] (≥4 балла). */
internal fun buildStrategyTestMonthlyReturnSummary(
    tradeItems: List<StrategyTestTradeItem>,
    notionalRub: Double,
    tradeRiskAssessments: List<StrategyTestTradeRiskAssessment>,
): StrategyTestMonthlyReturnSummary? {
    if (tradeItems.isEmpty() || notionalRub <= 0.0) return null
    val allTrades = tradeItems.map { it.trade }
    val safeTrades = tradeItems.mapIndexedNotNull { index, item ->
        val risk = tradeRiskAssessments.getOrNull(index)
        if (risk != null && isOpenTradeRedRiskZone(risk)) null else item.trade
    }
    return StrategyTestMonthlyReturnSummary(
        notionalRub = notionalRub,
        allTrades = buildStrategyTestReturnSlice(allTrades, notionalRub),
        withoutRedZone = buildStrategyTestReturnSlice(safeTrades, notionalRub),
        redZoneTradeCount = allTrades.size - safeTrades.size,
        monthlyBars = buildStrategyTestMonthlyReturnBars(allTrades, notionalRub),
    )
}

internal fun formatStrategyTestMonthlyReturnSubtitle(summary: StrategyTestMonthlyReturnSummary): String =
    buildString {
        append("ср. ")
        append(formatPercentSigned(summary.allTrades.avgMonthlyReturnPercent))
        append("/мес · без красной: ")
        append(formatPercentSigned(summary.withoutRedZone.avgMonthlyReturnPercent))
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

private const val STRATEGY_TEST_TABLE_PREFS = "moex_strategy_test_table_prefs"
private const val PREF_VISIBLE_COLUMNS = "visible_columns"

internal fun encodeStrategyTestTradesTableVisibleColumns(
    columns: Set<StrategyTestTradesTableColumn>,
): String =
    columns.intersect(StrategyTestTradesTableColumn.entries.toSet())
        .ifEmpty { StrategyTestTradesTableColumn.defaultVisible }
        .joinToString(",") { it.name }

internal fun decodeStrategyTestTradesTableVisibleColumns(
    raw: String?,
): Set<StrategyTestTradesTableColumn> {
    if (raw.isNullOrBlank()) return StrategyTestTradesTableColumn.defaultVisible
    val valid = StrategyTestTradesTableColumn.entries.toSet()
    val loaded = raw.split(',')
        .mapNotNull { token ->
            runCatching { StrategyTestTradesTableColumn.valueOf(token.trim()) }.getOrNull()
        }
        .filter { it in valid }
        .toSet()
    return loaded.ifEmpty { StrategyTestTradesTableColumn.defaultVisible }
}

internal fun loadStrategyTestTradesTableVisibleColumns(context: Context): Set<StrategyTestTradesTableColumn> =
    decodeStrategyTestTradesTableVisibleColumns(
        context.applicationContext
            .getSharedPreferences(STRATEGY_TEST_TABLE_PREFS, Context.MODE_PRIVATE)
            .getString(PREF_VISIBLE_COLUMNS, null),
    )

internal fun saveStrategyTestTradesTableVisibleColumns(
    context: Context,
    columns: Set<StrategyTestTradesTableColumn>,
) {
    context.applicationContext
        .getSharedPreferences(STRATEGY_TEST_TABLE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_VISIBLE_COLUMNS, encodeStrategyTestTradesTableVisibleColumns(columns))
        .apply()
}
