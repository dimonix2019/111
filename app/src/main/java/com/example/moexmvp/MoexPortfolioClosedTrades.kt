package com.example.moexmvp

import java.util.Locale
import kotlin.math.max

/**
 * Закрытые сделки по парам «открытая запись на демо + выход в журнале сигналов»,
 * если replay по 15м ряду не построил строку (рассинхрон режима ручной/авто, снап и т.п.).
 */
internal fun buildClosedRowsFromSandboxOpensAndJournalExits(
    openExecutions: List<SandboxSpreadExecUi>,
    allJournalEvents: List<StrategySignalEvent>,
    points: List<DataPoint>,
    ledger: List<PortfolioExecutionLedgerEntry>,
    pushLog: List<PushNotificationLogEntry>,
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
    portfolioLedgerIncludeAuto: Boolean
): Pair<List<PortfolioConfirmedTradeTableRow>, List<SandboxSpreadExecUi>> {
    if (openExecutions.isEmpty() || points.size < 2 || allJournalEvents.isEmpty()) {
        return emptyList<PortfolioConfirmedTradeTableRow>() to openExecutions
    }
    val allowedPairs = ledgerEntryPairsForPortfolioReplay(ledger, portfolioLedgerIncludeAuto)
    val allowAllOpens = ledger.isEmpty()
    if (!allowAllOpens && allowedPairs.isEmpty()) {
        return emptyList<PortfolioConfirmedTradeTableRow>() to openExecutions
    }

    val effectiveNotionalRub = notionalRub * leverage
    val commissionPerSideRub = effectiveNotionalRub * (commissionPercentPerSide / 100.0)
    val borrowedRub = notionalRub * (leverage - 1.0).coerceAtLeast(0.0)
    val overnightFeePerDayRub = borrowedRub * (TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)

    val remainingExits = allJournalEvents
        .filter {
            it.signalType == StrategySignalType.ExitLong ||
                it.signalType == StrategySignalType.ExitShort
        }
        .sortedBy { it.timestampMillis }
        .toMutableList()
    val closedRows = mutableListOf<PortfolioConfirmedTradeTableRow>()
    val stillOpen = mutableListOf<SandboxSpreadExecUi>()
    var tradeSeq = 0

    for (open in openExecutions.sortedBy { it.barTimestampMillis }) {
        if (!allowAllOpens &&
            !ledgerEntryMatchesSignalBar(allowedPairs, open.signalType, open.barTimestampMillis)
        ) {
            stillOpen += open
            continue
        }
        val exitType = when (open.signalType) {
            StrategySignalType.EnterLong -> StrategySignalType.ExitLong
            StrategySignalType.EnterShort -> StrategySignalType.ExitShort
            else -> {
                stillOpen += open
                continue
            }
        }
        val exitIdx = remainingExits.indexOfFirst { ev ->
            ev.signalType == exitType && ev.timestampMillis >= open.barTimestampMillis
        }
        if (exitIdx < 0) {
            stillOpen += open
            continue
        }
        val exitEvent = remainingExits.removeAt(exitIdx)
        val entrySnaps = snapStrategySignalEventsToExecutionPoints(points, listOf(open.toSyntheticEnterEvent()))
        val exitSnaps = snapStrategySignalEventsToExecutionPoints(points, listOf(exitEvent))
        val entryPoint = entrySnaps.firstOrNull()?.point
        val exitPoint = exitSnaps.firstOrNull()?.point
        if (entryPoint == null || exitPoint == null) {
            stillOpen += open
            continue
        }
        val entrySpread = resolveEntrySpreadPercent(
            open.entrySpreadPercent,
            open.barTimestampMillis,
            points
        )
        val direction = when (open.signalType) {
            StrategySignalType.EnterLong -> ZStrategyPosition.Long
            StrategySignalType.EnterShort -> ZStrategyPosition.Short
            else -> ZStrategyPosition.Flat
        }
        val pnlTriple = when (direction) {
            ZStrategyPosition.Long -> {
                val spread = exitPoint.spreadPercent - entrySpread
                val gross = spreadPnlToRubApprox(spread, effectiveNotionalRub)
                val overnight = overnightFeePerDayRub * overnightDaysForClosed(entryPoint.tradeDate, exitPoint.tradeDate)
                Triple(spread, gross, gross - (2 * commissionPerSideRub) - overnight)
            }
            ZStrategyPosition.Short -> {
                val spread = entrySpread - exitPoint.spreadPercent
                val gross = spreadPnlToRubApprox(spread, effectiveNotionalRub)
                val overnight = overnightFeePerDayRub * overnightDaysForClosed(entryPoint.tradeDate, exitPoint.tradeDate)
                Triple(spread, gross, gross - (2 * commissionPerSideRub) - overnight)
            }
            ZStrategyPosition.Flat -> null
        }
        if (pnlTriple == null) {
            stillOpen += open
            continue
        }
        val (_, grossPnlRub, netPnlRub) = pnlTriple
        tradeSeq += 1
        val legs = legSpreadDisplay(direction)
        val tag = spreadLegPushCorrelationTag(open.barTimestampMillis, open.signalType)
        val halfNet = netPnlRub / 2.0
        val (commRub, ovnRub) = portfolioTradeCommissionAndOvernightRub(
            notionalRub = notionalRub,
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            entryDateLabel = entryPoint.tradeDate,
            exitDateLabel = exitPoint.tradeDate,
            includeExitCommission = true
        )
        val (sigId, sigBar, sigRecv) = entrySignalDisplayFields(
            journalEvents = allJournalEvents,
            barTimestampMillis = open.barTimestampMillis,
            signalType = open.signalType,
            fallbackReceivedAtMillis = open.executedAtMillis
        )
        val tradeDisplayId = entryTradeDisplayId(
            journalEvents = allJournalEvents,
            barTimestampMillis = open.barTimestampMillis,
            signalType = open.signalType,
            fallbackReceivedAtMillis = open.executedAtMillis,
        )
        closedRows += PortfolioConfirmedTradeTableRow(
            tradeId = "T-O%03d".format(Locale.US, tradeSeq),
            tradeDisplayId = tradeDisplayId,
            directionLabel = if (direction == ZStrategyPosition.Short) "short" else "long",
            entryTimeMsk = formatPortfolioExecutionTableMsk(open.executedAtMillis),
            exitTimeMsk = formatPortfolioExecutionTableMsk(exitPoint.timestampMillis),
            longLegTicker = legs.longTicker,
            shortLegTicker = legs.shortTicker,
            longLegSideRu = legs.longSideRu,
            shortLegSideRu = legs.shortSideRu,
            volumeText = "1+1 лот",
            confirmLabel = open.confirmLabel,
            entryZ = open.zScore,
            exitZ = exitEvent.zScore,
            notificationIdsText = formatPushIdsForCorrelation(pushLog, tag),
            legLongPnlSplitRubApprox = halfNet,
            legShortPnlSplitRubApprox = halfNet,
            grossPnlRubApprox = grossPnlRub,
            netPnlRubApprox = netPnlRub,
            commissionRubApprox = commRub,
            overnightRubApprox = ovnRub,
            entrySignalId = sigId,
            entrySignalBarTimeMsk = sigBar,
            entrySignalReceivedMsk = sigRecv
        )
    }
    return closedRows to stillOpen
}

