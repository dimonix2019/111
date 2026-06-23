package com.example.moexmvp

import android.content.Context
import java.util.Locale

/** Доля капитала в сделку на Prod (= 1 − SPREAD_LOT_RESERVE_CASH_FRACTION). */
internal val PROD_EFFECTIVE_CAPITAL_USAGE_PERCENT: Double
    get() = (1.0 - SPREAD_LOT_RESERVE_CASH_FRACTION) * 100.0

internal data class StrategyTestSimThresholds(
    val entry: Double,
    val exit: Double,
    val source: StrategyTestThresholdSource,
)

internal enum class StrategyTestThresholdSource { PortfolioProd, StrategyTestCustom }

internal data class StrategyTestProdParityItem(
    val ok: Boolean,
    val label: String,
)

/** Пороги для симуляции: по умолчанию — боевые с «Портфеля». */
internal fun MoexScreenState.resolveStrategyTestSimThresholds(): StrategyTestSimThresholds {
    val fallbackEntry = dynamicThresholds.entry.coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    val fallbackExit = dynamicThresholds.exit.coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    return if (strategyTestUsePortfolioThresholds) {
        StrategyTestSimThresholds(
            entry = (realTradeEntryThreshold ?: fallbackEntry)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
            exit = (realTradeExitThreshold ?: fallbackExit)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
            source = StrategyTestThresholdSource.PortfolioProd,
        )
    } else {
        StrategyTestSimThresholds(
            entry = (strategyTestEntryThreshold ?: fallbackEntry)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
            exit = (strategyTestExitThreshold ?: fallbackExit)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
            source = StrategyTestThresholdSource.StrategyTestCustom,
        )
    }
}

internal fun MoexScreenState.strategyTestThresholdsMatchPortfolio(): Boolean {
    val sim = resolveStrategyTestSimThresholds()
    val portfolioEntry = (realTradeEntryThreshold ?: dynamicThresholds.entry)
        .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    val portfolioExit = (realTradeExitThreshold ?: dynamicThresholds.exit)
        .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    return portfolioThresholdsMatchStrategyTest(portfolioEntry, portfolioExit, sim.entry, sim.exit)
}

internal fun buildStrategyTestCommissionPercentPerSide(context: Context, fallback: Double): Double {
    val fromLog = TradeExecutionLog.medianCommissionPercentPerSide(context)
    return (fromLog ?: fallback).coerceIn(0.0, 1.0)
}

/** Cash после последнего Prod-входа из журнала исполнений (если есть). */
internal fun loadLastProdPortfolioCashRub(context: Context): Double? =
    TinkoffSandboxSpreadExecLog.loadRecent(context)
        .asSequence()
        .map { it.entryPortfolioCashRub }
        .firstOrNull { it > 0.0 }

internal fun buildStrategyTestProdParityChecklist(
    context: Context,
    accountSizeRub: Double,
    capitalUsagePercent: Double,
    usePortfolioThresholds: Boolean,
    useLiveZSignals: Boolean,
    commissionPercentPerSide: Double,
    thresholdsMatchPortfolio: Boolean,
): List<StrategyTestProdParityItem> = listOf(
    StrategyTestProdParityItem(usePortfolioThresholds && thresholdsMatchPortfolio, "Пороги Z = боевые (Портфель)"),
    StrategyTestProdParityItem(useLiveZSignals, "Z как live (без overlay журнала)"),
    StrategyTestProdParityItem(
        kotlin.math.abs(capitalUsagePercent - PROD_EFFECTIVE_CAPITAL_USAGE_PERCENT) <= 1.0,
        "Резерв капитала ≈ Prod (${"%.0f".format(Locale.US, PROD_EFFECTIVE_CAPITAL_USAGE_PERCENT)}%)",
    ),
    StrategyTestProdParityItem(
        capitalUsagePercent in 10.0..100.0,
        "Размер позиции по % капитала (${"%.0f".format(Locale.US, capitalUsagePercent)}%)",
    ),
    StrategyTestProdParityItem(
        TradeExecutionLog.medianSlippageSpreadPts(context) != null,
        "Slippage из лога исполнений",
    ),
    StrategyTestProdParityItem(
        TradeExecutionLog.medianCommissionPercentPerSide(context) != null,
        "Комиссия из лога (иначе ${"%.3f".format(Locale.US, commissionPercentPerSide)}%)",
    ),
    StrategyTestProdParityItem(accountSizeRub in 1_000.0..10_000_000.0, "Размер счёта задан"),
)

internal fun formatStrategyTestProdParitySummary(items: List<StrategyTestProdParityItem>): String {
    val ok = items.count { it.ok }
    return "Prod-parity: $ok/${items.size} · ${items.filter { !it.ok }.joinToString { it.label }}".trim()
}
