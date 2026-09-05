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
        // Не тащить high/low всего 10м бара в 1м свечу
        assertEquals(101.2, out.last().high, 1e-9)
        assertEquals(100.5, out.last().low, 1e-9)
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

    @Test
    fun buildSpreadPercentCandlesFromLegs_usesBodyOnlyNotCrossExtremes() {
        val ts = LocalDate.of(2026, 9, 4).atTime(10, 0)
        // Широкие high/low ног: старая формула TATN.high/TATNP.low давала ~4.8%, low ~2.3%
        val tatn = listOf(
            CandleBar(ts, open = 650.0, high = 655.0, low = 645.0, close = 651.0),
        )
        val tatnp = listOf(
            CandleBar(ts, open = 628.0, high = 632.0, low = 624.0, close = 629.0),
        )
        val candles = buildSpreadPercentCandlesFromLegs(tatn, tatnp)
        assertEquals(1, candles.size)
        val c = candles.single()
        val open = (650.0 / 628.0 - 1.0) * 100.0
        val close = (651.0 / 629.0 - 1.0) * 100.0
        assertEquals(open, c.open, 1e-9)
        assertEquals(close, c.close, 1e-9)
        assertEquals(maxOf(open, close), c.high, 1e-9)
        assertEquals(minOf(open, close), c.low, 1e-9)
        // Без шипа от скрещённых экстремумов
        assertTrue(c.high < 4.0)
        assertTrue(c.low > 3.0)
    }

    @Test
    fun buildSpreadPercentCandlesFromLegs_skipsBrokenLegAndOutlierSpike() {
        val day = LocalDate.of(2026, 9, 4)
        fun bar(min: Int, tatnClose: Double, tatnpClose: Double) = CandleBar(
            timestamp = day.atTime(10, min),
            open = tatnClose,
            high = tatnClose + 0.5,
            low = tatnClose - 0.5,
            close = tatnClose,
        ) to CandleBar(
            timestamp = day.atTime(10, min),
            open = tatnpClose,
            high = tatnpClose + 0.5,
            low = tatnpClose - 0.5,
            close = tatnpClose,
        )
        val normal = (0..8).map { i ->
            // ~3.5% спред
            bar(i, 650.0 + i * 0.1, 628.0)
        }
        // Минута 9: нулевая нога TATNP (дыра) — не рисовать
        val zeroLeg = CandleBar(day.atTime(10, 9), 650.0, 651.0, 649.0, 650.5) to
            CandleBar(day.atTime(10, 9), 0.0, 0.0, 0.0, 0.0)
        // Минута 10: синтетический битый тик → спред ~100%
        val spike = bar(10, 1250.0, 625.0)
        val rest = (11..14).map { i -> bar(i, 650.0, 628.0) }

        val pairs = normal + zeroLeg + spike + rest
        val tatn = pairs.map { it.first }
        val tatnp = pairs.map { it.second }
        val candles = buildSpreadPercentCandlesFromLegs(tatn, tatnp)

        assertTrue(candles.none { it.label.endsWith("10:09") })
        assertTrue(candles.none { it.label.endsWith("10:10") })
        assertTrue(candles.isNotEmpty())
        val yMin = candles.minOf { it.low }
        val yMax = candles.maxOf { it.high }
        assertTrue("ось не должна раздуваться битым баром: $yMin..$yMax", yMax < 5.0)
        assertTrue("ось не должна раздуваться битым баром: $yMin..$yMax", yMin > 2.0)
    }

    @Test
    fun sanitizeSpreadPercentCandles_dropsIsolatedJump() {
        val base = (0..6).map { i ->
            CandlePoint("t$i", open = 3.5, high = 3.5, low = 3.5, close = 3.5)
        }.toMutableList()
        base[3] = CandlePoint("t3", open = 12.0, high = 12.0, low = 12.0, close = 12.0)
        val out = sanitizeSpreadPercentCandles(base)
        assertEquals(6, out.size)
        assertTrue(out.none { it.label == "t3" })
        assertEquals(3.5, out.maxOf { it.high }, 1e-9)
    }
}
