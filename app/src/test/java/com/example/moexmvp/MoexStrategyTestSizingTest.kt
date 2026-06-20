package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexStrategyTestSizingTest {
    @Test
    fun strategyTestPairNotional_10kAccount80Pct_matchesProdScale() {
        val bar = DataPoint(
            timestampMillis = 1L,
            tradeDate = "2026-06-19 21:30",
            tatnClose = 531.6,
            tatnpClose = 497.3,
            spreadPercent = 6.98,
            diff = 0.0,
            zScore = 0.75,
        )
        val notional = strategyTestPairNotionalRub(
            bar = bar,
            accountSizeRub = 10_000.0,
            capitalUsagePercent = 80.0,
            leverageForLots = 7.0,
        )
        assertTrue(notional in 5_000.0..80_000.0)
        assertTrue(notional < DEFAULT_PORTFOLIO_NOTIONAL_RUB)
    }

    @Test
    fun strategyTestPairNotional_userScreenshotPrices_67lotsRange() {
        val bar = DataPoint(
            timestampMillis = 1L,
            tradeDate = "2026-06-18 07:00",
            tatnClose = 537.9,
            tatnpClose = 506.5,
            spreadPercent = 6.20,
            diff = 0.0,
            zScore = -1.21,
        )
        val notional67 = spreadPairNotionalRub(537.9, 506.5, 1, 67)
        assertEquals(69_974.8, notional67, 50.0)
        val simNotional = strategyTestPairNotionalRub(
            bar = bar,
            accountSizeRub = 10_000.0,
            capitalUsagePercent = 80.0,
            leverageForLots = 7.0,
        )
        assertTrue(simNotional <= notional67 * 1.05)
        assertTrue(simNotional < DEFAULT_PORTFOLIO_NOTIONAL_RUB)
    }
}
