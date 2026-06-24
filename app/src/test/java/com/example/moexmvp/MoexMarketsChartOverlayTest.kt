package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MoexMarketsChartOverlayTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun alignIntraday1mCloseSeries_sortsByTimeNotLexicographic() {
        val tatn = listOf(
            CandlePoint("10:00", 650.0, 650.0, 650.0, 650.0),
            CandlePoint("09:05", 640.0, 640.0, 640.0, 640.0),
        )
        val tatnp = listOf(
            CandlePoint("10:00", 600.0, 600.0, 600.0, 600.0),
            CandlePoint("09:05", 590.0, 590.0, 590.0, 590.0),
        )
        val aligned = alignIntraday1mCloseSeries(tatn, tatnp)!!
        assertEquals(listOf("09:05", "10:00"), aligned.labels)
    }

    @Test
    fun mergeM15SessionWithSqliteForChart_fillsSessionGapFromSqlite() {
        val day = LocalDate.of(2026, 6, 23)
        val history = testM15BarSeries(day.minusDays(2), 10, 0, List(80) { 0.1 })
        val session = history + listOf(
            testM15Bar("2026-06-23 06:45", 0.7),
            testM15Bar("2026-06-23 21:00", 0.2),
        )
        val sqlite = history + testM15BarSeries(day, 7, 0, List(56) { 0.3 })
        val merged = mergeM15SessionWithSqliteForChart(session, sqlite)
        val todayStart = day.atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()
        val today = merged.filter { it.timestampMillis >= todayStart }
        assertFalse(m15SeriesHasIntradayTradingGap(today))
        assertTrue(today.size >= 50)
    }

    @Test
    fun applyTodayM15Overlay_fillsIntradayGap() {
        val day = LocalDate.of(2026, 6, 23)
        val history = testM15BarSeries(day.minusDays(3), 10, 0, List(80) { 0.1 })
        val canonical = history + listOf(
            testM15Bar("2026-06-23 06:45", 0.7),
            testM15Bar("2026-06-23 21:00", 0.2),
        )
        val overlay = testM15BarSeries(day, 7, 0, List(56) { 0.3 })
        val merged = applyTodayM15OverlayForChart(canonical, overlay)
        val todayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val today = merged.filter { it.timestampMillis >= todayStart }
        assertTrue(today.size >= 50)
        assertFalse(m15SeriesHasIntradayTradingGap(today))
    }

    @Test
    fun buildTodayM15OverlayFromMoexEntities_recomputesZ() {
        val day = LocalDate.now(zone).minusDays(2)
        val history = testM15BarSeries(day, 10, 0, List(80) { 0.1 })
        val today = LocalDate.now(zone)
        val entities = testM15BarSeries(today, 7, 0, List(8) { 0.2 }).map { pt ->
            PortfolioM15SpreadEntity(
                tsMillis = pt.timestampMillis,
                tatnClose = pt.tatnClose,
                tatnpClose = pt.tatnpClose,
                spreadPercent = pt.spreadPercent,
                diff = pt.diff,
            )
        }
        val overlay = buildTodayM15OverlayFromMoexEntities(history, entities, zone)
        assertNotNull(overlay)
        assertTrue(overlay!!.isNotEmpty())
    }
}
