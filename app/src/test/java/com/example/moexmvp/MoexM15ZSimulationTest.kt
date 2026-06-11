package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexM15ZSimulationTest {

    @Test
    fun portfolioThresholdsMatchStrategyTest_withinEpsilon() {
        assertTrue(portfolioThresholdsMatchStrategyTest(0.65, 0.55, 0.65, 0.55))
        assertTrue(!portfolioThresholdsMatchStrategyTest(0.65, 0.55, 0.70, 0.55))
    }

    @Test
    fun buildPortfolioTabSimulationMetrics_matchesStrategyTestWhenSameThresholds() {
        val points = listOf(
            dp("2026-05-30 10:00", z = 0.0),
            dp("2026-05-30 10:15", z = -0.8),
            dp("2026-05-30 10:30", z = -1.4),
            dp("2026-05-30 10:45", z = -0.5),
        )
        val th = DynamicThresholds(entry = 0.65, exit = 0.55, calculatedDate = null)
        val portfolio = buildPortfolioTabSimulationMetrics(
            points = points,
            entryThreshold = 0.65,
            exitThreshold = 0.55,
            dynamicCalculatedDate = null,
            leverage = 1.0,
            commissionPercentPerSide = 0.0,
        )
        val test = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = th,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 1.0,
            commissionPercentPerSide = 0.0,
            periodDescription = "test",
            compoundReturns = false,
            exitMode = ZStrategyExitMode.FixedThreshold,
        )
        assertEquals(test?.closedTrades?.size, portfolio?.closedTrades?.size)
    }

    private fun dp(label: String, z: Double) = DataPoint(
        timestampMillis = 0L,
        tradeDate = label,
        tatnClose = 100.0,
        tatnpClose = 100.0,
        spreadPercent = 0.0,
        diff = 0.0,
        zScore = z,
    )
}
