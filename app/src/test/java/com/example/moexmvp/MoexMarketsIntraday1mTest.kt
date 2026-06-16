package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class MoexMarketsIntraday1mTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun isMoexMainSessionLikelyOpen_weekdayMidday() {
        val wed = ZonedDateTime.of(
            LocalDate.of(2026, 6, 10),
            LocalTime.of(12, 0),
            zone,
        )
        assertTrue(isMoexMainSessionLikelyOpen(wed))
    }

    @Test
    fun isMoexMainSessionLikelyOpen_saturdayFalse() {
        val sat = ZonedDateTime.of(
            LocalDate.of(2026, 6, 13),
            LocalTime.of(12, 0),
            zone,
        )
        assertFalse(isMoexMainSessionLikelyOpen(sat))
    }

    @Test
    fun intraday1mChartInitialWindow_showsTail() {
        val (w, start) = intraday1mChartInitialWindow(barCount = 400, visibleBars = 120)
        assertEquals(0.3f, w, 0.001f)
        assertEquals(0.7f, start, 0.001f)
    }

    @Test
    fun candleBarsToIntradayCandlePoints_sortedByTime() {
        val bars = listOf(
            CandleBar(
                timestamp = LocalDate.of(2026, 6, 15).atTime(10, 1),
                open = 1.0, high = 2.0, low = 0.5, close = 1.5,
            ),
            CandleBar(
                timestamp = LocalDate.of(2026, 6, 15).atTime(10, 0),
                open = 1.0, high = 2.0, low = 0.5, close = 1.2,
            ),
        )
        val points = candleBarsToIntradayCandlePoints(bars)
        assertEquals("10:00", points[0].label)
        assertEquals("10:01", points[1].label)
    }
}
