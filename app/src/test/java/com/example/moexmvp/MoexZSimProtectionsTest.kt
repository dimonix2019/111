package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexZSimProtectionsTest {

    @Test
    fun zSimEntryAndExitSpread_applySlippageByDirection() {
        assertEquals(10.05, zSimEntrySpread(10.0, ZStrategyPosition.Long, 0.05), 1e-9)
        assertEquals(9.95, zSimEntrySpread(10.0, ZStrategyPosition.Short, 0.05), 1e-9)
        assertEquals(9.95, zSimExitSpread(10.0, ZStrategyPosition.Long, 0.05), 1e-9)
        assertEquals(10.05, zSimExitSpread(10.0, ZStrategyPosition.Short, 0.05), 1e-9)
    }

    @Test
    fun buildZStrategyPortfolioMetrics_slippage_reducesPnl() {
        val points = listOf(
            point(0, z = 0.0, spread = 10.0, date = "2026-05-01 10:00"),
            point(1, z = -0.85, spread = 10.0, date = "2026-05-01 10:15"),
            point(2, z = -0.60, spread = 10.5, date = "2026-05-01 10:30")
        )
        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null)
        val base = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.0,
            periodDescription = "base"
        )!!
        val slip = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.0,
            periodDescription = "slip",
            simOptions = ZStrategySimOptions(slippageSpreadPts = 0.1)
        )!!
        assertEquals(1, base.closedTrades.size)
        assertEquals(1, slip.closedTrades.size)
        assertTrue(slip.closedTrades[0].pnlRubApprox < base.closedTrades[0].pnlRubApprox)
    }

    @Test
    fun buildZStrategyPortfolioMetrics_stopLossSpread_closesEarly() {
        val points = listOf(
            point(0, z = 0.0, spread = 10.0, date = "2026-05-01 10:00"),
            point(1, z = -0.85, spread = 10.0, date = "2026-05-01 10:15"),
            point(2, z = -0.95, spread = 9.5, date = "2026-05-01 10:30"),
            point(3, z = -0.75, spread = 10.5, date = "2026-05-01 10:45")
        )
        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null)
        val hold = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.0,
            periodDescription = "hold"
        )!!
        val stop = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.0,
            periodDescription = "stop",
            simOptions = ZStrategySimOptions(maxLossSpreadPts = 0.4)
        )!!
        assertTrue(hold.openPosition != null)
        assertEquals(1, stop.closedTrades.size)
        assertEquals("2026-05-01 10:30", stop.closedTrades[0].exitDate)
        assertTrue(stop.closedTrades[0].pnlRubApprox < 0)
    }

    @Test
    fun buildZStrategyPortfolioMetrics_minSpreadPct_blocksEntry() {
        val points = listOf(
            point(0, z = 0.0, spread = 9.5, date = "2026-05-01 10:00"),
            point(1, z = -0.85, spread = 9.5, date = "2026-05-01 10:15"),
            point(2, z = -0.60, spread = 10.0, date = "2026-05-01 10:30")
        )
        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null)
        val normal = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.0,
            periodDescription = "normal"
        )!!
        val filtered = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.0,
            periodDescription = "filtered",
            simOptions = ZStrategySimOptions(minSpreadPct = 10.0)
        )!!
        assertEquals(1, normal.closedTrades.size)
        assertTrue(filtered.closedTrades.isEmpty())
    }

    @Test
    fun buildZStrategyPortfolioMetrics_drawdownHalt_blocksSecondEntry() {
        val points = listOf(
            point(0, z = 0.0, spread = 10.0, date = "2026-05-01 10:00"),
            point(1, z = -0.85, spread = 10.0, date = "2026-05-01 10:15"),
            point(2, z = -0.60, spread = 9.0, date = "2026-05-01 10:30"),
            point(3, z = 0.0, spread = 9.0, date = "2026-05-01 10:45"),
            point(4, z = -0.85, spread = 9.0, date = "2026-05-01 11:00"),
            point(5, z = -0.60, spread = 9.5, date = "2026-05-01 11:15")
        )
        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null)
        val normal = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.0,
            periodDescription = "normal"
        )!!
        val halted = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.0,
            periodDescription = "halt",
            simOptions = ZStrategySimOptions(maxDrawdownHaltRub = 500.0)
        )!!
        assertEquals(2, normal.closedTrades.size)
        assertEquals(1, halted.closedTrades.size)
    }

    private fun point(index: Int, z: Double, spread: Double, date: String): DataPoint =
        DataPoint(
            timestampMillis = index.toLong(),
            tradeDate = date,
            tatnClose = 100.0,
            tatnpClose = 90.0,
            spreadPercent = spread,
            diff = 0.0,
            zScore = z
        )
}
