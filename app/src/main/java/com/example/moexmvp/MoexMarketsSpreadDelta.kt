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
