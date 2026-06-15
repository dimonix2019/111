package com.example.moexmvp

import android.content.Context
import java.time.LocalDate

/** Открытая авто-сделка на демо, соответствующая сигналу выхода стратегии (ручные не учитываются). */
internal fun findOpenTradeForStrategyExit(
    openExecutions: List<SandboxSpreadExecUi>,
    exitSignalType: StrategySignalType,
): SandboxSpreadExecUi? {
    val entrySignal = when (exitSignalType) {
        StrategySignalType.ExitLong -> StrategySignalType.EnterLong
        StrategySignalType.ExitShort -> StrategySignalType.EnterShort
        else -> return null
    }
    return openExecutions
        .filter { it.signalType == entrySignal && it.source == PortfolioExecSource.AUTO }
        .maxByOrNull { it.barTimestampMillis }
}

/**
 * Ручное закрытие одной открытой сделки на вкладке «Портфель»:
 * обратные заявки на демо (если включено), запись выхода в журнал, снятие с открытых.
 */
internal suspend fun closePortfolioOpenTrade(
    context: Context,
    execution: SandboxSpreadExecUi,
): Result<String> = runCatching {
    val entrySignal = execution.signalType
    require(entrySignal == StrategySignalType.EnterLong || entrySignal == StrategySignalType.EnterShort) {
        "Неподдерживаемый тип входа: $entrySignal"
    }
    val exitSignal = when (entrySignal) {
        StrategySignalType.EnterLong -> StrategySignalType.ExitLong
        StrategySignalType.EnterShort -> StrategySignalType.ExitShort
        else -> error("unreachable")
    }
    val legs = executeSpreadExitIfConfigured(context, entrySignal)
    val (exitTs, exitZ) = resolveExitBarForPortfolioClose(context, execution, null, null)
    finalizePortfolioOpenTradeClose(
        context = context,
        execution = execution,
        recordExitInJournal = true,
        exitZScore = exitZ,
        exitTimestampMillis = exitTs,
    ).getOrThrow()
    notifySandboxTradeClosedAfterClose(
        context = context,
        execution = execution,
        exitSignalType = exitSignal,
        exitBarTimestampMillis = exitTs,
        exitZScore = exitZ,
        exitLegs = legs,
    )
    "Сделка ${execution.tradeId} закрыта"
}

/**
 * Снятие открытой сделки с демо после выхода: опционально запись в журнал,
 * удаление из ledger и списка открытых.
 */
internal suspend fun finalizePortfolioOpenTradeClose(
    context: Context,
    execution: SandboxSpreadExecUi,
    recordExitInJournal: Boolean,
    exitZScore: Double? = null,
    exitTimestampMillis: Long? = null,
): Result<Unit> = runCatching {
    val entrySignal = execution.signalType
    require(entrySignal == StrategySignalType.EnterLong || entrySignal == StrategySignalType.EnterShort) {
        "Неподдерживаемый тип входа: $entrySignal"
    }
    val exitSignal = when (entrySignal) {
        StrategySignalType.EnterLong -> StrategySignalType.ExitLong
        StrategySignalType.EnterShort -> StrategySignalType.ExitShort
        else -> error("unreachable")
    }

    if (recordExitInJournal) {
        val (tsExit, lastZ) = resolveExitBarForPortfolioClose(context, execution, exitZScore, exitTimestampMillis)
        recordStrategySignalEvent(
            context = context,
            signalType = exitSignal,
            zScore = lastZ,
            timestampMillis = tsExit,
            skipJournalWallDedup = true,
            savePendingVirtualTradeIfEntry = false,
        )
    }

    removePortfolioExecutionLedgerEntry(
        context = context,
        barTimestampMillis = execution.barTimestampMillis,
        signalType = entrySignal,
        source = execution.source,
    )
    val removed = TinkoffSandboxSpreadExecLog.removeByTradeId(context, execution.tradeId)
    if (!removed) {
        throw IllegalStateException("Сделка ${execution.tradeId} не найдена в списке открытых")
    }

    val remaining = TinkoffSandboxSpreadExecLog.loadRecent(context)
    val savedPos = loadSavedStrategyPosition(context)
    val posFromThis = when (entrySignal) {
        StrategySignalType.EnterLong -> ZStrategyPosition.Long
        StrategySignalType.EnterShort -> ZStrategyPosition.Short
        else -> ZStrategyPosition.Flat
    }
    if (savedPos == posFromThis && remaining.none { openMatchesSavedPosition(it, savedPos) }) {
        saveStrategyPosition(context, ZStrategyPosition.Flat)
    }
}

