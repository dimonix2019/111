package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MoexStrategyTestSyncedChartsTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun buildStrategyTestChartTimeAxis_alignsFirstAndLastDay() {
        val labels = listOf("2026-01-01", "2026-06-15", "2026-12-31")
        val axis = buildStrategyTestChartTimeAxis(labels)!!
        val (tMin, tMax) = axis.timeRange
        val first = LocalDate.parse("2026-01-01").atStartOfDay(zone).toInstant().toEpochMilli()
        val lastEnd = LocalDate.parse("2026-12-31").plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        assertEquals(first, tMin)
        assertEquals(lastEnd, tMax)
        assertTrue(axis.monthTicks.isNotEmpty())
    }

    @Test
    fun strategyTestChartXMapper_mapsDayIndexMonotonically() {
        val labels = listOf("2026-01-01", "2026-06-01", "2026-12-01")
        val axis = buildStrategyTestChartTimeAxis(labels)!!
        val mapper = StrategyTestChartXMapper(axis.timeRange, leftPadding = 62f, plotWidth = 300f)
        val x0 = mapper.xForDayIndex(labels, 0)
        val xMid = mapper.xForDayIndex(labels, 1)
        val xLast = mapper.xForDayIndex(labels, labels.lastIndex)
        assertEquals(62f, x0, 0.01f)
        assertTrue(xMid > x0)
        assertTrue(xLast > xMid)
        assertTrue(xLast <= 362f)
    }

    @Test
    fun m15PointsInEquityChartRange_filtersOutsideSpan() {
        val labels = listOf("2026-06-01", "2026-06-02")
        val inside = LocalDate.parse("2026-06-01").atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val outside = LocalDate.parse("2026-05-01").atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val points = listOf(
            DataPoint(outside, "2026-05-01 10:00", 100.0, 95.0, 5.0, 5.0, 0.1),
            DataPoint(inside, "2026-06-01 10:00", 100.0, 95.0, 5.0, 5.0, 0.2),
        )
        assertEquals(1, m15PointsInEquityChartRange(points, labels).size)
    }
}
