package com.example.moexmvp

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexSpreadHourlyVolatilityTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun buildSpreadHourlyVolatilityReport_groupsDeltasByBarHour() {
        val points = listOf(
            point("2026-05-19 10:00", 5.00),
            point("2026-05-19 10:15", 5.10),
            point("2026-05-19 10:30", 5.30),
            point("2026-05-19 11:00", 5.00),
            point("2026-05-19 11:15", 5.05),
            point("2026-05-20 10:00", 5.10),
            point("2026-05-20 10:15", 5.20),
            point("2026-05-20 10:30", 5.50),
        )
        val report = buildSpreadHourlyVolatilityReport(points, zoneId = zone)!!
        val hour10 = report.bars[10]
        val hour11 = report.bars[11]

        assertEquals(4, hour10.deltaSampleCount)
        assertEquals(1, hour11.deltaSampleCount)
        assertTrue(hour10.volatility > 0.0)
        assertEquals(10, report.peakHour)
        assertEquals(2, report.calendarDays)
    }

    @Test
    fun buildSpreadHourlyVolatilityReport_returnsNullForShortSeries() {
        assertEquals(null, buildSpreadHourlyVolatilityReport(emptyList()))
        assertEquals(
            null,
            buildSpreadHourlyVolatilityReport(
                listOf(
                    point("2026-05-19 10:00", 5.0),
                    point("2026-05-19 10:15", 5.1),
                ),
            ),
        )
    }

    @Test
    fun barHourMsk_usesTradeDateLabel() {
        val point = point("2026-05-19 14:45", 5.0)
        assertEquals(14, barHourMsk(point, zone))
    }

    private fun point(label: String, spread: Double): DataPoint {
        val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
        return DataPoint(
            timestampMillis = ts.atZone(zone).toInstant().toEpochMilli(),
            tradeDate = label,
            tatnClose = 100.0,
            tatnpClose = 100.0 / (1.0 + spread / 100.0),
            spreadPercent = spread,
            diff = 0.0,
            zScore = 0.0,
        )
    }
}
