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

/** Метка «· тест» в confirmLabel — сделка с кнопки «Тестовая пара» на вкладке «Портфель». */
internal fun isPortfolioTestTradeConfirmLabel(confirmLabel: String): Boolean =
    confirmLabel.contains("· тест")

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
        .commit()
}

/**
 * Пары (тип входа, время бара) из журнала исполнений на демо для выбранного режима.
 * Если в выбранном режиме записей нет, но в другом есть — подставляем их (чтобы выходы не «висели» без входа).
 */
internal fun ledgerEntryPairsForPortfolioReplay(
    ledger: List<PortfolioExecutionLedgerEntry>,
    portfolioLedgerIncludeAuto: Boolean
): Set<Pair<StrategySignalType, Long>> {
    val primarySource = if (portfolioLedgerIncludeAuto) PortfolioExecSource.AUTO else PortfolioExecSource.MANUAL
    val fallbackSource = if (portfolioLedgerIncludeAuto) PortfolioExecSource.MANUAL else PortfolioExecSource.AUTO
    val primary = ledger.filter { it.source == primarySource }
        .map { Pair(it.signalType, it.barTimestampMillis) }
        .toSet()
    if (primary.isNotEmpty()) return primary
    return ledger.filter { it.source == fallbackSource }
        .map { Pair(it.signalType, it.barTimestampMillis) }
        .toSet()
}

/**
 * Вход Enter* — только если был реальный вход на демо (см. [ledgerEntryPairsForPortfolioReplay]).
 * Выход — только если в том же отфильтрованном потоке был соответствующий вход (иначе replay не закроет сделку).
 */
internal fun journalEventsForExecutionPortfolioTab(
    allEvents: List<StrategySignalEvent>,
    ledger: List<PortfolioExecutionLedgerEntry>,
    portfolioLedgerIncludeAuto: Boolean
): List<StrategySignalEvent> {
    val ledgerPairs = ledgerEntryPairsForPortfolioReplay(ledger, portfolioLedgerIncludeAuto)
    /** Нет записей демо — закрытые сделки всё равно строим по парам вход/выход в журнале сигналов. */
    val allowAllJournalEnters = ledger.isEmpty()

    val sorted = allEvents.sortedBy { it.timestampMillis }
    val out = mutableListOf<StrategySignalEvent>()
    var position = ZStrategyPosition.Flat

    for (ev in sorted) {
        when (ev.signalType) {
            StrategySignalType.EnterLong -> {
                if (position == ZStrategyPosition.Flat &&
                    (allowAllJournalEnters ||
                        ledgerEntryMatchesSignalBar(ledgerPairs, ev.signalType, ev.timestampMillis))
                ) {
                    out += ev
                    position = ZStrategyPosition.Long
                }
            }
            StrategySignalType.EnterShort -> {
                if (position == ZStrategyPosition.Flat &&
                    (allowAllJournalEnters ||
                        ledgerEntryMatchesSignalBar(ledgerPairs, ev.signalType, ev.timestampMillis))
                ) {
                    out += ev
                    position = ZStrategyPosition.Short
                }
            }
            StrategySignalType.ExitLong -> {
                if (position == ZStrategyPosition.Long) {
                    out += ev
                    position = ZStrategyPosition.Flat
                }
            }
            StrategySignalType.ExitShort -> {
                if (position == ZStrategyPosition.Short) {
                    out += ev
                    position = ZStrategyPosition.Flat
                }
            }
        }
    }
    return out
}

internal fun filterSandboxExecutionsByPortfolioMode(
    executions: List<SandboxSpreadExecUi>,
    portfolioLedgerIncludeAuto: Boolean
): List<SandboxSpreadExecUi> =
    executions.filter { exec ->
        isPortfolioTestTradeConfirmLabel(exec.confirmLabel) ||
            portfolioLedgerIncludeAuto == (exec.source == PortfolioExecSource.AUTO)
    }

internal fun filterConfirmedTableRowsByPortfolioMode(
    rows: List<PortfolioConfirmedTradeTableRow>,
    portfolioLedgerIncludeAuto: Boolean
): List<PortfolioConfirmedTradeTableRow> =
    rows.filter { row ->
        when {
            isPortfolioTestTradeConfirmLabel(row.confirmLabel) -> true
            row.confirmLabel.startsWith("авто") -> portfolioLedgerIncludeAuto
            row.confirmLabel.startsWith("ручное") -> !portfolioLedgerIncludeAuto
            else -> false
        }
    }
