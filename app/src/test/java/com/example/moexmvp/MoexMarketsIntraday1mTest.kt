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
    fun isMoexQuotesSessionLikelyOpen_earlyMorning() {
        val morning = ZonedDateTime.of(
            LocalDate.of(2026, 6, 17),
            LocalTime.of(7, 50),
            zone,
        )
        assertTrue(isMoexQuotesSessionLikelyOpen(morning))
    }

    @Test
    fun intraday1mLastBarAgeMinutes_positive() {
        val now = ZonedDateTime.of(LocalDate.of(2026, 6, 17), LocalTime.of(7, 50), zone).toInstant().toEpochMilli()
        val bar = ZonedDateTime.of(LocalDate.of(2026, 6, 17), LocalTime.of(7, 34), zone).toInstant().toEpochMilli()
        assertEquals(16L, intraday1mLastBarAgeMinutes(bar, now))
    }

    @Test
    fun intraday1mChartInitialWindow_showsTailWithRightGap() {
        val (w, start) = intraday1mChartInitialWindow(barCount = 400, visibleBars = 120)
        assertEquals(0.3f, w, 0.001f)
        assertEquals(0.754f, start, 0.001f)
    }

    @Test
    fun alignIntraday1mCloseSeries_mergesByTimeLabel() {
        val tatn = listOf(
            CandlePoint("10:00", 100.0, 101.0, 99.0, 100.5),
            CandlePoint("10:01", 100.5, 101.5, 100.0, 101.0),
        )
        val tatnp = listOf(
            CandlePoint("10:00", 50.0, 51.0, 49.0, 50.2),
            CandlePoint("10:02", 50.2, 51.0, 50.0, 50.8),
        )
        val aligned = alignIntraday1mCloseSeries(tatn, tatnp)
        requireNotNull(aligned)
        assertEquals(listOf("10:00", "10:01", "10:02"), aligned.labels)
        assertEquals(listOf(100.5, 101.0, 101.0), aligned.tatnCloses)
        assertEquals(listOf(50.2, 50.2, 50.8), aligned.tatnpCloses)
    }

    @Test
    fun appendFormingIntraday1mFrom10m_addsCurrentMinuteFrom10m() {
        val now = ZonedDateTime.of(LocalDate.of(2026, 6, 17), LocalTime.of(10, 7, 30), zone)
        val minute = now.withSecond(0).withNano(0).toLocalDateTime()
        val bars1m = listOf(
            CandleBar(minute.minusMinutes(1), 100.0, 101.0, 99.0, 100.5),
        )
        val bars10m = listOf(
            CandleBar(minute, 100.5, 102.0, 100.0, 101.2),
        )
        val out = appendFormingIntraday1mFrom10m(bars1m, bars10m, now)
        assertEquals(2, out.size)
        assertEquals(101.2, out.last().close, 1e-9)
        assertEquals(minute, out.last().timestamp)
    }

    @Test
    fun appendFormingIntraday1mFrom10m_updatesFormingMinuteClose() {
        val now = ZonedDateTime.of(LocalDate.of(2026, 6, 17), LocalTime.of(10, 7, 30), zone)
        val minute = now.withSecond(0).withNano(0).toLocalDateTime()
        val bars1m = listOf(
            CandleBar(minute, 100.0, 100.5, 99.5, 100.2),
        )
        val bars10m = listOf(
            CandleBar(minute.minusMinutes(7), 99.0, 100.0, 98.0, 99.5),
            CandleBar(minute, 100.0, 101.5, 99.8, 101.0),
        )
        val out = appendFormingIntraday1mFrom10m(bars1m, bars10m, now)
        assertEquals(1, out.size)
        assertEquals(101.0, out.last().close, 1e-9)
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
