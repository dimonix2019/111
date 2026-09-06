package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MoexMarketsChartZDisplayTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun point(label: String, spread: Double, z: Double = 0.0): DataPoint {
        val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
            .atZone(zone).toInstant().toEpochMilli()
        return DataPoint(ts, label, 100.0, 90.0, spread, 10.0, z)
    }

    @Test
    fun filterM15PointsForMarketsOneDay_showsTodayFrom0645() {
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val points = listOf(
            point("${yesterday} 10:00", spread = 5.0, z = 0.5),
            point("${yesterday} 23:30", spread = 5.1, z = 0.6),
            point("${today} 06:45", spread = 5.2, z = 0.7),
            point("${today} 07:30", spread = 5.3, z = 0.8),
        )
        val oneDay = filterM15PointsForMarketsOneDay(points, zone)
        assertEquals(2, oneDay.size)
        assertEquals("${today} 06:45", oneDay.first().tradeDate)
        assertEquals("${today} 07:30", oneDay.last().tradeDate)
    }

    @Test
    fun recalcM15ZForChartDisplayWindow_recomputesFromSpreadNotPersistedFlat() {
        val day = LocalDate.of(2026, 6, 23)
        val history = (0 until 80).map { i ->
            val dt = LocalDateTime.of(2026, 6, 20, 10, 0).plusMinutes(i * 15L)
            point(dt.format(portfolio15mLabelFormatter), spread = 10.0 + i * 0.05, z = 99.0)
        }
        val window = listOf(
            point("${day} 10:00", spread = 14.0, z = 99.0),
            point("${day} 10:15", spread = 14.5, z = 99.0),
            point("${day} 10:30", spread = 13.5, z = 99.0),
        )
        val recalc = recalcM15ZForChartDisplayWindow(window, history)
        assertEquals(3, recalc.size)
        assertFalse(recalc.all { kotlin.math.abs(it.zScore - 99.0) < 1e-9 })
        assertTrue(recalc.map { it.zScore }.distinct().size >= 2)
    }

    @Test
    fun mergeTodayM15ChartOverlays_prefersMoexMorningBars() {
        val today = LocalDate.now(zone)
        val from1m = listOf(point("${today} 07:30", spread = 5.1))
        val fromMoex = listOf(
            point("${today} 06:45", spread = 5.0),
            point("${today} 07:00", spread = 5.05),
            point("${today} 07:30", spread = 5.08),
        )
        val merged = mergeTodayM15ChartOverlays(from1m, fromMoex)
        assertEquals(3, merged.size)
        assertEquals("${today} 06:45", merged.first().tradeDate)
    }

    @Test
    fun m15TodayOverlayNeedsMoexFetch_whenMorningIncomplete() {
        val today = LocalDate.now(zone)
        val history = listOf(point("${today.minusDays(1)} 23:30", spread = 5.0))
        val probe = listOf(point("${today} 07:30", spread = 5.1))
        assertTrue(m15TodayOverlayNeedsMoexFetch(history, probe, zone))
    }
}
