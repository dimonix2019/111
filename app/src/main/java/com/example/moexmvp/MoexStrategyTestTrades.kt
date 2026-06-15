package com.example.moexmvp

import android.content.Context
import kotlin.math.abs
import kotlin.math.max

/** Сделка в списке «Тест страт.» (только симуляция на 15м ряду). */
internal data class StrategyTestTradeItem(
    val trade: PortfolioClosedTrade,
    val sourceLabel: String = "симуляция"
)

internal fun filterStrategyTestTradeItemsExcludingRedZone(
    tradeItems: List<StrategyTestTradeItem>,
    tradeRiskAssessments: List<StrategyTestTradeRiskAssessment>,
): List<StrategyTestTradeItem> =
    filterStrategyTestTradeItemsWithRiskExcludingRedZone(tradeItems, tradeRiskAssessments).first

/** Пары сделка+риск без красной зоны (индексы совпадают). */
internal fun filterStrategyTestTradeItemsWithRiskExcludingRedZone(
    tradeItems: List<StrategyTestTradeItem>,
    tradeRiskAssessments: List<StrategyTestTradeRiskAssessment>,
): Pair<List<StrategyTestTradeItem>, List<StrategyTestTradeRiskAssessment>> {
    val items = mutableListOf<StrategyTestTradeItem>()
    val risks = mutableListOf<StrategyTestTradeRiskAssessment>()
    tradeItems.forEachIndexed { index, item ->
        val risk = tradeRiskAssessments.getOrNull(index)
        if (risk != null && isOpenTradeRedRiskZone(risk)) return@forEachIndexed
        items += item
        if (risk != null) risks += risk
    }
    return items to risks
}

/**
 * Метрики симуляции только по выбранным закрытым сделкам (фильтр «без красной зоны»).
 * Equity и просадка — по кумулятивному PnL на датах выхода (не полный 15м replay).
 */
internal fun buildStrategyTestMetricsForClosedTrades(
    base: PortfolioMetrics,
    closedTrades: List<PortfolioClosedTrade>,
    includeOpenPosition: Boolean = false,
    periodSuffix: String = " · без красной зоны",
): PortfolioMetrics {
    val sorted = closedTrades.sortedBy { parseSimTradeExitMillis(it.exitDate) ?: Long.MAX_VALUE }
    val realizedRub = sorted.sumOf { it.pnlRubApprox }
    val realizedSpread = sorted.sumOf { it.pnlSpreadPoints }
    val totalCommission = sorted.sumOf { it.commissionRubApprox }
    val totalOvernight = sorted.sumOf { it.overnightRubApprox }

    var cumulative = 0.0
    val exitLabels = ArrayList<String>(sorted.size)
    val equitySeries = ArrayList<Double>(sorted.size)
    for (trade in sorted) {
        cumulative += trade.pnlRubApprox
        exitLabels += trade.exitDate
        equitySeries += cumulative
    }
    val (curveLabels, curveEquity, curveDrawdown) = equityCurveDailyForChart(exitLabels, equitySeries)

    var peak = 0.0
    var maxDdRub = 0.0
    var maxDdPct = 0.0
    for (eq in equitySeries) {
        peak = max(peak, eq)
        val dd = peak - eq
        if (dd > maxDdRub) maxDdRub = dd
        if (peak != 0.0) {
            val pct = dd / abs(peak) * 100.0
            if (pct > maxDdPct) maxDdPct = pct
        }
    }

    val wins = sorted.count { it.pnlRubApprox > 0 }
    val losses = sorted.count { it.pnlRubApprox < 0 }
    val winRate = if (sorted.isEmpty()) 0.0 else wins * 100.0 / sorted.size
    val grossWin = sorted.filter { it.pnlRubApprox > 0 }.sumOf { it.pnlRubApprox }
    val grossLoss = sorted.filter { it.pnlRubApprox < 0 }.sumOf { -it.pnlRubApprox }
    val profitFactor = if (grossLoss > 0) grossWin / grossLoss else null
    val winRubList = sorted.mapNotNull { if (it.pnlRubApprox > 0) it.pnlRubApprox else null }
    val lossRubList = sorted.mapNotNull { if (it.pnlRubApprox < 0) it.pnlRubApprox else null }
    val avgWin = if (winRubList.isEmpty()) 0.0 else winRubList.average()
    val avgLoss = if (lossRubList.isEmpty()) 0.0 else lossRubList.average()
    val largestWin = winRubList.maxOrNull() ?: 0.0
    val largestLoss = lossRubList.minOrNull() ?: 0.0

    val open = if (includeOpenPosition) base.openPosition else null
    val unrealized = open?.unrealizedRubApprox ?: 0.0
    val unrealizedSpread = open?.unrealizedPnlSpread ?: 0.0
    val totalRub = realizedRub + unrealized

    return base.copy(
        periodDescription = base.periodDescription + periodSuffix,
        totalCommissionRub = totalCommission,
        totalOvernightRub = totalOvernight,
        closedTrades = sorted,
        openPosition = open,
        cumulativeRealizedSpread = realizedSpread,
        cumulativeRealizedRubApprox = realizedRub,
        unrealizedRubApprox = unrealized,
        totalPnlSpread = realizedSpread + unrealizedSpread,
        totalPnlRubApprox = totalRub,
        totalReturnPercent = if (base.notionalRub > 0) totalRub / base.notionalRub * 100.0 else 0.0,
        maxDrawdownRubApprox = maxDdRub,
        maxDrawdownPercent = maxDdPct,
        winCount = wins,
        lossCount = losses,
        winRate = winRate,
        profitFactor = profitFactor,
        avgWinRub = avgWin,
        avgLossRub = avgLoss,
        largestWinRub = largestWin,
        largestLossRub = largestLoss,
        equityCurveLabels = curveLabels,
        equityCurveRub = curveEquity,
        drawdownCurveRub = curveDrawdown,
    )
}

