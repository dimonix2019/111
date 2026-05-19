package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test

class MoexEquityCurveTest {

    @Test
    fun drawdownRubSeriesFromEquity_tracksPeakToTrough() {
        val equity = listOf(0.0, 100.0, 150.0, 120.0, 180.0)
        val dd = drawdownRubSeriesFromEquity(equity)
        assertEquals(listOf(0.0, 0.0, 0.0, 30.0, 0.0), dd)
    }

    @Test
    fun equityCurveDailyForChart_usesLastBarPerDay() {
        val labels = listOf("2026-05-01 10:00", "2026-05-01 15:00", "2026-05-02 10:00")
        val equity = listOf(100.0, 110.0, 105.0)
        val (days, eq, dd) = equityCurveDailyForChart(labels, equity)
        assertEquals(listOf("2026-05-01", "2026-05-02"), days)
        assertEquals(listOf(110.0, 105.0), eq)
        assertEquals(2, dd.size)
        assertEquals(0.0, dd[0], 1e-9)
        assertEquals(5.0, dd[1], 1e-9)
    }
}
