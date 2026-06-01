package com.example.moexmvp

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexStrategyTestLoadTest {

    @Test
    fun sufficientForStrategyTestSimulation_requiresSpanAndBars() {
        val zone = ZoneId.of("Europe/Moscow")
        val start = java.time.LocalDate.of(2025, 6, 1)
        val points = buildList {
            var day = 0
            while (day < 200) {
                val date = start.plusDays(day.toLong())
                val ts = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
                add(
                    DataPoint(
                        timestampMillis = ts,
                        tradeDate = date.toString(),
                        tatnClose = 100.0,
                        tatnpClose = 90.0,
                        spreadPercent = 10.0,
                        diff = 10.0,
                        zScore = 0.0,
                    )
                )
                day++
            }
        }
        assertTrue(points.sufficientForStrategyTestSimulation())
        assertFalse(points.take(40).sufficientForStrategyTestSimulation())
    }

    @Test
    fun strategyTestM15PointsForChart_prefersOneMonthTail() {
        val zone = ZoneId.of("Europe/Moscow")
        val start = java.time.LocalDate.of(2025, 1, 1)
        val full = buildList {
            var day = 0
            while (day < 90) {
                val date = start.plusDays(day.toLong())
                val ts = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
                add(
                    DataPoint(
                        timestampMillis = ts,
                        tradeDate = date.toString(),
                        tatnClose = 100.0,
                        tatnpClose = 90.0,
                        spreadPercent = 10.0,
                        diff = 10.0,
                        zScore = 0.0,
                    )
                )
                day++
            }
        }
        val chart = strategyTestM15PointsForChart(full)
        val month = filterM15PointsForMarketsPeriod(full, Period.OneMonth)
        assertTrue(chart.size <= full.size)
        assertEquals(month.size, chart.size)
    }
}
