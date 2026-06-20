package com.example.moexmvp

import android.content.Context
import java.util.Locale
import kotlin.math.max

internal data class ZStrategyProdLikeSizing(
    val accountSizeRub: Double,
    val capitalUsagePercent: Double,
    val leverageForLots: Double,
)

/** Номинал пары на баре — как Prod lot sizing (cash × usage%, плечо на liquid). */
internal fun strategyTestPairNotionalRub(
    bar: DataPoint,
    accountSizeRub: Double,
    capitalUsagePercent: Double,
    leverageForLots: Double,
): Double {
    if (bar.tatnClose <= 0.0 || bar.tatnpClose <= 0.0) return accountSizeRub.coerceAtLeast(1.0)
    val reserveFraction = (1.0 - capitalUsagePercent / 100.0).coerceIn(0.0, 0.95)
    val cash = accountSizeRub.coerceAtLeast(1.0)
    val sizing = computeSpreadQuantityLots(
        SpreadLotSizingInput(
            cashRub = cash,
            priceTatN = bar.tatnClose,
            priceTatNp = bar.tatnpClose,
            lotSize = 1,
            reserveFraction = reserveFraction,
            reserveMinRub = minOf(SPREAD_LOT_RESERVE_MIN_RUB, cash * 0.5),
            liquidPortfolioRub = cash,
            correctedMarginRub = 0.0,
            leverageForNotional = leverageForLots.coerceAtLeast(1.0),
        )
    )
    return max(sizing.executionNotionalRub, spreadPairNotionalRub(
        bar.tatnClose,
        bar.tatnpClose,
        1,
        SPREAD_LOT_MIN_LOTS,
    ))
}

internal fun buildStrategyTestSimOptions(context: Context): ZStrategySimOptions {
    val spreadSlip = TradeExecutionLog.medianSlippageSpreadPts(context)
        ?: DEFAULT_STRATEGY_TEST_SLIPPAGE_SPREAD_PTS
    return ZStrategySimOptions(
        slippageSpreadPts = spreadSlip.coerceAtLeast(0.0),
        maxLossRub = PROD_MONEY_STOP_PER_TRADE_RUB,
    )
}

internal fun loadStrategyTestAccountSizeRub(context: Context): Double =
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .getFloat(PREF_STRATEGY_TEST_ACCOUNT_RUB, DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toFloat())
        .toDouble()
        .coerceIn(1_000.0, 10_000_000.0)

internal fun saveStrategyTestAccountSizeRub(context: Context, rub: Double) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_STRATEGY_TEST_ACCOUNT_RUB, rub.toFloat().coerceIn(1_000f, 10_000_000f))
        .apply()
}

internal fun loadStrategyTestCapitalUsagePercent(context: Context): Double =
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .getFloat(PREF_STRATEGY_TEST_CAPITAL_USAGE_PCT, DEFAULT_STRATEGY_TEST_CAPITAL_USAGE_PERCENT.toFloat())
        .toDouble()
        .coerceIn(10.0, 100.0)

internal fun saveStrategyTestCapitalUsagePercent(context: Context, percent: Double) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_STRATEGY_TEST_CAPITAL_USAGE_PCT, percent.toFloat().coerceIn(10f, 100f))
        .apply()
}

internal const val STRATEGY_TEST_ACCOUNT_RUB_MIN = 1_000.0
internal const val STRATEGY_TEST_ACCOUNT_RUB_MAX = 10_000_000.0

internal fun formatStrategyTestAccountRubInput(rub: Double): String =
    "%.0f".format(Locale.US, rub.coerceIn(STRATEGY_TEST_ACCOUNT_RUB_MIN, STRATEGY_TEST_ACCOUNT_RUB_MAX))

/** Целое число ₽ из поля ввода (пробелы/₽/запятая допустимы). */
internal fun parseStrategyTestAccountRubInput(
    text: String,
    minRub: Double = STRATEGY_TEST_ACCOUNT_RUB_MIN,
    maxRub: Double = STRATEGY_TEST_ACCOUNT_RUB_MAX,
): Double? {
    val cleaned = text.trim()
        .replace(" ", "")
        .replace("₽", "")
        .replace(",", ".")
        .substringBefore('.')
    if (cleaned.isEmpty()) return null
    return cleaned.toDoubleOrNull()?.coerceIn(minRub, maxRub)
}

internal fun buildStrategyTestPeriodDescription(context: Context): String {
    val cal = TradeExecutionLog.calibrationSummary(context)
    return "Тест страт. · ${PORTFOLIO_M15_LOOKBACK_DAYS}д · Prod-like · $cal"
}
