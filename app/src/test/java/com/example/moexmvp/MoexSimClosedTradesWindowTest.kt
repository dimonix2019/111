package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MoexSimClosedTradesWindowTest {

    @Test
    fun filterSimClosedTradesInWindow_usesExitDate() {
        val zone = ZoneId.of("Europe/Moscow")
        val today = LocalDate.of(2026, 5, 16)
        val windowStart = today.minusDays(2)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val old = PortfolioClosedTrade(
            direction = ZStrategyPosition.Long,
            entryDate = "2026-05-01 10:00",
            exitDate = "2026-05-01 11:00",
            entrySpreadPercent = 1.0,
            exitSpreadPercent = 1.1,
            pnlSpreadPoints = 0.1,
            grossPnlRubApprox = 100.0,
            pnlRubApprox = 90.0
        )
        val recent = old.copy(exitDate = "2026-05-15 12:00")
        val filtered = filterSimClosedTradesInWindow(listOf(old, recent), lookbackDays = 3L, windowStartMillis = windowStart)
        assertEquals(1, filtered.size)
        assertEquals(recent.exitDate, filtered.single().exitDate)
    }
}
