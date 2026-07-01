package com.example.moexmvp

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    /** Gross ₽ на 1 п.п. Δ (номинал / 100); стабильная шкала. */
    val rubPerSpreadPoint: Double,
    /** Сдвиг правой оси: чистый ₽ = Δпп × rubPerSpreadPoint + netOffsetRub. */
    val netOffsetRub: Double = 0.0,
    val fromEntry: Boolean,
    /** Δ п.п. на баре входа (может ≠ 0, если спрэд бара ≠ entrySpread). */
    val entryDeltaPp: Double? = null,
    val pnlAxisBrokerCalibrated: Boolean = false,
)

internal data class SpreadDeltaChartRubAxis(
    val rubPerSpreadPoint: Double,
    val netOffsetRub: Double = 0.0,
    val mode: SpreadDeltaChartPnlAxisMode,
)

internal fun spreadDeltaNetRubAtPp(deltaPp: Double, axis: SpreadDeltaChartRubAxis): Double =
    deltaPp * axis.rubPerSpreadPoint + axis.netOffsetRub

/** 15м свечи Δ спреда (п.п.): close = Δ, open = Δ предыдущего бара. */
internal fun buildSpreadDeltaCandles(
    labels: List<String>,
    deltasPp: List<Double>,
): List<CandlePoint> {
    if (labels.isEmpty() || labels.size != deltasPp.size) return emptyList()
    return labels.indices.map { i ->
        val close = deltasPp[i]
        val open = if (i == 0) close else deltasPp[i - 1]
        CandlePoint(
            label = labels[i],
            open = open,
            high = max(open, close),
            low = min(open, close),
            close = close,
        )
    }
}

/** Точки для TradingView (zScore = Δ п.п. для маркеров/совместимости). */
internal fun buildSpreadDeltaDisplayPoints(
    labels: List<String>,
    deltasPp: List<Double>,
): List<DataPoint> {
    if (labels.isEmpty() || labels.size != deltasPp.size) return emptyList()
    return labels.indices.map { i ->
        val label = labels[i]
        val ts = runCatching { m15CandleLabelToUnixSec(label) * 1000L }.getOrElse { 0L }
        DataPoint(
            timestampMillis = ts,
            tradeDate = label,
            tatnClose = 0.0,
            tatnpClose = 0.0,
            spreadPercent = 0.0,
            diff = 0.0,
            zScore = deltasPp[i],
        )
    }
}

internal fun buildSpreadDeltaTvReferenceLines(
    context: SpreadDelta15mChartContext,
): List<ChartReferenceLine> {
    val lines = mutableListOf<ChartReferenceLine>()
    val zeroLine = if (context.fromEntry) {
        val entry = context.entryDeltaPp ?: 0.0
        ChartReferenceLine(
            value = entry,
            color = Color(0xFFFFB74D),
            label = "вход ${formatSpreadDeltaPp(entry)}",
            dashOnPx = 6f,
            dashOffPx = 6f,
        )
    } else {
        SPREAD_DELTA_ZERO_LINE
    }
    lines += zeroLine
    val tailDelta = context.deltasPp.lastOrNull()
    if (tailDelta != null && !tailDelta.isNaN()) {
        val tailPnl = spreadDeltaNetRubAtPp(
            tailDelta,
            SpreadDeltaChartRubAxis(
                rubPerSpreadPoint = context.rubPerSpreadPoint,
                netOffsetRub = context.netOffsetRub,
                mode = if (context.pnlAxisBrokerCalibrated) {
                    SpreadDeltaChartPnlAxisMode.NetBrokerCalibrated
                } else {
                    SpreadDeltaChartPnlAxisMode.ReferenceGross
                },
            ),
        )
        lines += ChartReferenceLine(
            value = tailDelta,
            color = Color(0xFF60A5FA),
            label = buildString {
                append("сейчас ")
                append(formatSpreadDeltaPp(tailDelta))
                append(" · ")
                append(formatRubAxisValue(tailPnl))
            },
        )
    }
    return lines
}

internal fun spreadDeltaTvSubtitle(context: SpreadDelta15mChartContext): String {
    val tailDelta = context.deltasPp.lastOrNull() ?: return context.subtitle
    val tailPnl = spreadDeltaNetRubAtPp(
        tailDelta,
        SpreadDeltaChartRubAxis(
            rubPerSpreadPoint = context.rubPerSpreadPoint,
            netOffsetRub = context.netOffsetRub,
            mode = SpreadDeltaChartPnlAxisMode.ReferenceGross,
        ),
    )
    return buildString {
        append(formatSpreadDeltaPp(tailDelta))
        append(" · PnL ")
        append(formatRubAxisValue(tailPnl))
        if (context.pnlAxisBrokerCalibrated) append(" · шторка")
    }
}

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

/** Хвост Δпп: broker gross → п.п., чтобы линия двигалась с Tinkoff, а не только шкала ₽. */
/** Δ п.п. на первом 15м-баре с временем ≥ входа (до broker-патча хвоста). */
internal fun resolveSpreadDeltaAtEntryBar(
    chartPoints: List<DataPoint>,
    deltasPp: List<Double>,
    entryMillis: Long,
): Double? {
    if (chartPoints.isEmpty() || chartPoints.size != deltasPp.size) return null
    val entryIndex = chartPoints.indexOfFirst { barMillisAt(it) >= entryMillis }
    val idx = if (entryIndex >= 0) entryIndex else chartPoints.lastIndex
    return deltasPp.getOrNull(idx)
}

