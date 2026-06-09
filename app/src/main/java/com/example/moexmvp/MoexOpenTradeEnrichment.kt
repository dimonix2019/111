package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Подстановка актуального Z и оценки PnL для открытых сделок на демо при обновлении 15м ряда.
 */
internal fun enrichOpenSandboxExecutions(
    executions: List<SandboxSpreadExecUi>,
    points: List<DataPoint>,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double = 0.04
): List<SandboxSpreadExecUi> {
    if (executions.isEmpty() || points.isEmpty()) return executions
    val last = points.last()
    return executions.map { exec ->
        if (exec.signalType != StrategySignalType.EnterLong &&
            exec.signalType != StrategySignalType.EnterShort
        ) {
            return@map exec
        }
        val entryZ = if (exec.zScore == PORTFOLIO_TEST_SIGNAL_Z_MARKER) last.zScore else exec.zScore
        val entrySpread = resolveEntrySpreadPercent(
            exec.entrySpreadPercent,
            exec.barTimestampMillis,
            points
        )
        val entryDateLabel = points.minByOrNull { kotlin.math.abs(it.timestampMillis - exec.barTimestampMillis) }
            ?.tradeDate ?: exec.entryTimeMsk
        val grossRub = estimateOpenSpreadMtmGrossRub(
            signalType = exec.signalType,
            entrySpreadPercent = entrySpread,
            currentSpreadPercent = last.spreadPercent,
            notionalRub = notionalRub,
            leverage = leverage,
            points = points,
            barTimestampMillis = exec.barTimestampMillis
        )
        val (commRub, ovnRub) = portfolioTradeCommissionAndOvernightRub(
            notionalRub = notionalRub,
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            entryDateLabel = entryDateLabel,
            exitDateLabel = last.tradeDate,
            includeExitCommission = false
        )
        val mtmRub = grossRub - commRub - ovnRub
        val half = mtmRub / 2.0
        exec.copy(
            zScore = entryZ,
            entrySpreadPercent = entrySpread,
            exitZDisplay = last.zScore,
            legLongPnlSplitRubApprox = half,
            legShortPnlSplitRubApprox = half,
            netPnlRubApprox = mtmRub,
            commissionRubApprox = commRub,
            overnightRubApprox = ovnRub
        )
    }
}

internal fun openSandboxExecutionsNeedMtmEnrichment(executions: List<SandboxSpreadExecUi>): Boolean =
    executions.any { exec ->
        (exec.signalType == StrategySignalType.EnterLong ||
            exec.signalType == StrategySignalType.EnterShort) &&
            (exec.netPnlRubApprox.isNaN() || exec.exitZDisplay.isNaN())
    }

/** Синхронный MTM для UI, если в state ещё сырые записи из SQLite prefs. */
internal fun enrichSandboxExecutionsIfNeeded(
    executions: List<SandboxSpreadExecUi>,
    points: List<DataPoint>,
    leverage: Double,
    commissionPercentPerSide: Double,
): List<SandboxSpreadExecUi> {
    if (executions.isEmpty() || points.isEmpty() || !openSandboxExecutionsNeedMtmEnrichment(executions)) {
        return executions
    }
    return enrichOpenSandboxExecutions(
        executions = executions,
        points = points,
        notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide
    )
}

internal suspend fun MoexScreenState.resolveEnrichmentPoints(): List<DataPoint> {
    if (portfolioM15Points.isNotEmpty()) return portfolioM15Points
    if (marketsM15Source().isNotEmpty()) return marketsM15Source()
    val cached = withContext(Dispatchers.IO) {
        loadPortfolio15mPointsForSignalMonitor(context, PortfolioM15LoadMode.CACHE_ONLY)
    }
    if (cached.isNotEmpty()) {
        if (portfolioM15Points.isEmpty()) portfolioM15Points = cached
        if (marketsM15Source().isEmpty()) storeMarketsM15(cached)
        return cached
    }
    val snapshotPoints = lastGoodMarkets?.points.orEmpty()
    if (snapshotPoints.isNotEmpty()) {
        return withContext(Dispatchers.Default) {
            applyZScoresDefault(snapshotPoints)
        }
    }
    return emptyList()
}

