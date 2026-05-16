package com.example.moexmvp

import android.content.Context

private val autoSpreadDedupLock = Any()
private const val AUTO_SPREAD_PREFS = "moex_sandbox_auto_spread_dedup"
private const val KEY_LAST_AUTO = "last_executed_bar_key"

internal fun clearSandboxAutoSpreadDedup(context: Context) {
    synchronized(autoSpreadDedupLock) {
        context.applicationContext.getSharedPreferences(AUTO_SPREAD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_AUTO)
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
    barTimestampMillis: Long
): Boolean {
    if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) {
        return false
    }
    if (!TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context)) return false
    if (!TinkoffSandboxStorage.isExecuteSignalsOnSandbox(context)) return false
    val dedupKey = "${signalType.name}|$barTimestampMillis"
    synchronized(autoSpreadDedupLock) {
        val p = context.applicationContext.getSharedPreferences(AUTO_SPREAD_PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_LAST_AUTO, null) == dedupKey) return false
    }
    when (TinkoffSandboxStorage.resolveExecUiState(context)) {
        SandboxExecUiState.Off -> {
            notifySandboxAutoEntrySkipped(context, "Включите «Исполнять вход по сигналу на демо-счёт» на вкладке «Портфель».")
            return false
        }
        SandboxExecUiState.MissingCredentials -> {
            notifySandboxAutoEntrySkipped(context, "Укажите токен и счёт песочницы.")
            return false
        }
        SandboxExecUiState.Ready -> Unit
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
        TinkoffSandboxSpreadExecLog.record(app, signalType, zScore, System.currentTimeMillis())
        notifySandboxSpreadLegExecutionResults(
            app,
            legs,
            DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage
        )
        true
    } catch (e: Exception) {
        notifySandboxAutoEntrySkipped(
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