internal suspend fun executeSpreadExitIfConfigured(
    context: Context,
    entrySignal: StrategySignalType,
): List<SandboxLegOrderResult> {
    val mode = currentExecutionMode(context)
    val tok = TinkoffSandboxStorage.getActiveToken(context, mode)
    val acc = TinkoffSandboxStorage.getActiveAccountId(context, mode)
    if (TinkoffSandboxStorage.isExecuteSignalsOnSandbox(context) &&
        !tok.isNullOrBlank() &&
        !acc.isNullOrBlank()
    ) {
        return tinkoffExecuteSpreadExitDetailed(mode, tok, acc, entrySignal)
    }
    return emptyList()
}

internal suspend fun notifySandboxTradeClosedAfterClose(
    context: Context,
    execution: SandboxSpreadExecUi,
    exitSignalType: StrategySignalType,
    exitBarTimestampMillis: Long,
    exitZScore: Double,
    exitLegs: List<SandboxLegOrderResult> = emptyList(),
    notionalRub: Double = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
    leverage: Double = TinkoffSandboxStorage.getSandboxNotifyLeverage(context),
    commissionPercentPerSide: Double = 0.04,
) {
    val app = context.applicationContext
    val exitSpread = resolveSpreadPercentAtBar(app, exitBarTimestampMillis, execution.entrySpreadPercent)
    val entryDate = portfolioDateLabelFromMskTableTime(execution.entryTimeMsk)
    val exitDate = portfolioDateLabelFromMskTableTime(
        formatPortfolioExecutionTableMsk(exitBarTimestampMillis)
    )
    val pnl = computeSandboxClosedTradePnl(
        execution = execution,
        exitSpreadPercent = exitSpread,
        entryDateLabel = entryDate,
        exitDateLabel = exitDate,
        notionalRub = notionalRub,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
    )
    val accountTotalPnlRub = SandboxAccountPnlLedger.addClosedTradeNetPnlRub(app, pnl.netRub)
    val lastLeg = exitLegs.lastOrNull()
    notifySandboxTradeClosed(
        context = app,
        execution = execution,
        exitSignalType = exitSignalType,
        exitBarTimestampMillis = exitBarTimestampMillis,
        exitZScore = exitZScore,
        tradePnl = pnl,
        accountTotalPnlRub = accountTotalPnlRub,
        portfolioTotalRub = lastLeg?.portfolioTotalRub,
        portfolioCashRub = lastLeg?.portfolioCashRub,
        notionalRub = notionalRub,
        leverage = leverage,
    )
}

private suspend fun resolveExitBarForPortfolioClose(
    context: Context,
    execution: SandboxSpreadExecUi,
    exitZScore: Double?,
    exitTimestampMillis: Long?,
): Pair<Long, Double> {
    if (exitZScore != null && exitTimestampMillis != null) {
        return exitTimestampMillis to exitZScore
    }
    val till = LocalDate.now(moexZoneId)
    val pts = loadPortfolio15mDataPoints(
        context,
        till.minusDays(3),
        till,
        PortfolioM15LoadMode.INCREMENTAL,
    )
    val lastPt = pts.lastOrNull()
    return Pair(
        lastPt?.timestampMillis ?: System.currentTimeMillis(),
        lastPt?.zScore ?: execution.zScore,
    )
}

private fun openMatchesSavedPosition(
    exec: SandboxSpreadExecUi,
    position: ZStrategyPosition,
): Boolean = when (position) {
    ZStrategyPosition.Long -> exec.signalType == StrategySignalType.EnterLong
    ZStrategyPosition.Short -> exec.signalType == StrategySignalType.EnterShort
    ZStrategyPosition.Flat -> false
}
