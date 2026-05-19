package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
internal data class PortfolioTestEntrySignalResult(
    val toastMessage: String,
    val sandboxSpreadExecReloadDelta: Int
)

/**
 * Полный путь тестового входа: Z и время бара — с последней 15м точки рынка; позиция Z, журнал;
 * при готовой песочнице две заявки (авто или «как Принять»).
 */
internal suspend fun runPortfolioTestEntrySignalFull(
    context: Context,
    signalType: StrategySignalType
): Result<PortfolioTestEntrySignalResult> = withContext(Dispatchers.IO) {
    runCatching {
        require(signalType == StrategySignalType.EnterLong || signalType == StrategySignalType.EnterShort)
        val app = context.applicationContext
        val market = loadCurrentPortfolioMarketSnapshot(app, forceNetworkRefresh = true)
        val barTs = market.timestampMillis
        val z = market.zScore
        val dir = if (signalType == StrategySignalType.EnterShort) "SHORT" else "LONG"
        val zText = String.format(Locale.US, "%.2f", z)
        val position = when (signalType) {
            StrategySignalType.EnterLong -> ZStrategyPosition.Long
            StrategySignalType.EnterShort -> ZStrategyPosition.Short
            else -> ZStrategyPosition.Flat
        }
        saveStrategyPosition(app, position)
        recordStrategySignalEvent(
            context = app,
            signalType = signalType,
            zScore = z,
            timestampMillis = barTs,
            skipJournalWallDedup = true,
            savePendingVirtualTradeIfEntry = true
        )
        val execState = TinkoffSandboxStorage.resolveExecUiState(app)
        if (execState != SandboxExecUiState.Ready) {
            return@runCatching PortfolioTestEntrySignalResult(
                toastMessage = "Тестовый сигнал $dir: журнал и позиция Z (Z=$zText). " +
                    "Укажите токен и счёт песочницы, чтобы отправлялись ордера.",
                sandboxSpreadExecReloadDelta = 0
            )
        }
        if (TinkoffSandboxStorage.isSandboxSpreadAutoExecute(app)) {
            val ran = runSandboxAutoEntryIfNeeded(
                app,
                signalType,
                z,
                barTs,
                market.entrySpreadPercent,
                fromTestButton = true
            )
            return@runCatching if (ran) {
                PortfolioTestEntrySignalResult(
                    toastMessage = "Тестовый $dir: авто-вход на демо — 2 ордера (Z=$zText).",
                    sandboxSpreadExecReloadDelta = 1
                )
            } else {
                PortfolioTestEntrySignalResult(
                    toastMessage = "Тестовый $dir записан (Z=$zText). Авто-вход не выполнен (см. уведомление).",
                    sandboxSpreadExecReloadDelta = 0
                )
            }
        }
        val r = executeTestSandboxSpreadPair(
            app,
            signalType,
            journalBarTimestampMillis = barTs,
            zScore = z,
            entrySpreadPercent = market.spreadPercent,
            skipStrategyJournalIfAlreadyRecorded = true,
            fromTestButton = true
        )
        r.getOrThrow()
        PortfolioTestEntrySignalResult(
            toastMessage = "Тестовый $dir: 2 ордера на демо (Z=$zText).",
            sandboxSpreadExecReloadDelta = 1
        )
    }
}

/**
 * Две рыночные заявки на демо: журнал + реестр [PortfolioExecSource.MANUAL], позиция Z.
 *
 * При [fromTestButton] всегда берётся свежий рынок (не дата/Z из карточки «Принять»).
 */
