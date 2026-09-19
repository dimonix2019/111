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

internal fun isPortfolioBrokerClosedRow(confirmLabel: String): Boolean =
    confirmLabel == "брокер" || confirmLabel == "счёт"

internal enum class PortfolioClosedTradesSourceFilter {
    All,
    Broker,
    TestOnly,
}

internal fun filterClosedTradesBySourceFilter(
    rows: List<PortfolioConfirmedTradeTableRow>,
    filter: PortfolioClosedTradesSourceFilter,
): List<PortfolioConfirmedTradeTableRow> = when (filter) {
    PortfolioClosedTradesSourceFilter.All -> rows
    PortfolioClosedTradesSourceFilter.Broker ->
        rows.filter { isPortfolioBrokerClosedRow(it.confirmLabel) }
    PortfolioClosedTradesSourceFilter.TestOnly ->
        rows.filter { isPortfolioTestTradeConfirmLabel(it.confirmLabel) }
}

/** Однобуквенный тип сделки: Р — ручная, А — авто (подпись на графике Z). */
internal fun portfolioTradeSourceTypeLetter(confirmLabel: String): String = when {
    confirmLabel.startsWith("авто") -> "А"
    confirmLabel.startsWith("ручное") -> "Р"
    else -> "—"
}

/** Подпись у маркера на графике: «3А», «2Р». */
internal fun portfolioTradeChartBadgeText(tradeDisplayId: String, confirmLabel: String): String {
    val num = tradeDisplayId.trim().substringBefore(' ').ifBlank { tradeDisplayId }
    val type = portfolioTradeSourceTypeLetter(confirmLabel)
    return if (type == "—") num else "$num$type"
}

/**
 * Журнал фактических входов спрэда на песочнице (ручной «Принять» или авто‑заявки).
 * Метка «авто»/«ручное» в таблице портфеля; без записи — «сигнал» (только журнал).
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

internal fun removePortfolioExecutionLedgerEntry(
    context: Context,
    barTimestampMillis: Long,
    signalType: StrategySignalType,
    source: PortfolioExecSource,
) {
    if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return
    val app = context.applicationContext
    synchronized(portfolioExecLedgerLock) {
        val list = loadPortfolioExecutionLedgerUnsafe(app).toMutableList()
        val removed = list.removeAll {
            it.barTimestampMillis == barTimestampMillis &&
                it.signalType == signalType &&
                it.source == source
        }
        if (removed) saveLedgerUnsafe(app, list)
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
 * Пары вход/выход для вкладки «Портфель» — по журналу сигналов (как на «Журнал»).
 * Реестр демо ([ledger]) влияет только на метку «авто»/«ручное»/«сигнал» в таблице.
 */
/** Все записи реестра входов (ручные + авто) — для маркеров сделок на Z-графике. */
internal fun ledgerEntryPairsAll(
    ledger: List<PortfolioExecutionLedgerEntry>,
): Set<Pair<StrategySignalType, Long>> =
    ledger.map { Pair(it.signalType, it.barTimestampMillis) }.toSet()

internal fun journalEventsForExecutionPortfolioTab(
    allEvents: List<StrategySignalEvent>,
    @Suppress("UNUSED_PARAMETER") ledger: List<PortfolioExecutionLedgerEntry>,
    @Suppress("UNUSED_PARAMETER") portfolioLedgerIncludeAuto: Boolean
): List<StrategySignalEvent> {
    val sorted = allEvents.sortedBy { it.timestampMillis }
    val out = mutableListOf<StrategySignalEvent>()
    var position = ZStrategyPosition.Flat

    for (ev in sorted) {
        when (ev.signalType) {
            StrategySignalType.EnterLong -> {
                if (position == ZStrategyPosition.Flat) {
                    out += ev
                    position = ZStrategyPosition.Long
                }
            }
            StrategySignalType.EnterShort -> {
                if (position == ZStrategyPosition.Flat) {
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

/** Журнал для Z-графика «Рынок» — паритет с «Портфель» / «Журнал» (все пары вход/выход). */
internal fun journalEventsForChartTrades(
    allEvents: List<StrategySignalEvent>,
    ledger: List<PortfolioExecutionLedgerEntry>,
): List<StrategySignalEvent> =
    journalEventsForExecutionPortfolioTab(allEvents, ledger, portfolioLedgerIncludeAuto = true)

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
    portfolioLedgerIncludeAuto: Boolean,
    executionMode: TinkoffExecutionMode = TinkoffExecutionMode.Sandbox,
): List<PortfolioConfirmedTradeTableRow> =
    rows.filter { row ->
        if (executionMode == TinkoffExecutionMode.Prod && isPortfolioSignalOnlyClosedRow(row)) {
            return@filter false
        }
        when {
            isPortfolioTestTradeConfirmLabel(row.confirmLabel) -> true
            row.confirmLabel == "брокер" -> true
            row.confirmLabel.startsWith("авто") -> portfolioLedgerIncludeAuto
            row.confirmLabel.startsWith("ручное") -> !portfolioLedgerIncludeAuto
            row.confirmLabel == "сигнал" || row.confirmLabel == "—" -> true
            else -> false
        }
    }
