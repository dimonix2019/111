package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(1, summary.closedSecondDay.tradeCount)
        assertEquals(50.0, summary.closedSecondDay.totalPnlRub, 0.01)
        assertEquals("< 1 дн.", summary.detailBuckets.first().title)
    }

    @Test
    fun isSimTradeClosedOnSecondCalendarDay_nextMskDayOnly() {
        assertTrue(isSimTradeClosedOnSecondCalendarDay("2026-05-01 18:00", "2026-05-02 10:00"))
        assertFalse(isSimTradeClosedOnSecondCalendarDay("2026-05-01 10:00", "2026-05-01 18:00"))
        assertFalse(isSimTradeClosedOnSecondCalendarDay("2026-05-01 10:00", "2026-05-03 10:00"))
    }

    @Test
    fun buildStrategyTestMonthlyReturnSummary_buildsMonthlyBars() {
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
        assertEquals(2, summary.monthlyBars.size)
        assertEquals("01.26", summary.monthlyBars[0].label)
        assertEquals("02.26", summary.monthlyBars[1].label)
        assertEquals(1.5, summary.monthlyBars[0].returnPercent, 0.01)
        assertEquals(-0.3, summary.monthlyBars[1].returnPercent, 0.01)
        assertEquals(2, summary.monthlyBars[0].tradeCount)
        assertEquals(1500.0, summary.monthlyBars[0].pnlRub, 0.01)
    }

    @Test
    fun buildNiceRubAxis_roundsToReadableSteps() {
        val (_, axisMax, ticks) = buildNiceRubAxis(0.0, 73_000.0, tickCount = 5)
        assertTrue(axisMax >= 73_000.0)
        assertTrue(ticks.size >= 2)
        assertEquals(0.0, ticks.first(), 0.01)
    }

    @Test
    fun formatBarPercentLabel_showsIntegerPercentAboveBar() {
        assertEquals("40%", formatBarPercentLabel(40.0))
        assertEquals("-3%", formatBarPercentLabel(-3.2))
        assertEquals("1%", formatBarPercentLabel(1.48))
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

    @Test
    fun filterStrategyTestTradeItemsExcludingRedZone_dropsHighRiskOnly() {
        fun trade(exit: String) = StrategyTestTradeItem(
            trade = PortfolioClosedTrade(
                direction = ZStrategyPosition.Long,
                entryDate = "2026-03-01 10:00",
                exitDate = exit,
                entrySpreadPercent = 1.0,
                exitSpreadPercent = 1.1,
                pnlSpreadPoints = 0.1,
                grossPnlRubApprox = 100.0,
                pnlRubApprox = 100.0,
            ),
        )
        val items = listOf(trade("2026-03-05 12:00"), trade("2026-03-10 12:00"))
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
        val filtered = filterStrategyTestTradeItemsExcludingRedZone(items, assessments)
        assertEquals(1, filtered.size)
        assertEquals("2026-03-10 12:00", filtered.single().trade.exitDate)
        val summary = buildStrategyTestMonthlyReturnSummary(items, 100_000.0, assessments)!!
        val bars = summary.monthlyBarsForDisplay(excludeRedZone = true, items, assessments)
        assertEquals(1, bars.single().tradeCount)
    }

    @Test
    fun buildStrategyTestMetricsForClosedTrades_recomputesPnlAndDrawdownWithoutRedZone() {
        fun trade(exit: String, pnl: Double) = PortfolioClosedTrade(
            direction = ZStrategyPosition.Long,
            entryDate = "2026-03-01 10:00",
            exitDate = exit,
            entrySpreadPercent = 1.0,
            exitSpreadPercent = 1.1,
            pnlSpreadPoints = 0.1,
            grossPnlRubApprox = pnl,
            commissionRubApprox = 0.0,
            overnightRubApprox = 0.0,
            pnlRubApprox = pnl,
        )
        val base = PortfolioMetrics(
            periodDescription = "Тест",
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            totalCommissionRub = 0.0,
            totalOvernightRub = 0.0,
            closedTrades = listOf(
                trade("2026-03-05 12:00", 1000.0),
                trade("2026-03-10 12:00", -2000.0),
            ),
            openPosition = null,
            cumulativeRealizedSpread = 0.0,
            cumulativeRealizedRubApprox = -1000.0,
            unrealizedRubApprox = 0.0,
            totalPnlSpread = 0.0,
            totalPnlRubApprox = -1000.0,
            totalReturnPercent = -1.0,
            maxDrawdownRubApprox = 3000.0,
            maxDrawdownPercent = 200.0,
            winCount = 1,
            lossCount = 1,
            winRate = 50.0,
            profitFactor = 0.5,
            avgWinRub = 1000.0,
            avgLossRub = -2000.0,
            largestWinRub = 1000.0,
            largestLossRub = -2000.0,
        )
        val filtered = buildStrategyTestMetricsForClosedTrades(
            base = base,
            closedTrades = listOf(trade("2026-03-05 12:00", 1000.0)),
        )
        assertEquals(1000.0, filtered.totalPnlRubApprox, 0.01)
        assertEquals(1.0, filtered.totalReturnPercent, 0.01)
        assertEquals(0.0, filtered.maxDrawdownRubApprox, 0.01)
        assertEquals(1, filtered.winCount)
        assertEquals(0, filtered.lossCount)
        assertTrue(filtered.periodDescription.contains("без красной зоны"))
    }
}
