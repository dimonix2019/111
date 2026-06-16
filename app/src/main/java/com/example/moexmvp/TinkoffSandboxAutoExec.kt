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
    val mode = currentExecutionMode(context)
    val dedupKey = "${signalType.name}|$barTimestampMillis"
    synchronized(autoSpreadDedupLock) {
        val p = context.applicationContext.getSharedPreferences(AUTO_SPREAD_PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_LAST_AUTO, null) == dedupKey) return false
    }
    when (TinkoffSandboxStorage.resolveExecUiState(context, mode)) {
        SandboxExecUiState.MissingCredentials -> {
            notifySandboxAutoEntrySkipped(
                context,
                "Укажите токен и счёт (${executionModeLabelRu(mode)})."
            )
            return false
        }
        SandboxExecUiState.Ready -> Unit
        SandboxExecUiState.Off -> Unit
    }
    if (TinkoffSandboxStorage.getActiveToken(context, mode) == null) {
        notifySandboxAutoEntrySkipped(context, "Нет токена (${executionModeLabelRu(mode)}).")
        return false
    }
    if (TinkoffSandboxStorage.getActiveAccountId(context, mode) == null) {
        notifySandboxAutoEntrySkipped(context, "Нет счёта (${executionModeLabelRu(mode)}).")
        return false
    }
    val leverage = TinkoffSandboxStorage.getSandboxNotifyLeverage(context)
    val app = context.applicationContext
    return try {
        val entry = executeSpreadEntryDetailedForConfiguredMode(app, signalType)
        val legs = entry.legs
        val sizing = entry.sizing
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
            fromTestButton = fromTestButton,
            quantityLots = sizing.quantityLots,
            executionNotionalRub = sizing.executionNotionalRub,
        )
        execution?.let { opened ->
            val lastLeg = legs.lastOrNull()
            notifySandboxTradeOpened(
                context = app,
                execution = opened,
                notionalRub = sizing.executionNotionalRub,
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
 * В режиме AUTO: после сигнала выхода отправить обратные заявки на песочницу и снять авто-сделку с открытых.
 * Ручные сделки алгоритмом не закрываются — только через «Закрыть» на «Портфеле».
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
    if (!TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context)) return false
    val mode = currentExecutionMode(context)
    val dedupKey = "${exitSignalType.name}|$barTimestampMillis"
    synchronized(autoSpreadDedupLock) {
        val p = context.applicationContext.getSharedPreferences(AUTO_SPREAD_PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_LAST_AUTO_EXIT, null) == dedupKey) return false
    }
    val openTrade = findOpenTradeForStrategyExit(
        TinkoffSandboxSpreadExecLog.loadRecent(context),
        exitSignalType,
    ) ?: return false
    when (TinkoffSandboxStorage.resolveExecUiState(context, mode)) {
        SandboxExecUiState.MissingCredentials -> {
            notifySandboxAutoExitSkipped(
                context,
                "Укажите токен и счёт (${executionModeLabelRu(mode)})."
            )
            return false
        }
        SandboxExecUiState.Ready -> Unit
        SandboxExecUiState.Off -> Unit
    }
    if (TinkoffSandboxStorage.getActiveToken(context, mode) == null) {
        notifySandboxAutoExitSkipped(context, "Нет токена (${executionModeLabelRu(mode)}).")
        return false
    }
    if (TinkoffSandboxStorage.getActiveAccountId(context, mode) == null) {
        notifySandboxAutoExitSkipped(context, "Нет счёта (${executionModeLabelRu(mode)}).")
        return false
    }
    val leverage = TinkoffSandboxStorage.getSandboxNotifyLeverage(context)
    val app = context.applicationContext
    return try {
        val legs = executeSpreadExitDetailedForConfiguredMode(
            app,
            openTrade.signalType,
            openTrade.quantityLots,
        )
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
            notionalRub = tradeExecutionNotionalRub(openTrade, DEFAULT_PORTFOLIO_NOTIONAL_RUB),
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
    val mode = currentExecutionMode(context)
    showPushNotification(
        context = context,
        title = "${mode.titleRu}: авто-вход не выполнен",
        body = reason,
        virtualTradeTap = null
    )
}

private fun notifySandboxAutoExitSkipped(context: Context, reason: String) {
    val mode = currentExecutionMode(context)
    showPushNotification(
        context = context,
        title = "${mode.titleRu}: авто-выход не выполнен",
        body = reason,
        virtualTradeTap = null
    )
}
