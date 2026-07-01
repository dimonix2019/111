package com.example.moexmvp

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal data class SpreadDeltaSeries(
    val labels: List<String>,
    /** Δ спреда в п.п. от открытия торгового дня (07:30 МСК). */
    val deltasPp: List<Double>,
    val dayOpenSpreadPercent: Double?,
)

internal val SPREAD_DELTA_LINE_COLOR = Color(0xFF69F0FA)
internal val SPREAD_DELTA_ZERO_LINE = ChartReferenceLine(
    value = 0.0,
    color = Color(0xFF616161),
    label = "0",
    dashOnPx = 6f,
    dashOffPx = 6f,
)

internal fun formatSpreadDeltaPp(value: Double): String =
    String.format(Locale.US, "%+.2f", value)

internal fun formatSpreadDeltaAxisTick(value: Double): String =
    String.format(Locale.US, "%+.2f", value)

internal fun spreadPnlRubPerSpreadPoint(effectiveNotionalRub: Double): Double =
    effectiveNotionalRub / 100.0

internal data class SpreadDelta15mChartContext(
    val title: String,
    val subtitle: String,
    val labels: List<String>,
    val deltasPp: List<Double>,
    /** gross ₽ ≈ Δпп × rubPerSpreadPoint */
    val rubPerSpreadPoint: Double,
    val fromEntry: Boolean,
)

/**
 * 15м Δ спреда для графика «Рынок».
 * Открытая сделка — Δ от входа (без сброса по дням, до 3+ суток).
 * Иначе — Δ от открытия торгового дня; правая ось — справочный gross.
 */
internal fun buildSpreadDelta15mChartContext(
    chartPoints: List<DataPoint>,
    sourcePoints: List<DataPoint>,
    openExec: SandboxSpreadExecUi?,
    executionMode: TinkoffExecutionMode,
    leverage: Double,
    tradeAmountRub: Double,
): SpreadDelta15mChartContext? {
    if (chartPoints.isEmpty()) return null
    val history = sourcePoints.ifEmpty { chartPoints }
    val pnlLeverage = portfolioPnlLeverageMultiplier(executionMode, leverage)
    val openLeg = openExec?.takeIf {
        it.signalType == StrategySignalType.EnterLong ||
            it.signalType == StrategySignalType.EnterShort
    }
    val effNotional = when {
        openLeg != null -> {
            val base = resolveTradeNotionalRubForPnl(openLeg, history, tradeAmountRub)
            base * pnlLeverage
        }
        else -> tradeAmountRub * pnlLeverage
    }
    val rubPerPoint = spreadPnlRubPerSpreadPoint(effNotional)

    if (openLeg != null) {
        val entrySpread = resolveEntrySpreadPercent(
            openLeg.entrySpreadPercent,
            openLeg.barTimestampMillis,
            history,
        )
        if (entrySpread.isNaN()) return null
        val entryMillis = openLeg.barTimestampMillis
        val deltas = chartPoints.map { pt ->
            if (barMillisAt(pt) < entryMillis) {
                0.0
            } else {
                openSpreadMtmPoints(openLeg.signalType, entrySpread, pt.spreadPercent)
            }
        }
        return SpreadDelta15mChartContext(
            title = "Δ спред 15м · от входа",
            subtitle = buildString {
                append("Вход ")
                append(formatPortfolioSpreadPercent(entrySpread))
                append(" · gross PnL справа · номинал ≈")
                append(String.format(Locale.US, "%.0f", effNotional))
                append(" ₽")
            },
            labels = chartPoints.map { it.tradeDate },
            deltasPp = deltas,
            rubPerSpreadPoint = rubPerPoint,
            fromEntry = true,
        )
    }

    val daySeries = spreadDeltaFromDayOpenSeries(chartPoints) ?: return null
    return SpreadDelta15mChartContext(
        title = "Δ спред 15м · от открытия дня",
        subtitle = buildString {
            append("Правая ось — справочный gross long от 07:30 · номинал ≈")
            append(String.format(Locale.US, "%.0f", effNotional))
            append(" ₽")
        },
        labels = daySeries.labels,
        deltasPp = daySeries.deltasPp,
        rubPerSpreadPoint = rubPerPoint,
        fromEntry = false,
    )
}

/**
 * Δ спреда 15м: текущий spread% − spread на открытии календарного дня (≥07:30 МСК).
 * Сбрасывается каждый торговый день — прямой драйвер intraday PnL long-спреда.
 */
internal fun spreadDeltaFromDayOpenSeries(points: List<DataPoint>): SpreadDeltaSeries? {
    if (points.isEmpty()) return null
    val openByDay = linkedMapOf<LocalDate, Double>()
    points.forEach { pt ->
        val day = m15LabelCalendarDate(pt.tradeDate) ?: return@forEach
        if (!openByDay.containsKey(day)) {
            openByDay[day] = spreadPercentAtTradingDayOpen(points, day) ?: pt.spreadPercent
        }
    }
    if (openByDay.isEmpty()) return null
    val deltas = points.map { pt ->
        val day = m15LabelCalendarDate(pt.tradeDate)
        val base = day?.let { openByDay[it] } ?: pt.spreadPercent
        pt.spreadPercent - base
    }
    val lastDay = points.lastOrNull()?.let { m15LabelCalendarDate(it.tradeDate) }
    return SpreadDeltaSeries(
        labels = points.map { it.tradeDate },
        deltasPp = deltas,
        dayOpenSpreadPercent = lastDay?.let { openByDay[it] },
    )
}

internal data class IntradaySpreadDeltaSeries(
    val labels: List<String>,
    val deltasPp: List<Double>,
    val dayOpenSpreadPercent: Double,
)

/** Δ спреда 1м за сегодня от открытия дня (та же база, что у Spread 15м). */
internal fun buildIntraday1mSpreadDeltaSeries(
    m15Points: List<DataPoint>,
    aligned: AlignedIntraday1mQuotes,
    zone: ZoneId = moexZoneId,
): IntradaySpreadDeltaSeries? {
    if (aligned.labels.isEmpty()) return null
    val today = LocalDate.now(zone)
    val dayOpen = spreadPercentAtTradingDayOpen(m15Points, today)
        ?: spreadPercentFromPairCloses(aligned.tatnCloses.first(), aligned.tatnpCloses.first())
        ?: return null
    val labels = mutableListOf<String>()
    val deltas = mutableListOf<Double>()
    aligned.labels.indices.forEach { i ->
        val spread = spreadPercentFromPairCloses(aligned.tatnCloses[i], aligned.tatnpCloses[i])
            ?: return@forEach
        labels += aligned.labels[i]
        deltas += spread - dayOpen
    }
    if (labels.isEmpty()) return null
    return IntradaySpreadDeltaSeries(labels, deltas, dayOpen)
}
