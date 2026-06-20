package com.example.moexmvp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.util.Locale

/** Последний CSV симуляции «Тест страт.» для выгрузки из «О приложении». */
internal object StrategyTestExportStore {
    private const val PREFS = "strategy_test_export_prefs"
    private const val KEY_CSV = "last_compare_csv_v1"
    private const val KEY_AT_MS = "last_export_at_ms"

    fun saveCompareCsv(context: Context, csv: String) {
        if (csv.lines().size <= 1) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CSV, csv)
            .putLong(KEY_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun loadCompareCsv(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CSV, null)
            ?.takeIf { it.isNotBlank() }

    fun lastExportAtMillis(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_AT_MS, 0L)
}

internal data class StrategyTestExportConfig(
    val accountSizeRub: Double,
    val capitalUsagePercent: Double,
    val leverageForLots: Double,
    val commissionPercentPerSide: Double,
    val entryThreshold: Double,
    val exitThreshold: Double,
    val slippageSpreadPts: Double,
    val compoundReturns: Boolean,
    val applyProdLotCap: Boolean = true,
    val usePortfolioThresholds: Boolean = true,
    val useLiveZSignals: Boolean = true,
    val thresholdSource: String = "portfolio",
)

private val TRADE_COMPARE_HEADER = listOf(
    "source",
    "trade_index",
    "direction",
    "entry_msk",
    "exit_msk",
    "duration_min",
    "spread_entry_pct",
    "spread_exit_pct",
    "spread_pnl_pts",
    "gross_rub",
    "commission_rub",
    "overnight_rub",
    "net_rub",
    "notional_rub",
    "quantity_lots",
    "z_entry",
    "z_exit",
    "leg_long_pnl_rub",
    "leg_short_pnl_rub",
    "trade_id",
).joinToString(",")

private fun csvCell(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n')) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }

private fun fmtNum(v: Double?): String =
    when {
        v == null || v.isNaN() -> ""
        else -> "%.4f".format(Locale.US, v)
    }

private fun fmtRub(v: Double): String =
    if (v.isNaN()) "" else "%.2f".format(Locale.US, v)

private fun directionLabel(dir: ZStrategyPosition): String = when (dir) {
    ZStrategyPosition.Long -> "LONG"
    ZStrategyPosition.Short -> "SHORT"
    ZStrategyPosition.Flat -> "FLAT"
}

private fun durationMin(entry: String, exit: String): String {
    val ms = simTradeDurationMillis(entry, exit) ?: return ""
    return "%.1f".format(Locale.US, ms / 60_000.0)
}

private fun metaLines(prefix: String, lines: List<String>): List<String> =
    lines.map { "# $prefix$it" }

internal fun exportProdTradesCompareCsv(context: Context): String {
    val closed = TinkoffClosedSpreadExecLog.loadRecent(context)
    val legFills = TradeExecutionLog.loadRecent(context)
    val meta = metaLines(
        prefix = "prod ",
        lines = listOf(
            "export=prod_trades",
            "generated_msk=${formatPortfolioExecutionTableMsk(System.currentTimeMillis())}",
            "mode=${currentExecutionMode(context).name}",
            "closed_trades=${closed.size}",
            "leg_fills=${legFills.size}",
            "calibration=${TradeExecutionLog.calibrationSummary(context)}",
        ),
    )
    val rows = closed.mapIndexed { index, r ->
        val exitMsk = formatPortfolioExecutionTableMsk(r.exitTimestampMillis)
        val exitSpread = legFills
            .filter { it.tradeId == r.tradeId && it.phase == TradeExecPhase.Exit }
            .mapNotNull { it.refSpreadPercent }
            .firstOrNull()
        listOf(
            "prod",
            (closed.size - index).toString(),
            when (r.signalType) {
                StrategySignalType.EnterLong -> "LONG"
                StrategySignalType.EnterShort -> "SHORT"
                else -> r.directionLabel
            },
            csvCell(r.entryTimeMsk),
            csvCell(exitMsk),
            durationMin(r.entryTimeMsk, exitMsk),
            fmtNum(r.entrySpreadPercent),
            fmtNum(exitSpread),
            "",
            fmtRub(r.longLegYieldRub + r.shortLegYieldRub),
            fmtRub(r.operationsCommissionRub),
            "",
            fmtRub(r.realizedNetRub ?: (r.longLegYieldRub + r.shortLegYieldRub - r.operationsCommissionRub)),
            fmtRub(r.executionNotionalRub),
            r.quantityLots.toString(),
            fmtNum(r.zScore),
            fmtNum(r.exitZScore),
            fmtRub(r.longLegYieldRub),
            fmtRub(r.shortLegYieldRub),
            csvCell(r.tradeId),
        ).joinToString(",")
    }
    return (meta + listOf(TRADE_COMPARE_HEADER) + rows).joinToString("\n")
}

internal fun exportStrategyTestCompareCsv(
    metrics: PortfolioMetrics?,
    tradeItems: List<StrategyTestTradeItem>,
    config: StrategyTestExportConfig,
    periodDescription: String = metrics?.periodDescription.orEmpty(),
): String {
    val trades = tradeItems.map { it.trade }
    val meta = metaLines(
        prefix = "sim ",
        lines = listOf(
            "export=strategy_test",
            "generated_msk=${formatPortfolioExecutionTableMsk(System.currentTimeMillis())}",
            "account_rub=${"%.0f".format(Locale.US, config.accountSizeRub)}",
            "capital_usage_pct=${"%.0f".format(Locale.US, config.capitalUsagePercent)}",
            "leverage_lots=${"%.1f".format(Locale.US, config.leverageForLots)}",
            "pnl_leverage=1.0",
            "commission_pct_side=${"%.4f".format(Locale.US, config.commissionPercentPerSide)}",
            "z_entry=${"%.2f".format(Locale.US, config.entryThreshold)}",
            "z_exit=${"%.2f".format(Locale.US, config.exitThreshold)}",
            "slippage_spread_pts=${"%.4f".format(Locale.US, config.slippageSpreadPts)}",
            "compound=${config.compoundReturns}",
            "prod_lot_cap=${config.applyProdLotCap}",
            "portfolio_thresholds=${config.usePortfolioThresholds}",
            "live_z=${config.useLiveZSignals}",
            "threshold_source=${config.thresholdSource}",
            "trades=${trades.size}",
            "total_net_rub=${fmtRub(metrics?.totalPnlRubApprox ?: Double.NaN)}",
            "period=${periodDescription}",
        ),
    )
    val rows = trades.mapIndexed { index, t ->
        listOf(
            "sim",
            (index + 1).toString(),
            directionLabel(t.direction),
            csvCell(t.entryDate),
            csvCell(t.exitDate),
            durationMin(t.entryDate, t.exitDate),
            fmtNum(t.entrySpreadPercent),
            fmtNum(t.exitSpreadPercent),
            fmtNum(t.pnlSpreadPoints),
            fmtRub(t.grossPnlRubApprox),
            fmtRub(t.commissionRubApprox),
            fmtRub(t.overnightRubApprox),
            fmtRub(t.pnlRubApprox),
            fmtRub(metrics?.notionalRub ?: Double.NaN),
            "",
            "",
            "",
            "",
            "",
            "",
        ).joinToString(",")
    }
    return (meta + listOf(TRADE_COMPARE_HEADER) + rows).joinToString("\n")
}

internal fun buildStrategyTestExportConfig(
    context: Context,
    accountSizeRub: Double,
    capitalUsagePercent: Double,
    leverageForLots: Double,
    commissionPercentPerSide: Double,
    entryThreshold: Double,
    exitThreshold: Double,
    compoundReturns: Boolean,
    applyProdLotCap: Boolean = true,
    usePortfolioThresholds: Boolean = true,
    useLiveZSignals: Boolean = true,
    thresholdSource: String = "portfolio",
): StrategyTestExportConfig {
    val slip = TradeExecutionLog.medianSlippageSpreadPts(context)
        ?: DEFAULT_STRATEGY_TEST_SLIPPAGE_SPREAD_PTS
    return StrategyTestExportConfig(
        accountSizeRub = accountSizeRub,
        capitalUsagePercent = capitalUsagePercent,
        leverageForLots = leverageForLots,
        commissionPercentPerSide = commissionPercentPerSide,
        entryThreshold = entryThreshold,
        exitThreshold = exitThreshold,
        slippageSpreadPts = slip,
        compoundReturns = compoundReturns,
        applyProdLotCap = applyProdLotCap,
        usePortfolioThresholds = usePortfolioThresholds,
        useLiveZSignals = useLiveZSignals,
        thresholdSource = thresholdSource,
    )
}

internal fun copyCsvToClipboard(context: Context, csv: String, label: String): Boolean {
    if (csv.lines().size <= 1) return false
    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText(label, csv))
    return true
}

internal fun tradeCompareRowCount(csv: String): Int {
    val dataLines = csv.lines().filter { it.isNotBlank() && !it.startsWith("#") }
    return (dataLines.size - 1).coerceAtLeast(0)
}

internal fun tradeCompareHeaderColumnCount(): Int =
    TRADE_COMPARE_HEADER.split(',').size

internal fun persistStrategyTestCompareExport(
    context: Context,
    metrics: PortfolioMetrics,
    tradeItems: List<StrategyTestTradeItem>,
    config: StrategyTestExportConfig,
): String {
    val csv = exportStrategyTestCompareCsv(metrics, tradeItems, config)
    StrategyTestExportStore.saveCompareCsv(context, csv)
    return csv
}

internal fun buildStrategyTestCompareCsvFromState(
    context: Context,
    metrics: PortfolioMetrics?,
    tradeItems: List<StrategyTestTradeItem>,
    accountSizeRub: Double,
    capitalUsagePercent: Double,
    leverageForLots: Double,
    commissionPercentPerSide: Double,
    entryThreshold: Double,
    exitThreshold: Double,
    compoundReturns: Boolean,
    applyProdLotCap: Boolean = true,
    usePortfolioThresholds: Boolean = true,
    useLiveZSignals: Boolean = true,
    thresholdSource: String = "portfolio",
): String? {
    val m = metrics ?: return null
    if (tradeItems.isEmpty()) return null
    val config = buildStrategyTestExportConfig(
        context = context,
        accountSizeRub = accountSizeRub,
        capitalUsagePercent = capitalUsagePercent,
        leverageForLots = leverageForLots,
        commissionPercentPerSide = commissionPercentPerSide,
        entryThreshold = entryThreshold,
        exitThreshold = exitThreshold,
        compoundReturns = compoundReturns,
        applyProdLotCap = applyProdLotCap,
        usePortfolioThresholds = usePortfolioThresholds,
        useLiveZSignals = useLiveZSignals,
        thresholdSource = thresholdSource,
    )
    return exportStrategyTestCompareCsv(m, tradeItems, config)
}
