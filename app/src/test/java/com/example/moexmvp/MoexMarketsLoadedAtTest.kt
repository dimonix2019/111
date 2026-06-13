package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MoexMarketsLoadedAtTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun pointAt(day: LocalDate, hour: Int, minute: Int): DataPoint {
        val ldt = day.atTime(hour, minute)
        val ts = ldt.atZone(zone).toInstant().toEpochMilli()
        return DataPoint(
            timestampMillis = ts,
            tradeDate = ldt.format(portfolio15mLabelFormatter),
            tatnClose = 590.0,
            tatnpClose = 555.0,
            spreadPercent = 6.3,
            diff = 35.0,
            zScore = 0.5,
        )
    }

    @Test
    fun resolveMarketsLoadedAtLabel_prefersFresherM15Bar() {
        val today = LocalDate.of(2026, 6, 13)
        val m15 = listOf(pointAt(today, 10, 15))
        val resolved = resolveMarketsLoadedAtLabel(m15, "2026-06-12 19:00:00")
        assertEquals("2026-06-13 10:15:00", resolved)
    }

    @Test
    fun resolveMarketsLoadedAtLabel_keepsDailyWhenNewer() {
        val yesterday = LocalDate.of(2026, 6, 12)
        val m15 = listOf(pointAt(yesterday, 19, 0))
        val resolved = resolveMarketsLoadedAtLabel(m15, "2026-06-13 09:00:00")
        assertEquals("2026-06-13 09:00:00", resolved)
    }

    @Test
    fun portfolio15mSeriesNeedsMoexRefresh_trueWhenLastBarYesterday() {
        val yesterday = LocalDate.now(zone).minusDays(1)
        val points = listOf(pointAt(yesterday, 19, 0))
        assertTrue(portfolio15mSeriesNeedsMoexRefresh(points))
    }

    @Test
    fun portfolio15mSeriesNeedsMoexRefresh_falseWhenRecentTodayBar() {
        val now = Instant.now().minusSeconds(5 * 60)
        val zdt = now.atZone(zone)
        val points = listOf(
            DataPoint(
                timestampMillis = now.toEpochMilli(),
                tradeDate = zdt.format(portfolio15mLabelFormatter),
                tatnClose = 590.0,
                tatnpClose = 555.0,
                spreadPercent = 6.3,
                diff = 35.0,
                zScore = 0.5,
            ),
        )
        assertFalse(portfolio15mSeriesNeedsMoexRefresh(points))
    }
}
