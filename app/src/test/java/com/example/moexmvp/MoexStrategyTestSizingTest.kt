package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun parseStrategyTestAccountRubInput_acceptsDigitsAndSeparators() {
        assertEquals(10_205.0, parseStrategyTestAccountRubInput("10 205 ₽")!!, 0.01)
        assertEquals(50_000.0, parseStrategyTestAccountRubInput("50000")!!, 0.01)
        assertEquals(null, parseStrategyTestAccountRubInput(""))
        assertEquals(1_000.0, parseStrategyTestAccountRubInput("500")!!, 0.01)
    }

    @Test
    fun formatStrategyTestAccountRubInput_roundsToInteger() {
        assertEquals("10205", formatStrategyTestAccountRubInput(10_205.4))
    }

    @Test
    fun previewStrategyTestEntrySizing_100kHitsProdLotCap() {
        val bar = DataPoint(
            timestampMillis = 1L,
            tradeDate = "2026-06-18 07:00",
            tatnClose = 537.9,
            tatnpClose = 506.5,
            spreadPercent = 6.20,
            diff = 0.0,
            zScore = -1.21,
        )
        val small = previewStrategyTestEntrySizing(bar, 10_000.0, 80.0, 7.0, applyProdLotCap = true)!!
        val large = previewStrategyTestEntrySizing(bar, 100_000.0, 80.0, 7.0, applyProdLotCap = true)!!
        assertTrue(small.quantityLots in 50..75)
        assertEquals(SPREAD_LOT_MAX_LOTS, large.quantityLots)
        assertTrue(large.cappedByProdMaxLots)
        assertTrue(large.executionNotionalRub < small.executionNotionalRub * 2.0)
    }

    @Test
    fun previewStrategyTestEntrySizing_uncappedScalesWithAccount() {
        val bar = DataPoint(
            timestampMillis = 1L,
            tradeDate = "2026-06-18 07:00",
            tatnClose = 537.9,
            tatnpClose = 506.5,
            spreadPercent = 6.20,
            diff = 0.0,
            zScore = -1.21,
        )
        val small = previewStrategyTestEntrySizing(bar, 10_000.0, 80.0, 7.0, applyProdLotCap = false)!!
        val large = previewStrategyTestEntrySizing(bar, 100_000.0, 80.0, 7.0, applyProdLotCap = false)!!
        assertTrue(large.executionNotionalRub > small.executionNotionalRub * 5.0)
        assertTrue(large.quantityLots > small.quantityLots * 5)
    }

    @Test
    fun buildZStrategyPortfolioMetrics_scalesPnlWithAccountSize() {
        val points = (0 until 80).map { i ->
            val z = when {
                i == 20 -> -2.5
                i == 25 -> -0.4
                i == 40 -> 2.5
                i == 45 -> 0.4
                else -> 0.0
            }
            DataPoint(
                timestampMillis = i * 900_000L,
                tradeDate = "2026-01-01 ${"%02d".format(10 + i / 4)}:${"%02d".format((i % 4) * 15)}",
                tatnClose = 530.0,
                tatnpClose = 500.0,
                spreadPercent = 6.0,
                diff = 0.0,
                zScore = z,
            )
        }
        val thresholds = DynamicThresholds(2.0, 0.5, null)
        val prodLike = ZStrategyProdLikeSizing(
            accountSizeRub = 10_000.0,
            capitalUsagePercent = 80.0,
            leverageForLots = 7.0,
        )
        fun metricsFor(accountRub: Double): PortfolioMetrics? =
            buildZStrategyPortfolioMetrics(
                points = points,
                thresholds = thresholds,
                notionalRub = accountRub,
                leverage = 1.0,
                commissionPercentPerSide = 0.04,
                periodDescription = "test",
                compoundReturns = false,
                exitMode = ZStrategyExitMode.FixedThreshold,
                prodLikeSizing = prodLike.copy(accountSizeRub = accountRub),
            )
        val small = metricsFor(10_000.0)
        val large = metricsFor(20_000.0)
        assertNotNull(small)
        assertNotNull(large)
        if (small!!.closedTrades.isNotEmpty() && large!!.closedTrades.isNotEmpty()) {
            assertTrue(large.totalPnlRubApprox > small.totalPnlRubApprox * 1.5)
        }
    }
}
