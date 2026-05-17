package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MoexStrategyTestTradesTest {

    @Test
    fun pointsForStrategyTestWindow_includesBarBeforeWindowStart() {
        val zone = ZoneId.of("Europe/Moscow")
        val day = LocalDate.of(2026, 5, 13)
        val windowStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val points = (0 until 6).map { i ->
            val ms = windowStart - 15 * 60 * 1000L + i * 15 * 60 * 1000L
            DataPoint(
                timestampMillis = ms,
                tradeDate = "2026-05-13 ${10 + i}:00",
                tatnClose = 500.0,
                tatnpClose = 400.0,
                spreadPercent = 1.0 + i * 0.1,
                diff = 0.0,
                zScore = 0.0
            )
        }
        val slice = pointsForStrategyTestWindow(points, windowStart)
        assertTrue(slice.first().timestampMillis < windowStart)
        assertEquals(points.last().timestampMillis, slice.last().timestampMillis)
    }

    @Test
    fun buildStrategyTestTradeList_mergesJournalWithoutDuplicates() {
        val zone = ZoneId.of("Europe/Moscow")
        val day = LocalDate.of(2026, 5, 15)
        val windowStart = day.minusDays(PORTFOLIO_TRADES_WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val trade = PortfolioClosedTrade(
            direction = ZStrategyPosition.Long,
            entryDate = "2026-05-14 10:00",
            exitDate = "2026-05-15 11:00",
            entrySpreadPercent = 1.0,
            exitSpreadPercent = 1.1,
            pnlSpreadPoints = 0.1,
            grossPnlRubApprox = 100.0,
            pnlRubApprox = 90.0
        )
        val items = buildStrategyTestTradeList(
            simulationTrades = listOf(trade),
            journalTrades = listOf(trade),
            windowStartMillis = windowStart
        )
        assertEquals(1, items.size)
        assertEquals("симуляция 3 дн.", items.single().sourceLabel)
    }
}
