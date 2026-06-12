package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val testM15Zone: ZoneId = ZoneId.of("Europe/Moscow")

/** Тестовый 15м бар с согласованными tradeDate и timestampMillis (+15 мин MSK). */
internal fun testM15Bar(
    tradeDate: String,
    z: Double,
    spread: Double = 10.0,
): DataPoint {
    val millis = LocalDateTime.parse(tradeDate, portfolio15mLabelFormatter)
        .atZone(testM15Zone)
        .toInstant()
        .toEpochMilli()
    return DataPoint(
        timestampMillis = millis,
        tradeDate = tradeDate,
        tatnClose = 100.0,
        tatnpClose = 90.0,
        spreadPercent = spread,
        diff = 0.0,
        zScore = z,
    )
}

/** Серия соседних 15м баров от (day, hour, minute); [zAt] — Z на каждом шаге. */
internal fun testM15BarSeries(
    day: LocalDate,
    hour: Int,
    minute: Int,
    zAt: List<Double>,
    spread: Double = 10.0,
): List<DataPoint> {
    val base = day.atTime(hour, minute).atZone(testM15Zone).toInstant().toEpochMilli()
    val step = 15 * 60_000L
    return zAt.mapIndexed { i, z ->
        val ts = base + i * step
        val label = Instant.ofEpochMilli(ts).atZone(testM15Zone).toLocalDateTime()
            .format(portfolio15mLabelFormatter)
        DataPoint(
            timestampMillis = ts,
            tradeDate = label,
            tatnClose = 100.0,
            tatnpClose = 90.0,
            spreadPercent = spread,
            diff = 0.0,
            zScore = z,
        )
    }
}
