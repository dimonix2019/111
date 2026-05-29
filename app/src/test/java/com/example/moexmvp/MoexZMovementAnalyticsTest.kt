package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexZMovementAnalyticsTest {

    @Test
    fun buildZMovementReport_zoneSharesSumNear100Percent() {
        val points = listOf(
            point("2026-01-01 10:00", 0.0),
            point("2026-01-01 10:15", 0.5),
            point("2026-01-01 10:30", 0.9),
            point("2026-01-01 10:45", -0.9),
            point("2026-01-01 11:00", -1.2),
            point("2026-01-01 11:15", 0.6)
        )
        val report = buildZMovementReport(points)!!
        val sumPct = report.zoneShares.sumOf { it.percent }
        assertEquals(6, report.barCount)
        assertTrue(sumPct in 99.0..101.0)
        assertEquals(1, report.crossings.crossDownEntryLong)
        assertEquals(1, report.crossings.crossUpEntryShort)
    }

    @Test
    fun buildZMovementReport_longestStreakAbsZGe1() {
        val points = listOf(
            point("2026-01-01 10:00", 0.2),
            point("2026-01-01 10:15", 1.1),
            point("2026-01-01 10:30", 1.2),
            point("2026-01-01 10:45", 1.05),
            point("2026-01-01 11:00", 0.3)
        )
        val report = buildZMovementReport(points)!!
        assertEquals(3, report.longestStreakAbsZGe1)
    }

    private fun point(date: String, z: Double): DataPoint =
        DataPoint(
            timestampMillis = 0L,
            tradeDate = date,
            tatnClose = 100.0,
            tatnpClose = 90.0,
            spreadPercent = 10.0,
            diff = 0.0,
            zScore = z
        )
}
