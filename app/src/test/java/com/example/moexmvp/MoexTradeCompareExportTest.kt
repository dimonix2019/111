package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexTradeCompareExportTest {
    private fun sampleTrade(direction: ZStrategyPosition = ZStrategyPosition.Long) = PortfolioClosedTrade(
        direction = direction,
        entryDate = "15.06.25 : 10:00",
        exitDate = "15.06.25 : 12:30",
        entrySpreadPercent = 6.12,
        exitSpreadPercent = 5.98,
        pnlSpreadPoints = 0.14,
        grossPnlRubApprox = 120.0,
        commissionRubApprox = 8.5,
        overnightRubApprox = 0.0,
        pnlRubApprox = 111.5,
    )

    private fun sampleMetrics(trades: List<PortfolioClosedTrade>) = PortfolioMetrics(
        periodDescription = "255д 15м",
        notionalRub = 10_000.0,
        leverage = 1.0,
        commissionPercentPerSide = 0.04,
        totalCommissionRub = trades.sumOf { it.commissionRubApprox },
        totalOvernightRub = 0.0,
        closedTrades = trades,
        openPosition = null,
        cumulativeRealizedSpread = trades.sumOf { it.pnlSpreadPoints },
        cumulativeRealizedRubApprox = trades.sumOf { it.pnlRubApprox },
        unrealizedRubApprox = 0.0,
        totalPnlSpread = trades.sumOf { it.pnlSpreadPoints },
        totalPnlRubApprox = trades.sumOf { it.pnlRubApprox },
        totalReturnPercent = 1.1,
        maxDrawdownRubApprox = 50.0,
        maxDrawdownPercent = 0.5,
        winCount = trades.count { it.pnlRubApprox > 0 },
        lossCount = trades.count { it.pnlRubApprox <= 0 },
        winRate = 100.0,
        profitFactor = null,
        avgWinRub = 111.5,
        avgLossRub = 0.0,
        largestWinRub = 111.5,
        largestLossRub = 0.0,
    )

    @Test
    fun exportStrategyTestCompareCsv_includesProdParityMeta() {
        val trades = listOf(sampleTrade())
        val items = trades.map { StrategyTestTradeItem(trade = it) }
        val csv = exportStrategyTestCompareCsv(
            metrics = sampleMetrics(trades),
            tradeItems = items,
            config = StrategyTestExportConfig(
                accountSizeRub = 10_000.0,
                capitalUsagePercent = 75.0,
                leverageForLots = 7.0,
                commissionPercentPerSide = 0.04,
                entryThreshold = 2.0,
                exitThreshold = 0.5,
                slippageSpreadPts = 0.02,
                compoundReturns = false,
                usePortfolioThresholds = true,
                useLiveZSignals = true,
                thresholdSource = "PortfolioProd",
            ),
        )
        assertTrue(csv.lines().any { it.contains("capital_usage_pct=75") })
        assertTrue(csv.lines().any { it.contains("live_z=true") })
        assertTrue(csv.lines().any { it.contains("threshold_source=PortfolioProd") })
    }

    @Test
    fun exportStrategyTestCompareCsv_hasUnifiedHeaderAndTradeRows() {
        val trades = listOf(
            sampleTrade(),
            sampleTrade(direction = ZStrategyPosition.Short),
        )
        val items = trades.map { StrategyTestTradeItem(trade = it) }
        val csv = exportStrategyTestCompareCsv(
            metrics = sampleMetrics(trades),
            tradeItems = items,
            config = StrategyTestExportConfig(
                accountSizeRub = 10_000.0,
                capitalUsagePercent = 80.0,
                leverageForLots = 7.0,
                commissionPercentPerSide = 0.04,
                entryThreshold = 2.0,
                exitThreshold = 0.5,
                slippageSpreadPts = 0.02,
                compoundReturns = false,
            ),
        )
        assertEquals(2, tradeCompareRowCount(csv))
        val headerLine = csv.lines().first { !it.startsWith("#") && it.isNotBlank() }
        assertTrue(headerLine.startsWith("source,trade_index,direction"))
        assertEquals(tradeCompareHeaderColumnCount(), headerLine.split(',').size)
        assertTrue(csv.lines().any { it.startsWith("sim,1,LONG,") })
        assertTrue(csv.lines().any { it.startsWith("sim,2,SHORT,") })
    }

    @Test
    fun tradeCompareRowCount_ignoresCommentMetaLines() {
        val csv = """
            # sim export=strategy_test
            source,trade_index,direction
            sim,1,LONG
        """.trimIndent()
        assertEquals(1, tradeCompareRowCount(csv))
    }
}
