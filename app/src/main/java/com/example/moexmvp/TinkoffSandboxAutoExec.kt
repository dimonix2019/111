package com.example.moexmvp

import android.content.Context

private val autoSpreadDedupLock = Any()
private const val AUTO_SPREAD_PREFS = "moex_sandbox_auto_spread_dedup"
private const val KEY_LAST_AUTO = "last_executed_bar_key"
private const val KEY_LAST_AUTO_EXIT = "last_executed_exit_bar_key"

internal fun clearSandboxAutoSpreadDedup(context: Context) {
    synchronized(autoSpreadDedupLock) {
        context.applicationContext.getSharedPreferences(AUTO_SPREAD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_AUTO)
            .remove(KEY_LAST_AUTO_EXIT)
            .apply()
    }
}

/**
 * В режиме AUTO: после сигнала входа отправить две заявки на песочницу и показать по ноге уведомление.
 * Дедуп по паре (тип входа, timestamp бара из журнала), чтобы UI и фон не исполнили дважды.
 */
internal suspend fun runSandboxAutoEntryIfNeeded(
    context: Context,
    signalType: StrategySignalType,
    zScore: Double,
    barTimestampMillis: Long,
    entrySpreadPercent: Double,
    fromTestButton: Boolean = false
): Boolean {
    if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) {
        return false
    }
    if (!TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context)) return false
    val dedupKey = "${signalType.name}|$barTimestampMillis"
    synchronized(autoSpreadDedupLock) {
        val p = context.applicationContext.getSharedPreferences(AUTO_SPREAD_PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_LAST_AUTO, null) == dedupKey) return false
    }
    when (TinkoffSandboxStorage.resolveExecUiState(context)) {
        SandboxExecUiState.MissingCredentials -> {
            notifySandboxAutoEntrySkipped(context, "Укажите токен и счёт песочницы (вкладка «Песочница»).")
            return false
        }
        SandboxExecUiState.Ready -> Unit
        SandboxExecUiState.Off -> Unit
    }
    val token = TinkoffSandboxStorage.getToken(context) ?: run {
        notifySandboxAutoEntrySkipped(context, "Нет токена песочницы.")
        return false
    }
    val accountId = TinkoffSandboxStorage.getAccountId(context) ?: run {
        notifySandboxAutoEntrySkipped(context, "Нет счёта песочницы.")
        return false
    }
    val leverage = TinkoffSandboxStorage.getSandboxNotifyLeverage(context)
    val app = context.applicationContext
    return try {
        val legs = tinkoffSandboxExecuteSpreadEntryDetailed(token, accountId, signalType)
        val executedAt = System.currentTimeMillis()
        synchronized(autoSpreadDedupLock) {
            app.getSharedPreferences(AUTO_SPREAD_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_AUTO, dedupKey).commit()
        }
        markVirtualTradeConsumedForJournalEntry(app, signalType, barTimestampMillis)
        appendPortfolioExecutionLedger(
            app,
            barTimestampMillis = barTimestampMillis,
            signalType = signalType,
            source = PortfolioExecSource.AUTO
        )
        val execution = TinkoffSandboxSpreadExecLog.recordFromLegs(
            app,
            signalType,
            zScore,
            barTimestampMillis = barTimestampMillis,
            executedAtMillis = executedAt,
            entrySpreadPercent = entrySpreadPercent,
            source = PortfolioExecSource.AUTO,
            legs = legs,
            fromTestButton = fromTestButton
        )
        execution?.let { opened ->
            val lastLeg = legs.lastOrNull()
            notifySandboxTradeOpened(
                context = app,
                execution = opened,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = leverage,
                portfolioTotalRub = lastLeg?.portfolioTotalRub,
                portfolioCashRub = lastLeg?.portfolioCashRub,
            )
        }
        true
    } catch (e: Exception) {
        notifySandboxAutoEntrySkipped(
            app,
            e.message?.take(200) ?: e.javaClass.simpleName
        )
        false
    }
}

/**
 * В режиме AUTO: после сигнала выхода отправить обратные заявки на песочницу и снять сделку с открытых.
 * Закрывает любую открытую сделку (ручную или авто), не только при режиме авто-входа.
 * Журнал выхода уже записан обработчиком сигнала — дублировать не нужно.
 */
internal suspend fun runSandboxAutoExitIfNeeded(
    context: Context,
    exitSignalType: StrategySignalType,
    zScore: Double,
    barTimestampMillis: Long,
): Boolean {
    if (exitSignalType != StrategySignalType.ExitLong &&
        exitSignalType != StrategySignalType.ExitShort
    ) {
        return false
    }
    val dedupKey = "${exitSignalType.name}|$barTimestampMillis"
    synchronized(autoSpreadDedupLock) {
        val p = context.applicationContext.getSharedPreferences(AUTO_SPREAD_PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_LAST_AUTO_EXIT, null) == dedupKey) return false
    }
    val app = context.applicationContext
    val openTrade = findOpenTradeForStrategyExit(
        TinkoffSandboxSpreadExecLog.loadRecent(app),
        exitSignalType,
    ) ?: run {
        MoexDiagnostics.log(
            app,
            "sandbox",
            "auto_exit_skip no_open_trade signal=$exitSignalType bar=$barTimestampMillis",
        )
        return false
    }
    val leverage = TinkoffSandboxStorage.getSandboxNotifyLeverage(app)
    return try {
        val legs = executeSandboxSpreadExitIfConfigured(app, openTrade.signalType)
        synchronized(autoSpreadDedupLock) {
            app.getSharedPreferences(AUTO_SPREAD_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_AUTO_EXIT, dedupKey).commit()
        }
        finalizePortfolioOpenTradeClose(
            context = app,
            execution = openTrade,
            recordExitInJournal = false,
            exitZScore = zScore,
            exitTimestampMillis = barTimestampMillis,
        ).getOrThrow()
        notifySandboxTradeClosedAfterClose(
            context = app,
            execution = openTrade,
            exitSignalType = exitSignalType,
            exitBarTimestampMillis = barTimestampMillis,
            exitZScore = zScore,
            exitLegs = legs,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = leverage,
        )
        true
    } catch (e: Exception) {
        notifySandboxAutoExitSkipped(
            app,
            e.message?.take(200) ?: e.javaClass.simpleName
        )
        false
    }
}

private fun notifySandboxAutoEntrySkipped(context: Context, reason: String) {
    showPushNotification(
        context = context,
        title = "Песочница: авто-вход не выполнен",
        body = reason,
        virtualTradeTap = null
    )
}

private fun notifySandboxAutoExitSkipped(context: Context, reason: String) {
    showPushNotification(
        context = context,
        title = "Песочница: авто-выход не выполнен",
        body = reason,
        virtualTradeTap = null
    )
}
