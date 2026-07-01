package com.example.moexmvp

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

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
    /** Чистый ₽ ≈ Δпп × rubPerSpreadPoint (калибровка как в шторке). */
    val rubPerSpreadPoint: Double,
    val fromEntry: Boolean,
    val pnlAxisBrokerCalibrated: Boolean = false,
)

internal enum class SpreadDeltaChartPnlAxisMode {
    /** netPnlRubApprox / Δпп — parity со шторкой (GetPortfolio). */
    NetBrokerCalibrated,
    /** MOEX net (комиссия + overnight) на текущем Δ. */
    NetMoexEstimate,
    /** Справочная gross-шкала без открытой сделки. */
    ReferenceGross,
}

/** Δпп, подразумеваемый gross PnL брокера и номиналом (Tinkoff expectedYield). */
internal fun brokerImpliedSpreadDeltaPp(grossRub: Double, effNotionalRub: Double): Double? {
    if (effNotionalRub <= 1e-6) return null
    return grossRub * 100.0 / effNotionalRub
}

/** Знаменатель калибровки ₽/п.п.: live MOEX Δпп (parity со шторкой на хвосте), иначе broker implied. */
internal fun resolveSpreadDeltaCalibrationPp(
    moexDeltaPp: Double,
    brokerGrossRub: Double?,
    effNotionalRub: Double,
): Double? {
    val brokerDelta = brokerGrossRub?.let { brokerImpliedSpreadDeltaPp(it, effNotionalRub) }
    return when {
        abs(moexDeltaPp) > 1e-6 -> moexDeltaPp
        brokerDelta != null && abs(brokerDelta) > 1e-6 -> brokerDelta
        else -> null
    }
}

/**
 * ₽ на 1 п.п. Δ спреда для правой оси.
 * При открытой сделке: net Tinkoff / live MOEX Δпп (хвост 1м); broker implied — если MOEX Δ ≈ 0.
 */
internal fun resolveSpreadDeltaChartRubPerPoint(
    openExec: SandboxSpreadExecUi?,
    currentDeltaPp: Double,
    sourcePoints: List<DataPoint>,
    executionMode: TinkoffExecutionMode,
    leverage: Double,
    commissionPercentPerSide: Double,
    tradeAmountRub: Double,
): Pair<Double, SpreadDeltaChartPnlAxisMode> {
    val pnlLeverage = portfolioPnlLeverageMultiplier(executionMode, leverage)
    if (openExec == null) {
        return spreadPnlRubPerSpreadPoint(tradeAmountRub * pnlLeverage) to
            SpreadDeltaChartPnlAxisMode.ReferenceGross
    }
    val history = sourcePoints
    val tradeNotional = resolveTradeNotionalRubForPnl(openExec, history, tradeAmountRub)
    val effNotional = tradeNotional * pnlLeverage
    val brokerNet = openExec.netPnlRubApprox.takeUnless { it.isNaN() }
    val brokerGross = brokerOpenTradeGrossRub(openExec)
    val brokerCalibrated = brokerGross != null && brokerNet != null
    val calibDelta = resolveSpreadDeltaCalibrationPp(
        moexDeltaPp = currentDeltaPp,
        brokerGrossRub = brokerGross,
        effNotionalRub = effNotional,
    )

    if (brokerCalibrated && calibDelta != null) {
        return (brokerNet!! / calibDelta) to SpreadDeltaChartPnlAxisMode.NetBrokerCalibrated
    }
    if (brokerNet != null && calibDelta != null) {
        return (brokerNet / calibDelta) to SpreadDeltaChartPnlAxisMode.NetMoexEstimate
    }

    val entryDateLabel = history
        .minByOrNull { kotlin.math.abs(it.timestampMillis - openExec.barTimestampMillis) }
        ?.tradeDate ?: openExec.entryTimeMsk
    val exitDateLabel = history.lastOrNull()?.tradeDate ?: openExec.entryTimeMsk
    val (commRub, ovnRub) = portfolioTradeCommissionAndOvernightRub(
        notionalRub = tradeNotional,
        leverage = pnlLeverage,
        commissionPercentPerSide = commissionPercentPerSide,
        entryDateLabel = entryDateLabel,
        exitDateLabel = exitDateLabel,
        includeExitCommission = false,
    )
    val grossRub = spreadPnlToRubApprox(currentDeltaPp, effNotional)
    val netRub = grossRub - commRub - ovnRub
    if (abs(currentDeltaPp) > 1e-6) {
        return (netRub / currentDeltaPp) to SpreadDeltaChartPnlAxisMode.NetMoexEstimate
    }
    return spreadPnlRubPerSpreadPoint(effNotional) to SpreadDeltaChartPnlAxisMode.NetMoexEstimate
}

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
    commissionPercentPerSide: Double,
    tradeAmountRub: Double,
): SpreadDelta15mChartContext? {
    if (chartPoints.isEmpty()) return null
    val history = sourcePoints.ifEmpty { chartPoints }
    val openLeg = openExec?.takeIf {
        it.signalType == StrategySignalType.EnterLong ||
            it.signalType == StrategySignalType.EnterShort
    }

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
        val currentDelta = deltas.lastOrNull() ?: 0.0
        val (rubPerPoint, pnlMode) = resolveSpreadDeltaChartRubPerPoint(
            openExec = openLeg,
            currentDeltaPp = currentDelta,
            sourcePoints = history,
            executionMode = executionMode,
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            tradeAmountRub = tradeAmountRub,
        )
        return SpreadDelta15mChartContext(
            title = "Δ спред 15м · от входа",
            subtitle = when (pnlMode) {
                SpreadDeltaChartPnlAxisMode.NetBrokerCalibrated ->
                    "Правая ось — чистый PnL (шторка · Tinkoff); хвост Δ — live 1м"
                else ->
                    "Правая ось — чистый PnL (оценка MOEX · комиссия и overnight)"
            },
            labels = chartPoints.map { it.tradeDate },
            deltasPp = deltas,
            rubPerSpreadPoint = rubPerPoint,
            fromEntry = true,
            pnlAxisBrokerCalibrated = pnlMode == SpreadDeltaChartPnlAxisMode.NetBrokerCalibrated,
        )
    }

    val daySeries = spreadDeltaFromDayOpenSeries(chartPoints) ?: return null
    val (rubPerPoint, _) = resolveSpreadDeltaChartRubPerPoint(
        openExec = null,
        currentDeltaPp = daySeries.deltasPp.lastOrNull() ?: 0.0,
        sourcePoints = history,
        executionMode = executionMode,
        leverage = leverage,
        commissionPercentPerSide = commissionPercentPerSide,
        tradeAmountRub = tradeAmountRub,
    )
    val pnlLeverage = portfolioPnlLeverageMultiplier(executionMode, leverage)
    val refNotional = tradeAmountRub * pnlLeverage
    return SpreadDelta15mChartContext(
        title = "Δ спред 15м · от открытия дня",
        subtitle = buildString {
            append("Правая ось — справочный gross long от 07:30 · ≈")
            append(String.format(Locale.US, "%.0f", refNotional))
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
