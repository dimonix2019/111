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
    fun isSimTradeDurationOverDay_trueFromTwentyFourHoursAndAbove() {
        assertEquals(false, isSimTradeDurationOverDay("2026-05-01 10:00", "2026-05-01 12:00"))
        assertEquals(true, isSimTradeDurationOverDay("2026-05-01 10:00", "2026-05-02 10:00"))
        assertEquals(true, isSimTradeDurationOverDay("2026-05-01 10:00", "2026-05-02 10:01"))
        assertEquals(true, isSimTradeDurationOverDay("2026-05-01 10:00", "2026-05-03 14:00"))
    }

    @Test
    fun isSimTradeDurationUnderDay_trueOnlyWhenStrictlyUnderTwentyFourHours() {
        assertEquals(true, isSimTradeDurationUnderDay("2026-05-01 10:00", "2026-05-01 12:00"))
        assertEquals(true, isSimTradeDurationUnderDay("2026-05-01 10:00", "2026-05-01 23:59"))
        assertEquals(false, isSimTradeDurationUnderDay("2026-05-01 10:00", "2026-05-02 10:00"))
        assertEquals(false, isSimTradeDurationUnderDay("2026-05-01 10:00", "2026-05-03 14:00"))
    }

    @Test
    fun strategyTestTradesTableColumn_defaultVisibleIncludesAllColumns() {
        assertEquals(StrategyTestTradesTableColumn.entries.size, StrategyTestTradesTableColumn.defaultVisible.size)
    }

    @Test
    fun decodeStrategyTestTradesTableVisibleColumns_roundTripsSubset() {
        val subset = setOf(
            StrategyTestTradesTableColumn.Index,
            StrategyTestTradesTableColumn.Duration,
            StrategyTestTradesTableColumn.Net,
        )
        val encoded = encodeStrategyTestTradesTableVisibleColumns(subset)
        assertEquals(subset, decodeStrategyTestTradesTableVisibleColumns(encoded))
    }

    @Test
    fun decodeStrategyTestTradesTableVisibleColumns_fallsBackWhenEmptyOrUnknown() {
        assertEquals(
            StrategyTestTradesTableColumn.defaultVisible,
            decodeStrategyTestTradesTableVisibleColumns(null),
        )
        assertEquals(
            StrategyTestTradesTableColumn.defaultVisible,
            decodeStrategyTestTradesTableVisibleColumns("NotAColumn,AlsoBad"),
        )
    }

    @Test
    fun simTradeDurationTone_mapsShortAndLongBuckets() {
        assertEquals(SimTradeDurationTone.Short, simTradeDurationTone("2026-05-01 10:00", "2026-05-01 12:00"))
        assertEquals(SimTradeDurationTone.Long, simTradeDurationTone("2026-05-01 10:00", "2026-05-02 10:00"))
    }

    @Test
    fun buildStrategyTestDurationSummary_groupsTradesByDuration() {
        fun trade(entry: String, exit: String, pnl: Double) = PortfolioClosedTrade(
            direction = ZStrategyPosition.Long,
            entryDate = entry,
            exitDate = exit,
            entrySpreadPercent = 1.0,
            exitSpreadPercent = 1.1,
            pnlSpreadPoints = 0.1,
            grossPnlRubApprox = pnl,
            pnlRubApprox = pnl,
        )
        val trades = listOf(
            trade("2026-05-01 10:00", "2026-05-01 12:00", 100.0),
            trade("2026-05-01 10:00", "2026-05-02 09:00", 50.0),
            trade("2026-05-01 10:00", "2026-05-04 10:00", -200.0),
        )
        val summary = buildStrategyTestDurationSummary(trades)!!
        assertEquals(2, summary.short.tradeCount)
        assertEquals(1, summary.long.tradeCount)
        assertEquals(100.0, summary.short.winPercent, 0.01)
        assertEquals(0.0, summary.long.winPercent, 0.01)
        assertEquals(150.0, summary.short.totalPnlRub, 0.01)
        assertEquals(-200.0, summary.long.totalPnlRub, 0.01)
        assertEquals("< 1 дн.", summary.detailBuckets.first().title)
    }

    @Test
    fun buildStrategyTestMonthlyReturnSummary_averagesMonthlyReturns() {
        fun trade(exit: String, pnl: Double) = PortfolioClosedTrade(
            direction = ZStrategyPosition.Long,
            entryDate = exit.replace(Regex("(\\d{2}:\\d{2})$"), "10:00"),
            exitDate = exit,
            entrySpreadPercent = 1.0,
            exitSpreadPercent = 1.1,
            pnlSpreadPoints = 0.1,
            grossPnlRubApprox = pnl,
            pnlRubApprox = pnl,
        )
        val items = buildStrategyTestTradeListFromSimulation(
            listOf(
                trade("2026-01-15 12:00", 1000.0),
                trade("2026-01-20 12:00", 500.0),
                trade("2026-02-10 12:00", -300.0),
            ),
        )
        val summary = buildStrategyTestMonthlyReturnSummary(items, notionalRub = 100_000.0, emptyList())!!
        assertEquals(3, summary.allTrades.tradeCount)
        assertEquals(2, summary.allTrades.monthCount)
        assertEquals(0.6, summary.allTrades.avgMonthlyReturnPercent, 0.01) // Jan 1.5%, Feb -0.3% → avg 0.6%
        assertEquals(1.2, summary.allTrades.totalReturnPercent, 0.01)
        assertEquals(0, summary.redZoneTradeCount)
    }

    @Test
    fun buildStrategyTestMonthlyReturnSummary_excludesRedZoneTrades() {
        fun trade(exit: String, pnl: Double) = PortfolioClosedTrade(
            direction = ZStrategyPosition.Long,
            entryDate = "2026-03-01 10:00",
            exitDate = exit,
            entrySpreadPercent = 1.0,
            exitSpreadPercent = 1.1,
            pnlSpreadPoints = 0.1,
            grossPnlRubApprox = pnl,
            pnlRubApprox = pnl,
        )
        val items = buildStrategyTestTradeListFromSimulation(
            listOf(
                trade("2026-03-05 12:00", 1000.0),
                trade("2026-03-10 12:00", -2000.0),
            ),
        )
        val assessments = listOf(
            StrategyTestTradeRiskAssessment(
                flags = listOf(StrategyTestTradeRiskFlag.LongHold),
                level = StrategyTestTradeRiskLevel.High,
                score = 4,
                entryZ = 0.5,
            ),
            StrategyTestTradeRiskAssessment(
                flags = emptyList(),
                level = StrategyTestTradeRiskLevel.None,
                score = 0,
                entryZ = null,
            ),
        )
        val summary = buildStrategyTestMonthlyReturnSummary(items, 100_000.0, assessments)!!
        assertEquals(1, summary.redZoneTradeCount)
        assertEquals(-1.0, summary.allTrades.totalReturnPercent, 0.01)
        assertEquals(1.0, summary.withoutRedZone.totalReturnPercent, 0.01)
        assertEquals(1.0, summary.withoutRedZone.avgMonthlyReturnPercent, 0.01)
    }
}
