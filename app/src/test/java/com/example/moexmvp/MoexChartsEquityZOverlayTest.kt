package com.example.moexmvp

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MoexChartsEquityZOverlayTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun equityDailyChartTimeRangeMillis_spansFirstAndLastDay() {
        val labels = listOf("2026-01-01", "2026-01-02", "2026-01-03")
        val (tMin, tMax) = equityDailyChartTimeRangeMillis(labels)!!
        val first = LocalDate.parse("2026-01-01").atStartOfDay(zone).toInstant().toEpochMilli()
        val lastEnd = LocalDate.parse("2026-01-03").plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        assertEquals(first, tMin)
        assertEquals(lastEnd, tMax)
    }

    @Test
    fun m15PointsInEquityChartRange_filtersOutsideEquitySpan() {
        val labels = listOf("2026-06-01", "2026-06-02")
        val inside = LocalDate.parse("2026-06-01").atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val outside = LocalDate.parse("2026-05-01").atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val points = listOf(
            DataPoint(outside, "2026-05-01 10:00", 100.0, 95.0, 5.0, 5.0, 0.1),
            DataPoint(inside, "2026-06-01 10:00", 100.0, 95.0, 5.0, 5.0, 0.2),
        )
        val filtered = m15PointsInEquityChartRange(points, labels)
        assertEquals(1, filtered.size)
        assertEquals(inside, filtered.single().timestampMillis)
    }

    @Test
    fun buildEquityChartZAxisRange_includesThresholdLines() {
        val refs = buildZScoreReferenceLines(
            DynamicThresholds(entry = 1.3, exit = 0.5, calculatedDate = null),
            desktopStyle = true,
        )
        val (min, max) = buildEquityChartZAxisRange(listOf(-0.2, 0.8), refs)
        assertTrue(min < -1.3)
        assertTrue(max > 1.3)
        assertEquals(Color(0xFFFBBF24), refs.first().color)
    }
}
