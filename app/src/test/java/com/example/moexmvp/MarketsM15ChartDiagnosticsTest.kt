package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MarketsM15ChartDiagnosticsTest {

    @Test
    fun snapshotM15Series_detectsIntradayGap() {
        val day = LocalDate.now(ZoneId.of("Europe/Moscow"))
        val d = day.toString()
        val points = listOf(
            testM15Bar("$d 06:45", 0.7),
            testM15Bar("$d 21:00", 0.2),
        )
        val snap = snapshotM15Series(points)
        assertTrue(snap.hasIntradayGap)
        assertNotNull(snap.firstIntradayGap)
        assertTrue(snap.toLogFields().contains("intraday_gap=YES"))
    }

    @Test
    fun firstIntradayTradingGapLabel_sameDayOnly() {
        val day = LocalDate.of(2026, 6, 23)
        val contiguous = testM15BarSeries(day, 10, 0, List(4) { 0.1 })
        assertEquals(null, firstIntradayTradingGapLabel(contiguous))
        val gapped = listOf(
            testM15Bar("2026-06-23 10:00", 0.1),
            testM15Bar("2026-06-23 15:00", 0.2),
        )
        assertNotNull(firstIntradayTradingGapLabel(gapped))
    }
}
