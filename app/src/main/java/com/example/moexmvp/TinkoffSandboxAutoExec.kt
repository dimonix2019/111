package com.example.moexmvp

import android.content.Context

private val autoSpreadDedupLock = Any()
private const val AUTO_SPREAD_PREFS = "moex_sandbox_auto_spread_dedup"
private const val KEY_LAST_AUTO = "last_executed_bar_key"

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
    if (!TinkoffSandboxStorage.isSandboxEntryAuto(context)) return false
    if (!TinkoffSandboxStorage.isExecuteSignalsOnSandbox(context)) return false
    if (TinkoffSandboxStorage.resolveExecUiState(context) != SandboxExecUiState.Ready) return false
    val dedupKey = "${signalType.name}|$barTimestampMillis"
    synchronized(autoSpreadDedupLock) {
        val p = context.applicationContext.getSharedPreferences(AUTO_SPREAD_PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_LAST_AUTO, null) == dedupKey) return false
    }
    val token = TinkoffSandboxStorage.getToken(context) ?: return false
    val accountId = TinkoffSandboxStorage.getAccountId(context) ?: return false
    val leverage = TinkoffSandboxStorage.getSandboxNotifyLeverage(context)
    val legs = tinkoffSandboxExecuteSpreadEntryDetailed(token, accountId, signalType)
    synchronized(autoSpreadDedupLock) {
        context.applicationContext.getSharedPreferences(AUTO_SPREAD_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_AUTO, dedupKey).commit()
    }
    val app = context.applicationContext
    markVirtualTradeConsumedForJournalEntry(app, signalType, barTimestampMillis)
    TinkoffSandboxSpreadExecLog.record(app, signalType, zScore, System.currentTimeMillis())
    notifySandboxSpreadLegExecutionResults(
        app,
        legs,
        DEFAULT_PORTFOLIO_NOTIONAL_RUB,
        leverage
    )
    return true
}
