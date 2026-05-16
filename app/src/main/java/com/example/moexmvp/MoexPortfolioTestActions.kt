package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Маркер Z в журнале для кнопок «тестовый сигнал» / «тестовая пара» (не с реального бара). */
internal const val PORTFOLIO_TEST_SIGNAL_Z_MARKER = 999.0

internal data class PortfolioTestEntrySignalResult(
    val toastMessage: String,
    val sandboxSpreadExecReloadDelta: Int
)

/**
 * Полный путь тестового входа: как у реального сигнала — позиция Z, журнал, при готовой песочнице
 * две заявки (авто или сразу «как Принять»), без второй строки журнала для того же [barTs].
 */
internal suspend fun runPortfolioTestEntrySignalFull(
    context: Context,
    signalType: StrategySignalType
): Result<PortfolioTestEntrySignalResult> = withContext(Dispatchers.IO) {
    runCatching {
        require(signalType == StrategySignalType.EnterLong || signalType == StrategySignalType.EnterShort)
        val app = context.applicationContext
        val barTs = System.currentTimeMillis()
        val dir = if (signalType == StrategySignalType.EnterShort) "SHORT" else "LONG"
        val position = when (signalType) {
            StrategySignalType.EnterLong -> ZStrategyPosition.Long
            StrategySignalType.EnterShort -> ZStrategyPosition.Short
            else -> ZStrategyPosition.Flat
        }
        saveStrategyPosition(app, position)
        recordStrategySignalEvent(
            context = app,
            signalType = signalType,
            zScore = PORTFOLIO_TEST_SIGNAL_Z_MARKER,
            timestampMillis = barTs,
            skipJournalWallDedup = true,
            savePendingVirtualTradeIfEntry = true
        )
        val execOn = TinkoffSandboxStorage.isExecuteSignalsOnSandbox(app)
        val execState = TinkoffSandboxStorage.resolveExecUiState(app)
        if (!execOn || execState != SandboxExecUiState.Ready) {
            return@runCatching PortfolioTestEntrySignalResult(
                toastMessage = "Тестовый сигнал $dir: журнал и позиция Z обновлены (Z=${PORTFOLIO_TEST_SIGNAL_Z_MARKER.toInt()} — метка теста). " +
                    "Включите «Исполнять вход на демо» и укажите токен/счёт песочницы, чтобы отправлялись заявки.",
                sandboxSpreadExecReloadDelta = 0
            )
        }
        if (TinkoffSandboxStorage.isSandboxSpreadAutoExecute(app)) {
            val ran = runSandboxAutoEntryIfNeeded(
                app,
                signalType,
                PORTFOLIO_TEST_SIGNAL_Z_MARKER,
                barTs
            )
            return@runCatching if (ran) {
                PortfolioTestEntrySignalResult(
                    toastMessage = "Тестовый $dir: авто-вход в песочницу — отправлены 2 заявки (Z=${PORTFOLIO_TEST_SIGNAL_Z_MARKER.toInt()}).",
                    sandboxSpreadExecReloadDelta = 1
                )
            } else {
                PortfolioTestEntrySignalResult(
                    toastMessage = "Тестовый $dir записан в журнал. Авто-вход не выполнен (см. уведомление или повтор с другим временем).",
                    sandboxSpreadExecReloadDelta = 0
                )
            }
        }
        val r = executeTestSandboxSpreadPair(
            app,
            signalType,
            journalBarTimestampMillis = barTs,
            skipStrategyJournalIfAlreadyRecorded = true
        )
        r.getOrThrow()
        PortfolioTestEntrySignalResult(
            toastMessage = "Тестовый $dir: в песочнице отправлены 2 заявки (как «Принять»), Z=${PORTFOLIO_TEST_SIGNAL_Z_MARKER.toInt()}.",
            sandboxSpreadExecReloadDelta = 1
        )
    }
}

/**
 * Две рыночные заявки на демо (как «Принять»): журнал + реестр исполнений с [PortfolioExecSource.MANUAL], позиция Z.
 *
 * @param journalBarTimestampMillis если задан — тот же штамп, что в журнале (ledger / push correlation).
 * @param skipStrategyJournalIfAlreadyRecorded не писать вторую строку в журнал (уже записана вызывающим кодом).
 */
internal suspend fun executeTestSandboxSpreadPair(
    context: Context,
    signalType: StrategySignalType,
    journalBarTimestampMillis: Long? = null,
    skipStrategyJournalIfAlreadyRecorded: Boolean = false
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
        val barTs = journalBarTimestampMillis ?: System.currentTimeMillis()
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
        if (!skipStrategyJournalIfAlreadyRecorded) {
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
            TinkoffSandboxStorage.getSandboxNotifyLeverage(app),
            spreadLegPushCorrelationTag(barTs, signalType)
        )
    }
}
