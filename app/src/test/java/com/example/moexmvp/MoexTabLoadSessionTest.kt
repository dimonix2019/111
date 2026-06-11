package com.example.moexmvp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MoexTabLoadSessionTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun m15PointsCoverPortfolioLookback_whenSpanSufficient() {
        val today = LocalDate.of(2026, 6, 10)
        val points = listOf(
            dp(today.minusDays(40), 0.0),
            dp(today, 0.1),
        )
        assertTrue(m15PointsCoverPortfolioLookback(points, lookbackDays = 30))
    }

    @Test
    fun m15PointsCoverPortfolioLookback_falseWhenTooShort() {
        val today = LocalDate.of(2026, 6, 10)
        val points = listOf(
            dp(today.minusDays(5), 0.0),
            dp(today, 0.1),
        )
        assertFalse(m15PointsCoverPortfolioLookback(points, lookbackDays = 30))
    }

    private fun dp(day: LocalDate, z: Double): DataPoint {
        val zoned = day.atTime(10, 0).atZone(zone)
        return DataPoint(
            timestampMillis = zoned.toInstant().toEpochMilli(),
            tradeDate = day.toString(),
            tatnClose = 100.0,
            tatnpClose = 100.0,
            spreadPercent = 1.0,
            diff = 0.0,
            zScore = z,
        )
    }
}
