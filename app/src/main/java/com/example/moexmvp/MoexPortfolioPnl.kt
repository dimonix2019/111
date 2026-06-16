package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal fun spreadPnlToRubApprox(pnlSpreadPoints: Double, notionalRub: Double): Double {
    return notionalRub * (pnlSpreadPoints / 100.0)
}

/** В Prod PnL считаем по реальному номиналу без симуляционного плеча. */
internal fun portfolioPnlLeverageMultiplier(
    mode: TinkoffExecutionMode,
    configuredLeverage: Double,
): Double = when (mode) {
    TinkoffExecutionMode.Prod -> 1.0
    TinkoffExecutionMode.Sandbox -> configuredLeverage.coerceAtLeast(1.0)
}

internal fun resolveTradeNotionalRubForPnl(
    exec: SandboxSpreadExecUi,
    points: List<DataPoint>,
    fallbackRub: Double = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
): Double {
    exec.executionNotionalRub.takeIf { it > 0.0 }?.let { return it }
    if (exec.quantityLots > 0 && points.isNotEmpty()) {
        val bar = points.minByOrNull { kotlin.math.abs(it.timestampMillis - exec.barTimestampMillis) }
            ?: points.last()
        if (bar.tatnClose > 0.0 && bar.tatnpClose > 0.0) {
            return spreadPairNotionalRub(bar.tatnClose, bar.tatnpClose, 1, exec.quantityLots)
        }
    }
    return fallbackRub
}

internal fun overnightDays(entryDate: String, endDate: String): Long {
    val entry = parseChartDateLabel(entryDate) ?: return 0L
    val end = parseChartDateLabel(endDate) ?: return 0L
    return kotlin.math.max(0L, java.time.temporal.ChronoUnit.DAYS.between(entry, end))
}

/** Комиссия (1 или 2 стороны) и овернайт за период удержания сделки. */
internal fun portfolioTradeCommissionAndOvernightRub(
    notionalRub: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
    entryDateLabel: String,
    exitDateLabel: String,
    includeExitCommission: Boolean
): Pair<Double, Double> {
    val effectiveNotional = notionalRub * leverage
    val commissionPerSideRub = effectiveNotional * (commissionPercentPerSide / 100.0)
    val commissionRub = commissionPerSideRub * if (includeExitCommission) 2 else 1
    val borrowedRub = notionalRub * (leverage - 1.0).coerceAtLeast(0.0)
    val overnightPerDayRub =
        borrowedRub * (TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0)
    val days = overnightDays(entryDateLabel, exitDateLabel)
    return commissionRub to (overnightPerDayRub * days)
}

/** Daily labels `yyyy-MM-dd` or 15m labels `yyyy-MM-dd HH:mm` from [portfolio15mLabelFormatter]. */
internal fun drawdownRubSeriesFromEquity(equityRub: List<Double>): List<Double> {
    var peak = 0.0
    return equityRub.map { eq ->
        peak = max(peak, eq)
        peak - eq
    }
}

/** Последний снимок equity за календарный день (для графика на длинном 15м ряду). */
internal fun equityCurveDailyForChart(
    barLabels: List<String>,
    equityRub: List<Double>
): Triple<List<String>, List<Double>, List<Double>> {
    if (barLabels.isEmpty() || equityRub.isEmpty()) {
        return Triple(emptyList(), emptyList(), emptyList())
    }
    val n = min(barLabels.size, equityRub.size)
    val byDay = linkedMapOf<String, Double>()
    for (i in 0 until n) {
        val label = barLabels[i].trim()
        val day = if (label.length >= 10) label.take(10) else label
        byDay[day] = equityRub[i]
    }
    val labels = byDay.keys.toList()
    val equity = byDay.values.toList()
    val drawdown = drawdownRubSeriesFromEquity(equity).map { -it }
    return Triple(labels, equity, drawdown)
}

internal fun parseChartDateLabel(label: String): LocalDate? {
    val trimmed = label.trim()
    if (trimmed.length >= 10) {
        runCatching { return LocalDate.parse(trimmed.take(10)) }.getOrNull()
    }
    return runCatching { LocalDateTime.parse(trimmed, portfolio15mLabelFormatter).toLocalDate() }
        .getOrNull()
}

/**
 * Mirrors [backtestZStrategy] but records round-trips, open leg, equity path (realized + MTM each bar),
 * and portfolio statistics. PnL is in spread %-points; rub figures scale by effective notional (own capital × leverage).
 *
 * @param compoundReturns если true, перед каждой новой сделкой пересчитываем собственный капитал как
 * `max(1 ₽, стартовый notional + накопленный realizedRub)` и от него снова берём плечо, комиссию и овернайт —
 * прибыль увеличивает размер следующей позиции (упрощённая капитализация в симуляции).
 */