internal suspend fun executeTestSandboxSpreadPair(
    context: Context,
    signalType: StrategySignalType,
    journalBarTimestampMillis: Long? = null,
    zScore: Double? = null,
    entrySpreadPercent: Double? = null,
    skipStrategyJournalIfAlreadyRecorded: Boolean = false,
    fromTestButton: Boolean = false
): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
        require(signalType == StrategySignalType.EnterLong || signalType == StrategySignalType.EnterShort)
        val app = context.applicationContext
        when (TinkoffSandboxStorage.resolveExecUiState(app)) {
            SandboxExecUiState.MissingCredentials -> error("Укажите токен и счёт песочницы (вкладка «Песочница»).")
            SandboxExecUiState.Ready -> Unit
            SandboxExecUiState.Off -> Unit
        }
        val market = loadCurrentPortfolioMarketSnapshot(app, forceNetworkRefresh = fromTestButton)
        val barTs = if (fromTestButton) {
            market.timestampMillis
        } else {
            journalBarTimestampMillis ?: market.timestampMillis
        }
        val z = if (fromTestButton) {
            market.zScore
        } else {
            zScore ?: market.zScore
        }
        val entrySpread = if (fromTestButton) {
            market.spreadPercent
        } else {
            entrySpreadPercent ?: resolveSpreadPercentAtBar(app, barTs, market.spreadPercent)
        }
        val tok = TinkoffSandboxStorage.getToken(app) ?: error("Нет токена.")
        val acc = TinkoffSandboxStorage.getAccountId(app) ?: error("Нет счёта.")
        val legs = tinkoffSandboxExecuteSpreadEntryDetailed(tok, acc, signalType)
        val executedAt = System.currentTimeMillis()
        markVirtualTradeConsumedForJournalEntry(app, signalType, barTs)
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
                    zScore = z,
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
        TinkoffSandboxSpreadExecLog.recordFromLegs(
            app,
            signalType,
            z,
            barTimestampMillis = barTs,
            executedAtMillis = executedAt,
            entrySpreadPercent = entrySpread,
            source = PortfolioExecSource.MANUAL,
            legs = legs,
            fromTestButton = fromTestButton
        )
        notifySandboxSpreadLegExecutionResults(
            app,
            legs,
            DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            TinkoffSandboxStorage.getSandboxNotifyLeverage(app),
            spreadLegPushCorrelationTag(barTs, signalType)
        )
    }
}

/**
 * Кнопка «Тестовая пара» на вкладке «Портфель»: свежий рынок, позиция Z, журнал;
 * при авто-демо — [runSandboxAutoEntryIfNeeded], иначе две заявки как «Принять».
 */
internal suspend fun runPortfolioTestSpreadPairFull(
    context: Context,
    signalType: StrategySignalType
): Result<PortfolioTestEntrySignalResult> = withContext(Dispatchers.IO) {
    runCatching {
        require(signalType == StrategySignalType.EnterLong || signalType == StrategySignalType.EnterShort)
        val app = context.applicationContext
        val market = loadCurrentPortfolioMarketSnapshot(app, forceNetworkRefresh = true)
        val barTs = market.timestampMillis
        val z = market.zScore
        val dir = if (signalType == StrategySignalType.EnterShort) "SHORT" else "LONG"
        val zText = String.format(Locale.US, "%.2f", z)
        val position = when (signalType) {
            StrategySignalType.EnterLong -> ZStrategyPosition.Long
            StrategySignalType.EnterShort -> ZStrategyPosition.Short
            else -> ZStrategyPosition.Flat
        }
        saveStrategyPosition(app, position)
        val autoMode = TinkoffSandboxStorage.isSandboxSpreadAutoExecute(app)
        recordStrategySignalEvent(
            context = app,
            signalType = signalType,
            zScore = z,
            timestampMillis = barTs,
            skipJournalWallDedup = true,
            savePendingVirtualTradeIfEntry = !autoMode
        )
        val execState = TinkoffSandboxStorage.resolveExecUiState(app)
        if (execState != SandboxExecUiState.Ready) {
            return@runCatching PortfolioTestEntrySignalResult(
                toastMessage = "Тестовая пара $dir: журнал и позиция Z (Z=$zText). " +
                    "Укажите токен и счёт песочницы (вкладка «Песочница»).",
                sandboxSpreadExecReloadDelta = 0
            )
        }
        if (autoMode) {
            val ran = runSandboxAutoEntryIfNeeded(
                app,
                signalType,
                z,
                barTs,
                market.entrySpreadPercent,
                fromTestButton = true
            )
            return@runCatching if (ran) {
                PortfolioTestEntrySignalResult(
                    toastMessage = "Тестовая пара $dir: авто-вход на демо — 2 ордера (Z=$zText).",
                    sandboxSpreadExecReloadDelta = 1
                )
            } else {
                PortfolioTestEntrySignalResult(
                    toastMessage = "Тестовая пара $dir записана (Z=$zText). Авто-вход не выполнен (см. уведомление).",
                    sandboxSpreadExecReloadDelta = 0
                )
            }
        }
        PortfolioTestEntrySignalResult(
            toastMessage = "Тестовая пара $dir: подтвердите «Принять» в карточке (Z=$zText).",
            sandboxSpreadExecReloadDelta = 0
        )
    }
}
