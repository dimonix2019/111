package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DynamicSpreadRangeTest {

    private val zone: ZoneId = moexZoneId

    private fun sessionDay(date: LocalDate, low: Double, high: Double, minutes: Int = 40): List<MinuteSpreadSample> {
        val n = minutes.coerceAtLeast(2)
        return (0 until n).map { i ->
            val spread = low + (high - low) * i / (n - 1).toDouble()
            MinuteSpreadSample(date.atTime(LocalTime.of(7, 0)).plusMinutes(i.toLong()), spread)
        }
    }

    private fun weekday(start: LocalDate, index: Int): LocalDate {
        var date = start
        var left = index
        while (left > 0) {
            date = date.plusDays(1)
            if (date.dayOfWeek.value <= 5) left--
        }
        return date
    }

    @Test
    fun boxStartsOnlyAfterFourFinishedWeekdaysAndIgnoresTheDayItself() {
        val monday = LocalDate.of(2026, 9, 7)
        val samples = mutableListOf<MinuteSpreadSample>()
        repeat(4) { samples += sessionDay(weekday(monday, it), low = 3.0, high = 5.0) }
        val friday = weekday(monday, 4)
        samples += sessionDay(friday, low = 9.0, high = 12.0)
        val days = spreadSessionDays(samples)
        assertEquals(5, days.size)
        val expected = dynamicRangeBox(days.take(4))
        assertTrue(expected != null)
        val segments = buildDynamicSpreadRangeSegments(samples, zone)
        assertTrue(segments.isNotEmpty())
        val first = segments.first()
        assertEquals(friday.atTime(7, 0).atZone(zone).toInstant().toEpochMilli(), first.fromMillis)
        assertEquals(expected!!.floor, first.floor, 1e-9)
        assertEquals(expected.ceiling, first.ceiling, 1e-9)
        val beforeFriday = buildDynamicSpreadRangeSegments(samples.filter { it.time.toLocalDate().isBefore(friday) }, zone)
        assertTrue(beforeFriday.isEmpty())
    }

    @Test
    fun laterDaysDoNotChangeTheBoxDrawnThatMorning() {
        val monday = LocalDate.of(2026, 9, 7)
        val early = (0 until 4).flatMap { sessionDay(weekday(monday, it), 3.0, 5.0) }
        val friday = weekday(monday, 4)
        val withFuture = early + sessionDay(friday, 1.0, 2.0) + sessionDay(weekday(monday, 5), 8.0, 9.0)
        val morning = buildDynamicSpreadRangeSegments(withFuture, zone).first { it.fromMillis == friday.atTime(7, 0).atZone(zone).toInstant().toEpochMilli() }
        val fromHistory = dynamicRangeBox(spreadSessionDays(early))!!
        assertEquals(fromHistory.floor, morning.floor, 1e-9)
        assertEquals(fromHistory.ceiling, morning.ceiling, 1e-9)
    }

    @Test
    fun threeEscapedDaysDropTheBoxTheNextMorning() {
        val monday = LocalDate.of(2026, 9, 7)
        val samples = mutableListOf<MinuteSpreadSample>()
        repeat(6) { samples += sessionDay(weekday(monday, it), 3.0, 5.0) }
        repeat(3) { samples += sessionDay(weekday(monday, 6 + it), 8.0, 9.0) }
        val after = weekday(monday, 9)
        samples += sessionDay(after, 3.0, 5.0)
        val segments = buildDynamicSpreadRangeSegments(samples, zone)
        val afterMs = after.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(segments.none { it.fromMillis == afterMs })
        assertTrue(segments.any { it.fromMillis < afterMs })
    }

    @Test
    fun shortDaysWeekendsAndFlatRangeProduceNoBox() {
        val monday = LocalDate.of(2026, 9, 7)
        val shortDays = (0 until 4).flatMap { sessionDay(weekday(monday, it), 3.0, 5.0, minutes = 20) }
        assertTrue(buildDynamicSpreadRangeSegments(shortDays, zone).isEmpty())
        val saturday = MinuteSpreadSample(LocalDate.of(2026, 9, 12).atTime(10, 0), 4.0)
        assertTrue(spreadSessionDays(listOf(saturday)).isEmpty())
        val flat = (0 until 6).flatMap { sessionDay(weekday(monday, it), 4.0, 4.05) }
        assertNull(dynamicRangeBox(spreadSessionDays(flat)))
        assertTrue(buildDynamicSpreadRangeSegments(flat, zone).isEmpty())
    }

    @Test
    fun fixedSpreadLevelsStayOnTheChart() {
        val lines = MARKETS_PHONE_SPREAD_LEVEL_LINES
        assertEquals(listOf(6.1, 5.8, 4.0, 3.2), lines.map { it.value })
        assertEquals(3.2, DEFAULT_SPREAD_ENTER_NARROW, 0.0)
        assertEquals(4.0, DEFAULT_SPREAD_EXIT_NARROW, 0.0)
        assertEquals(5.8, DEFAULT_SPREAD_EXIT_WIDE, 0.0)
        assertEquals(6.1, DEFAULT_SPREAD_ENTER_WIDE, 0.0)
    }
}
