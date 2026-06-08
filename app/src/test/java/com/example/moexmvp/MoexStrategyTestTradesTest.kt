package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test

class MoexStrategyTestTradesTest {

    @Test
    fun buildStrategyTestTradeListFromSimulation_sortsNewestExitFirst() {
        val older = PortfolioClosedTrade(
            direction = ZStrategyPosition.Long,
            entryDate = "2026-05-01 10:00",
            exitDate = "2026-05-01 12:00",
            entrySpreadPercent = 1.0,
            exitSpreadPercent = 1.1,
            pnlSpreadPoints = 0.1,
            grossPnlRubApprox = 100.0,
            pnlRubApprox = 90.0
        )
        val newer = older.copy(exitDate = "2026-05-17 15:00", entryDate = "2026-05-17 10:00")
        val items = buildStrategyTestTradeListFromSimulation(listOf(older, newer))
        assertEquals(2, items.size)
        assertEquals("2026-05-17 15:00", items[0].trade.exitDate)
        assertEquals("2026-05-01 12:00", items[1].trade.exitDate)
    }

    @Test
    fun buildStrategyTestTradeListFromSimulation_includesAllTrades() {
        val trades = (1..20).map { i ->
            PortfolioClosedTrade(
                direction = ZStrategyPosition.Long,
                entryDate = "2026-01-${"%02d".format(i)} 10:00",
                exitDate = "2026-01-${"%02d".format(i)} 11:00",
                entrySpreadPercent = 1.0,
                exitSpreadPercent = 1.1,
                pnlSpreadPoints = 0.1,
                grossPnlRubApprox = 1.0,
                pnlRubApprox = 1.0
            )
        }
        assertEquals(20, buildStrategyTestTradeListFromSimulation(trades).size)
    }

    @Test
    fun formatSimTradeDurationLabel_formatsMinutesHoursAndDays() {
        assertEquals("2 ч", formatSimTradeDurationLabel("2026-05-01 10:00", "2026-05-01 12:00"))
        assertEquals("30 мин", formatSimTradeDurationLabel("2026-05-01 10:00", "2026-05-01 10:30"))
        assertEquals("2 дн. 4 ч", formatSimTradeDurationLabel("2026-05-01 10:00", "2026-05-03 14:00"))
        assertEquals("—", formatSimTradeDurationLabel("", "2026-05-01 12:00"))
    }

    @Test
    fun isSimTradeDurationOverDays_trueOnlyWhenStrictlyOverTenDays() {
        assertEquals(false, isSimTradeDurationOverDays("2026-05-01 10:00", "2026-05-11 10:00"))
        assertEquals(true, isSimTradeDurationOverDays("2026-05-01 10:00", "2026-05-11 10:01"))
        assertEquals(true, isSimTradeDurationOverDays("2026-05-01 10:00", "2026-05-15 10:00"))
    }
}
