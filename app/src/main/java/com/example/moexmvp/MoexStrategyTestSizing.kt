package com.example.moexmvp

import android.content.Context
import java.util.Locale
import kotlin.math.max

internal data class ZStrategyProdLikeSizing(
    val accountSizeRub: Double,
    val capitalUsagePercent: Double,
    val leverageForLots: Double,
    /** В симуляции лимит только % капитала и плечо — без cap брокера. */
    val maxLots: Int = STRATEGY_TEST_SIM_MAX_LOTS_UNCAPPED,
)

internal data class StrategyTestEntrySizingPreview(
    val quantityLots: Int,
    val executionNotionalRub: Double,
    val lotsFromLeverage: Int,
)

/** Оценка лотов/номинала на входе (последние цены TATN/TATNP). */
internal fun previewStrategyTestEntrySizing(
    bar: DataPoint,
    accountSizeRub: Double,
    capitalUsagePercent: Double,
    leverageForLots: Double,
): StrategyTestEntrySizingPreview? {
    if (bar.tatnClose <= 0.0 || bar.tatnpClose <= 0.0) return null
    val maxLots = STRATEGY_TEST_SIM_MAX_LOTS_UNCAPPED
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
            maxLots = maxLots,
        )
    )
    return StrategyTestEntrySizingPreview(
        quantityLots = sizing.quantityLots,
        executionNotionalRub = sizing.executionNotionalRub,
        lotsFromLeverage = sizing.lotsFromLeverage,
    )
}

/** Номинал пары на баре — как Prod lot sizing (cash × usage%, плечо на liquid). */
internal fun strategyTestPairNotionalRub(
    bar: DataPoint,
    accountSizeRub: Double,
    capitalUsagePercent: Double,
    leverageForLots: Double,
    maxLots: Int = STRATEGY_TEST_SIM_MAX_LOTS_UNCAPPED,
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
            maxLots = maxLots.coerceAtLeast(SPREAD_LOT_MIN_LOTS),
        )
    )
    return max(sizing.executionNotionalRub, spreadPairNotionalRub(
        bar.tatnClose,
        bar.tatnpClose,
        1,
        SPREAD_LOT_MIN_LOTS,
    ))
}

internal fun strategyTestAvgExecutionNotionalRub(trades: List<PortfolioClosedTrade>): Double? {
    val notionals = trades.map { it.executionNotionalRub }.filter { it > 0.0 }
    return notionals.average().takeIf { notionals.isNotEmpty() && !it.isNaN() }
}

internal fun formatStrategyTestSizingHint(
    preview: StrategyTestEntrySizingPreview?,
    avgNotionalRub: Double?,
): String? {
    preview ?: return null
    val avgPart = avgNotionalRub?.let { avg ->
        " · ср. номинал сделки ${"%.0f".format(Locale.US, avg)} ₽"
    }.orEmpty()
    return "~${preview.quantityLots} л · ~${"%.0f".format(Locale.US, preview.executionNotionalRub)} ₽ номинал$avgPart"
}

internal fun buildStrategyTestSimOptions(
    context: Context,
    accountSizeRub: Double,
    maxLossDdPercent: Double,
): ZStrategySimOptions {
    val spreadSlip = TradeExecutionLog.medianSlippageSpreadPts(context)
        ?: DEFAULT_STRATEGY_TEST_SLIPPAGE_SPREAD_PTS
    return ZStrategySimOptions(
        slippageSpreadPts = spreadSlip.coerceAtLeast(0.0),
        maxLossRub = resolveStrategyTestMaxLossRub(accountSizeRub, maxLossDdPercent),
    )
}

/** Money-stop в ₽: доля счёта; 0% → без ограничения. */
internal fun resolveStrategyTestMaxLossRub(accountSizeRub: Double, ddPercent: Double): Double {
    if (ddPercent <= 0.0) return 0.0
    return accountSizeRub.coerceAtLeast(1.0) * (ddPercent / 100.0)
}

internal fun formatStrategyTestMaxLossDdHint(ddPercent: Double, accountSizeRub: Double): String =
    if (ddPercent <= 0.0) {
        "money-stop выкл."
    } else {
        val rub = resolveStrategyTestMaxLossRub(accountSizeRub, ddPercent)
        "money-stop ${"%.1f".format(Locale.US, ddPercent)}% ≈ ${"%.0f".format(Locale.US, rub)} ₽/сделку"
    }

internal fun loadStrategyTestMaxLossDdPercent(context: Context): Double =
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .getFloat(PREF_STRATEGY_TEST_MAX_LOSS_DD_PCT, DEFAULT_STRATEGY_TEST_MAX_LOSS_DD_PERCENT.toFloat())
        .toDouble()
        .coerceIn(0.0, 50.0)

internal fun saveStrategyTestMaxLossDdPercent(context: Context, percent: Double) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_STRATEGY_TEST_MAX_LOSS_DD_PCT, percent.toFloat().coerceIn(0f, 50f))
        .apply()
}

internal fun buildStrategyTestSimOptions(context: Context): ZStrategySimOptions =
    buildStrategyTestSimOptions(
        context = context,
        accountSizeRub = DEFAULT_STRATEGY_TEST_ACCOUNT_RUB,
        maxLossDdPercent = DEFAULT_STRATEGY_TEST_MAX_LOSS_DD_PERCENT,
    )

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
