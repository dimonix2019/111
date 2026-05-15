package com.example.moexmvp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "moex_portfolio_exec_ledger"
private const val KEY_JSON = "portfolio_exec_entries_json"
private const val MAX_ENTRIES = 500

internal enum class PortfolioExecSource {
    MANUAL,
    AUTO
}

/**
 * Журнал фактических входов спрэда на песочнице (ручной «Принять» или авто‑заявки).
 * Портфель отображает сделки по журналу сигналов только если вход есть в этом списке (с фильтром по режиму).
 */
internal data class PortfolioExecutionLedgerEntry(
    val barTimestampMillis: Long,
    val signalType: StrategySignalType,
    val source: PortfolioExecSource
)

private val portfolioExecLedgerLock = Any()

internal fun appendPortfolioExecutionLedger(
    context: Context,
    barTimestampMillis: Long,
    signalType: StrategySignalType,
    source: PortfolioExecSource
) {
    if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return
    val app = context.applicationContext
    synchronized(portfolioExecLedgerLock) {
        val list = loadPortfolioExecutionLedgerUnsafe(app).toMutableList()
        val last = list.lastOrNull()
        if (last != null &&
            last.barTimestampMillis == barTimestampMillis &&
            last.signalType == signalType &&
            last.source == source
        ) {
            return
        }
        list += PortfolioExecutionLedgerEntry(barTimestampMillis, signalType, source)
        saveLedgerUnsafe(app, list.takeLast(MAX_ENTRIES))
    }
}

internal fun loadPortfolioExecutionLedger(context: Context): List<PortfolioExecutionLedgerEntry> =
    synchronized(portfolioExecLedgerLock) {
        loadPortfolioExecutionLedgerUnsafe(context.applicationContext)
    }

internal fun clearPortfolioExecutionLedger(context: Context) {
    synchronized(portfolioExecLedgerLock) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_JSON)
            .apply()
    }
}

private fun loadPortfolioExecutionLedgerUnsafe(app: Context): List<PortfolioExecutionLedgerEntry> {
    val raw = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_JSON, null)
        ?: return emptyList()
    val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = runCatching { StrategySignalType.valueOf(o.optString("signalType")) }.getOrNull()
                ?: continue
            val src = runCatching { PortfolioExecSource.valueOf(o.optString("source")) }.getOrNull()
                ?: continue
            add(
                PortfolioExecutionLedgerEntry(
                    barTimestampMillis = o.optLong("barTs", 0L),
                    signalType = type,
                    source = src
                )
            )
        }
    }
}

private fun saveLedgerUnsafe(app: Context, entries: List<PortfolioExecutionLedgerEntry>) {
    val arr = JSONArray()
    entries.forEach { e ->
        arr.put(
            JSONObject()
                .put("barTs", e.barTimestampMillis)
                .put("signalType", e.signalType.name)
                .put("source", e.source.name)
        )
    }
    app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_JSON, arr.toString())
        .apply()
}

/**
 * Выходы оставляем все из журнала сигналов; вход Enter* — только если был реальный билд на демо
 * под текущим режимом ручной/авто на песочнице.
 */
internal fun journalEventsForExecutionPortfolioTab(
    allEvents: List<StrategySignalEvent>,
    ledger: List<PortfolioExecutionLedgerEntry>,
    sandboxEntryAuto: Boolean
): List<StrategySignalEvent> {
    val allowedEntryPairs = ledger
        .filter { e ->
            if (sandboxEntryAuto) e.source == PortfolioExecSource.AUTO else e.source == PortfolioExecSource.MANUAL
        }
        .map { Pair(it.signalType, it.barTimestampMillis) }
        .toSet()

    return allEvents.filter { ev ->
        when (ev.signalType) {
            StrategySignalType.ExitLong, StrategySignalType.ExitShort -> true
            StrategySignalType.EnterLong, StrategySignalType.EnterShort ->
                Pair(ev.signalType, ev.timestampMillis) in allowedEntryPairs
        }
    }
}
