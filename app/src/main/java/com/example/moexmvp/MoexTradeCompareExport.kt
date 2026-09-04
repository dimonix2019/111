package com.example.moexmvp

import java.util.Locale

internal data class StrategyTestExportConfig(
    val accountSizeRub: Double,
    val capitalUsagePercent: Double,
    val leverageForLots: Double,
    val commissionPercentPerSide: Double,
    val entryThreshold: Double,
    val exitThreshold: Double,
    val slippageSpreadPts: Double,
    val compoundReturns: Boolean,
    val maxLossDdPercent: Double = 0.0,
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
            "max_loss_dd_pct=${"%.2f".format(Locale.US, config.maxLossDdPercent)}",
            "max_loss_rub=${fmtRub(resolveStrategyTestMaxLossRub(config.accountSizeRub, config.maxLossDdPercent))}",
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

internal fun tradeCompareRowCount(csv: String): Int {
    val dataLines = csv.lines().filter { it.isNotBlank() && !it.startsWith("#") }
    return (dataLines.size - 1).coerceAtLeast(0)
}

internal fun tradeCompareHeaderColumnCount(): Int =
    TRADE_COMPARE_HEADER.split(',').size
