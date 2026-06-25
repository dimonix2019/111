package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MoexMarketsZDisplayTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun point(label: String, spread: Double, z: Double = 99.0): DataPoint {
        val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
            .atZone(zone).toInstant().toEpochMilli()
        return DataPoint(ts, label, 100.0, 90.0, spread, 10.0, z)
    }

    @Test
    fun recalcM15ZForChartDisplayWindow_ignoresPersistedFlatZ() {
        val day = LocalDate.of(2026, 6, 20)
        val history = (0 until 80).map { i ->
            val dt = day.atTime(10, 0).plusMinutes(i * 15L)
            point(dt.format(portfolio15mLabelFormatter), spread = 10.0 + i * 0.05, z = -3.58)
        }
        val window = listOf(
            point("2026-06-23 10:00", spread = 14.0, z = -3.58),
            point("2026-06-23 10:15", spread = 14.5, z = -3.58),
        )
        val recalc = recalcM15ZForChartDisplayWindow(window, history)
        assertEquals(2, recalc.size)
        assertFalse(recalc.all { kotlin.math.abs(it.zScore + 3.58) < 1e-9 })
    }
}