private fun SandboxSpreadExecUi.toSyntheticEnterEvent(): StrategySignalEvent =
    StrategySignalEvent(
        timestampMillis = barTimestampMillis,
        signalType = signalType,
        zScore = zScore,
        receivedAtMillis = executedAtMillis
    )

private fun overnightDaysForClosed(entryDate: String, exitDate: String): Long {
    val entry = runCatching { java.time.LocalDate.parse(entryDate.take(10)) }.getOrNull() ?: return 0L
    val exit = runCatching { java.time.LocalDate.parse(exitDate.take(10)) }.getOrNull() ?: return 0L
    return max(0L, java.time.temporal.ChronoUnit.DAYS.between(entry, exit))
}

/** Объединить закрытые строки replay и синтеза; при дубликате предпочитаем replay (T-xxx). */
internal fun mergeClosedPortfolioTableRows(
    fromReplay: List<PortfolioConfirmedTradeTableRow>,
    fromOpens: List<PortfolioConfirmedTradeTableRow>
): List<PortfolioConfirmedTradeTableRow> {
    if (fromOpens.isEmpty()) return fromReplay
    if (fromReplay.isEmpty()) return fromOpens
    val replayKeys = fromReplay.map { closedTradeDedupKey(it) }.toSet()
    val extra = fromOpens.filter { closedTradeDedupKey(it) !in replayKeys }
    return fromReplay + extra
}

private fun closedTradeDedupKey(row: PortfolioConfirmedTradeTableRow): String =
    "${row.directionLabel}|${row.entryTimeMsk}|${row.exitTimeMsk}"