/** Пересчёт Z/PnL открытых сделок демо по последнему 15м ряду. */
internal suspend fun MoexScreenState.syncSandboxExecutionsEnrichment(
    journalEvents: List<StrategySignalEvent> = signalEvents
) {
    val raw = withContext(Dispatchers.IO) {
        TinkoffSandboxSpreadExecLog.loadRecent(context)
    }
    if (raw.isEmpty()) {
        sandboxSpreadExecutions = emptyList()
        return
    }
    val points = resolveEnrichmentPoints()
    val ledgerIncludeAuto = portfolioLedgerIncludeAuto
    if (points.isEmpty()) {
        sandboxSpreadExecutions = filterSandboxExecutionsByPortfolioMode(raw, ledgerIncludeAuto)
        return
    }
    val ledger = withContext(Dispatchers.IO) { loadPortfolioExecutionLedger(context) }
    val opensAfterJournalClose = withContext(Dispatchers.Default) {
        buildClosedRowsFromSandboxOpensAndJournalExits(
            openExecutions = raw,
            allJournalEvents = journalEvents,
            points = points,
            ledger = ledger,
            pushLog = emptyList(),
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = portfolioLeverage,
            commissionPercentPerSide = portfolioCommissionPercent,
            portfolioLedgerIncludeAuto = ledgerIncludeAuto
        ).second
    }
    val modeFiltered = filterSandboxExecutionsByPortfolioMode(opensAfterJournalClose, ledgerIncludeAuto)
    sandboxSpreadExecutions = withContext(Dispatchers.IO) {
        TinkoffSandboxSpreadExecLog.enrichForDisplay(
            context = context,
            executions = modeFiltered,
            points = points,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = portfolioLeverage,
            commissionPercentPerSide = portfolioCommissionPercent,
            journalEvents = journalEvents
        )
    }
}

/** Открытые и закрытые сделки портфеля для маркеров на Z-графике «Рынок». */
internal suspend fun loadPortfolioTradesForZChart(
    context: Context,
    points: List<DataPoint>,
    leverage: Double,
    commissionPercentPerSide: Double,
): Pair<List<SandboxSpreadExecUi>, List<PortfolioConfirmedTradeTableRow>> {
    if (points.size < 2) return emptyList<SandboxSpreadExecUi>() to emptyList()
    return withContext(Dispatchers.IO) {
        val eventsAll = loadStrategySignalEvents(
            context = context,
            fromTimestampMillis = points.first().timestampMillis,
        )
        val ledger = loadPortfolioExecutionLedger(context)
        val chartEvents = journalEventsForChartTrades(eventsAll, ledger)
        val pushLog = loadPushNotificationLog(context)
        val executed = withContext(Dispatchers.Default) {
            buildExecutedPortfolioWithTable(
                points = points,
                events = chartEvents,
                ledger = ledger,
                pushLog = pushLog,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = leverage,
                commissionPercentPerSide = commissionPercentPerSide,
                periodDescription = "z-chart",
            )
        }
        val sandboxRaw = TinkoffSandboxSpreadExecLog.loadRecent(context)
        val (closedFromOpens, opensAfterJournalClose) = withContext(Dispatchers.Default) {
            buildClosedRowsFromSandboxOpensAndJournalExits(
                openExecutions = sandboxRaw,
                allJournalEvents = eventsAll,
                points = points,
                ledger = ledger,
                pushLog = pushLog,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = leverage,
                commissionPercentPerSide = commissionPercentPerSide,
                portfolioLedgerIncludeAuto = true,
                includeAllLedgerEntries = true,
            )
        }
        val closed = mergeClosedPortfolioTableRows(executed.tableRows, closedFromOpens)
        val opens = TinkoffSandboxSpreadExecLog.enrichForDisplay(
            context = context,
            executions = opensAfterJournalClose,
            points = points,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            journalEvents = eventsAll,
        )
        opens to closed
    }
}