internal fun patchSpreadDeltaTailFromBroker(
    deltasPp: List<Double>,
    brokerGrossRub: Double?,
    effNotionalRub: Double,
): List<Double> {
    if (deltasPp.isEmpty() || brokerGrossRub == null) return deltasPp
    val tail = brokerImpliedSpreadDeltaPp(brokerGrossRub, effNotionalRub) ?: return deltasPp
    if (abs(deltasPp.last() - tail) < 1e-9) return deltasPp
    return deltasPp.toMutableList().also { it[it.lastIndex] = tail }
}

/**
 * ₽ на 1 п.п. Δ спреда для правой оси.
 * При открытой сделке с брокером: фикс. номинал/100 + сдвиг комиссии/overnight; хвост Δ — broker gross.
 */
internal fun resolveSpreadDeltaChartRubAxis(
    openExec: SandboxSpreadExecUi?,
    currentDeltaPp: Double,
    sourcePoints: List<DataPoint>,
    executionMode: TinkoffExecutionMode,
    leverage: Double,
    commissionPercentPerSide: Double,
    tradeAmountRub: Double,
): SpreadDeltaChartRubAxis {
    val pnlLeverage = portfolioPnlLeverageMultiplier(executionMode, leverage)
    if (openExec == null) {
        return SpreadDeltaChartRubAxis(
            rubPerSpreadPoint = spreadPnlRubPerSpreadPoint(tradeAmountRub * pnlLeverage),
            mode = SpreadDeltaChartPnlAxisMode.ReferenceGross,
        )
    }
    val history = sourcePoints
    val tradeNotional = resolveTradeNotionalRubForPnl(openExec, history, tradeAmountRub)
    val effNotional = tradeNotional * pnlLeverage
    val fixedRubPer = spreadPnlRubPerSpreadPoint(effNotional)
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
    val holdingCostOffset = -(commRub + ovnRub)
    val brokerNet = openExec.netPnlRubApprox.takeUnless { it.isNaN() }
    val brokerGross = brokerOpenTradeGrossRub(openExec)
    val brokerCalibrated = brokerGross != null && brokerNet != null

    if (brokerCalibrated) {
        val brokerTail = brokerImpliedSpreadDeltaPp(brokerGross!!, effNotional) ?: 0.0
        val netOffset = brokerNet!! - brokerTail * fixedRubPer
        return SpreadDeltaChartRubAxis(
            rubPerSpreadPoint = fixedRubPer,
            netOffsetRub = netOffset,
            mode = SpreadDeltaChartPnlAxisMode.NetBrokerCalibrated,
        )
    }
    if (brokerNet != null) {
        return SpreadDeltaChartRubAxis(
            rubPerSpreadPoint = fixedRubPer,
            netOffsetRub = holdingCostOffset,
            mode = SpreadDeltaChartPnlAxisMode.NetMoexEstimate,
        )
    }

    if (abs(currentDeltaPp) > 1e-6) {
        val grossRub = spreadPnlToRubApprox(currentDeltaPp, effNotional)
        val netRub = grossRub - commRub - ovnRub
        return SpreadDeltaChartRubAxis(
            rubPerSpreadPoint = netRub / currentDeltaPp,
            mode = SpreadDeltaChartPnlAxisMode.NetMoexEstimate,
        )
    }
    return SpreadDeltaChartRubAxis(
        rubPerSpreadPoint = fixedRubPer,
        netOffsetRub = holdingCostOffset,
        mode = SpreadDeltaChartPnlAxisMode.NetMoexEstimate,
    )
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
        val moexDeltas = chartPoints.map { pt ->
            if (barMillisAt(pt) < entryMillis) {
                0.0
            } else {
                openSpreadMtmPoints(openLeg.signalType, entrySpread, pt.spreadPercent)
            }
        }
        val tradeNotional = resolveTradeNotionalRubForPnl(openLeg, history, tradeAmountRub)
        val effNotional = tradeNotional * portfolioPnlLeverageMultiplier(executionMode, leverage)
        val brokerGross = brokerOpenTradeGrossRub(openLeg)
        val rubAxis = resolveSpreadDeltaChartRubAxis(
            openExec = openLeg,
            currentDeltaPp = moexDeltas.lastOrNull() ?: 0.0,
            sourcePoints = history,
            executionMode = executionMode,
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            tradeAmountRub = tradeAmountRub,
        )
        val entryDeltaPp = resolveSpreadDeltaAtEntryBar(chartPoints, moexDeltas, entryMillis)
        val deltas = if (rubAxis.mode == SpreadDeltaChartPnlAxisMode.NetBrokerCalibrated) {
            patchSpreadDeltaTailFromBroker(moexDeltas, brokerGross, effNotional)
        } else {
            moexDeltas
        }
        return SpreadDelta15mChartContext(
            title = "Δ спред 15м · от входа",
            subtitle = when (rubAxis.mode) {
                SpreadDeltaChartPnlAxisMode.NetBrokerCalibrated ->
                    "Правая ось — чистый PnL (фикс. номинал); хвост Δ — Tinkoff gross"
                else ->
                    "Правая ось — чистый PnL (оценка MOEX · комиссия и overnight)"
            },
            labels = chartPoints.map { it.tradeDate },
            deltasPp = deltas,
            rubPerSpreadPoint = rubAxis.rubPerSpreadPoint,
            netOffsetRub = rubAxis.netOffsetRub,
            fromEntry = true,
            entryDeltaPp = entryDeltaPp,
            pnlAxisBrokerCalibrated = rubAxis.mode == SpreadDeltaChartPnlAxisMode.NetBrokerCalibrated,
        )
    }

    val daySeries = spreadDeltaFromDayOpenSeries(chartPoints) ?: return null
    val rubAxis = resolveSpreadDeltaChartRubAxis(
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
        rubPerSpreadPoint = rubAxis.rubPerSpreadPoint,
        netOffsetRub = rubAxis.netOffsetRub,
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
