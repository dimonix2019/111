package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Маркер Z в журнале для кнопок «тестовый сигнал» / «тестовая пара» (не с реального бара). */
internal const val PORTFOLIO_TEST_SIGNAL_Z_MARKER = 999.0

internal fun emitTestStrategyEntrySignal(context: Context, signalType: StrategySignalType) {
    require(signalType == StrategySignalType.EnterLong || signalType == StrategySignalType.EnterShort)
    val app = context.applicationContext
    val ts = System.currentTimeMillis()
    recordStrategySignalEvent(
        context = app,
        signalType = signalType,
        zScore = PORTFOLIO_TEST_SIGNAL_Z_MARKER,
        timestampMillis = ts,
        skipJournalWallDedup = true,
        savePendingVirtualTradeIfEntry = true
    )
}

/**
 * Две рыночные заявки на демо (как «Принять»): журнал + реестр исполнений с [PortfolioExecSource.MANUAL], позиция Z.
 */
internal suspend fun executeTestSandboxSpreadPair(
    context: Context,
    signalType: StrategySignalType
): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        require(signalType == StrategySignalType.EnterLong || signalType == StrategySignalType.EnterShort)
        val app = context.applicationContext
        if (!TinkoffSandboxStorage.isExecuteSignalsOnSandbox(app)) {
            error("Включите «Исполнять вход по сигналу на демо-счёт».")
        }
        when (TinkoffSandboxStorage.resolveExecUiState(app)) {
            SandboxExecUiState.Off -> error("Исполнение на демо выключено.")
            SandboxExecUiState.MissingCredentials -> error("Укажите токен и счёт песочницы (вкладка «Песочница»).")
            SandboxExecUiState.Ready -> Unit
        }
        val tok = TinkoffSandboxStorage.getToken(app) ?: error("Нет токена.")
        val acc = TinkoffSandboxStorage.getAccountId(app) ?: error("Нет счёта.")
        val barTs = System.currentTimeMillis()
        val legs = tinkoffSandboxExecuteSpreadEntryDetailed(tok, acc, signalType)
        markVirtualTradeConsumedForJournalEntry(app, signalType, barTs)
        TinkoffSandboxSpreadExecLog.record(
            app,
            signalType,
            PORTFOLIO_TEST_SIGNAL_Z_MARKER,
            System.currentTimeMillis()
        )
        appendPortfolioExecutionLedger(
            app,
            barTimestampMillis = barTs,
            signalType = signalType,
            source = PortfolioExecSource.MANUAL
        )
        val recent = loadStrategySignalEvents(app)
        val last = recent.lastOrNull()
        val skipDup = last != null &&
            last.signalType == signalType &&
            last.timestampMillis == barTs
        if (!skipDup) {
            recordStrategySignalEvent(
                context = app,
                signalType = signalType,
                zScore = PORTFOLIO_TEST_SIGNAL_Z_MARKER,
                timestampMillis = barTs,
                skipJournalWallDedup = true,
                savePendingVirtualTradeIfEntry = false
            )
        }
        val position = when (signalType) {
            StrategySignalType.EnterLong -> ZStrategyPosition.Long
            StrategySignalType.EnterShort -> ZStrategyPosition.Short
            else -> ZStrategyPosition.Flat
        }
        saveStrategyPosition(app, position)
        notifySandboxSpreadLegExecutionResults(
            app,
            legs,
            DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            TinkoffSandboxStorage.getSandboxNotifyLeverage(app)
        )
    }
}
