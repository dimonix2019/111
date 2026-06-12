package com.example.moexmvp

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** Часы MSK на графике волатильности (без ночного простоя 0–7). */
internal const val SPREAD_HOURLY_VOLATILITY_HOUR_START_MSK = 8
internal const val SPREAD_HOURLY_VOLATILITY_HOUR_END_MSK = 23

internal fun spreadHourlyVolatilityTradingHoursRange(): IntRange =
    SPREAD_HOURLY_VOLATILITY_HOUR_START_MSK..SPREAD_HOURLY_VOLATILITY_HOUR_END_MSK

internal fun filterSpreadHourlyVolatilityForDisplay(
    bars: List<SpreadHourlyVolatilityBar>,
): List<SpreadHourlyVolatilityBar> =
    bars.filter { it.hour in spreadHourlyVolatilityTradingHoursRange() }

/** Волатильность спреда для одного часа торгового дня (MSK, 0–23). */
internal data class SpreadHourlyVolatilityBar(
    val hour: Int,
    /** σ |Δspread%| между соседними 15м барами в этом часе. */
    val volatility: Double,
    val deltaSampleCount: Int,
    val spreadSampleCount: Int,
)

internal data class SpreadHourlyVolatilityReport(
    val bars: List<SpreadHourlyVolatilityBar>,
    val calendarDays: Int,
    val totalDeltaSamples: Int,
    val peakHour: Int?,
    val peakVolatility: Double,
)

internal fun barHourMsk(point: DataPoint, zoneId: ZoneId = moexZoneId): Int {
    parseBarLocalTimeMsk(point.tradeDate)?.hour?.let { return it }
    return Instant.ofEpochMilli(point.timestampMillis).atZone(zoneId).hour
}

internal fun buildSpreadHourlyVolatilityReport(
    points: List<DataPoint>,
    zoneId: ZoneId = moexZoneId,
    minDeltaSamplesPerHour: Int = 2,
): SpreadHourlyVolatilityReport? {
    if (points.size < 3) return null

    val deltasByHour = Array(24) { mutableListOf<Double>() }
    val spreadsByHour = Array(24) { mutableListOf<Double>() }
    val calendarDays = linkedSetOf<String>()

    for (point in points) {
        val hour = barHourMsk(point, zoneId)
        spreadsByHour[hour].add(point.spreadPercent)
        parseBarLocalDateMsk(point.tradeDate)?.toString()?.let { calendarDays.add(it) }
    }

    for (i in 1 until points.size) {
        if (!isConsecutiveM15Bar(points[i - 1], points[i])) continue
        val delta = abs(points[i].spreadPercent - points[i - 1].spreadPercent)
        val hour = barHourMsk(points[i], zoneId)
        deltasByHour[hour].add(delta)
    }

    val bars = (0 until 24).map { hour ->
        val deltas = deltasByHour[hour]
        val volatility = if (deltas.size >= minDeltaSamplesPerHour) {
            populationStdDev(deltas)
        } else {
            0.0
        }
        SpreadHourlyVolatilityBar(
            hour = hour,
            volatility = volatility,
            deltaSampleCount = deltas.size,
            spreadSampleCount = spreadsByHour[hour].size,
        )
    }

    val peak = bars.filter {
        it.hour in spreadHourlyVolatilityTradingHoursRange() &&
            it.deltaSampleCount >= minDeltaSamplesPerHour
    }.maxByOrNull { it.volatility }

    return SpreadHourlyVolatilityReport(
        bars = bars,
        calendarDays = calendarDays.size,
        totalDeltaSamples = deltasByHour.sumOf { it.size },
        peakHour = peak?.hour,
        peakVolatility = peak?.volatility ?: 0.0,
    )
}

private fun populationStdDev(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mean = values.average()
    val variance = values.map { (it - mean) * (it - mean) }.average()
    return sqrt(variance)
}

internal fun formatSpreadHourlyVolatilitySubtitle(report: SpreadHourlyVolatilityReport): String {
    val peakPart = report.peakHour?.let { hour ->
        String.format(
            Locale.US,
            " · пик %02d:00 MSK (σ %.3f%%)",
            hour,
            report.peakVolatility,
        )
    }.orEmpty()
    return String.format(
        Locale.US,
        "σ |Δspread| по 15м · %d дн.%s",
        report.calendarDays,
        peakPart,
    )
}