/** Метрики для UI «Тест страт.» с учётом переключателя «Без красной зоны». */
internal fun strategyTestMetricsForDisplay(
    metrics: PortfolioMetrics?,
    displayTradeItems: List<StrategyTestTradeItem>,
    excludeRedZone: Boolean,
): PortfolioMetrics? {
    val base = metrics ?: return null
    if (!excludeRedZone) return base
    return buildStrategyTestMetricsForClosedTrades(
        base = base,
        closedTrades = displayTradeItems.map { it.trade },
        includeOpenPosition = false,
    )
}

internal fun StrategyTestMonthlyReturnSummary.monthlyBarsForDisplay(
    excludeRedZone: Boolean,
    tradeItems: List<StrategyTestTradeItem>,
    tradeRiskAssessments: List<StrategyTestTradeRiskAssessment>,
): List<StrategyTestMonthlyReturnBar> {
    if (!excludeRedZone) return monthlyBars
    val safeTrades = filterStrategyTestTradeItemsExcludingRedZone(tradeItems, tradeRiskAssessments)
        .map { it.trade }
    return buildStrategyTestMonthlyReturnBars(safeTrades, notionalRub)
}

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
    /** Закрытие на следующий календарный день после входа (2-й день удержания, МСК). */
    val closedSecondDay: StrategyTestDurationBucket,
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
    val secondDayTrades = mutableListOf<PortfolioClosedTrade>()
    val detailOrder = listOf("< 1 дн.", "1–2 дн.", "2–3 дн.", "3–5 дн.", "> 5 дн.")
    val detailAcc = detailOrder.associateWith { mutableListOf<PortfolioClosedTrade>() }.toMutableMap()
    for (trade in trades) {
        val durationMs = simTradeDurationMillis(trade.entryDate, trade.exitDate) ?: continue
        if (durationMs <= STRATEGY_TEST_TWO_DAYS_MS) shortTrades += trade else longTrades += trade
        if (isSimTradeClosedOnSecondCalendarDay(trade.entryDate, trade.exitDate)) {
            secondDayTrades += trade
        }
        detailAcc[strategyTestDurationBucketTitle(durationMs)]?.add(trade)
    }
    return StrategyTestDurationSummary(
        short = bucketFromTrades("≤ 2 сут", shortTrades),
        long = bucketFromTrades("> 2 сут", longTrades),
        closedSecondDay = bucketFromTrades("закр. 2-й дн.", secondDayTrades),
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

internal fun formatStrategyTestMonthlyReturnSubtitle(
    summary: StrategyTestMonthlyReturnSummary,
    excludeRedZone: Boolean = false,
): String =
    buildString {
        if (excludeRedZone && summary.redZoneTradeCount > 0) {
            append("без красной: ")
            append(formatPercentSigned(summary.withoutRedZone.avgMonthlyReturnPercent))
            append("/мес · всего ")
            append(formatPercentSigned(summary.allTrades.avgMonthlyReturnPercent))
            append("/мес")
        } else {
            append("ср. ")
            append(formatPercentSigned(summary.allTrades.avgMonthlyReturnPercent))
            append("/мес · без красной: ")
            append(formatPercentSigned(summary.withoutRedZone.avgMonthlyReturnPercent))
        }
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
