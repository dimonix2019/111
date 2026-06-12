package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexZPullbackEntryTest {

    @Test
    fun zPullbackLongEntryTriggered_onBounceFromMin() {
        assertFalse(zPullbackLongEntryTriggered(-1.15, zExtreme = -1.2, pullbackZ = 0.07))
        assertTrue(zPullbackLongEntryTriggered(-1.12, zExtreme = -1.2, pullbackZ = 0.07))
    }

    @Test
    fun zPullbackShortEntryTriggered_onPullbackFromMax() {
        assertFalse(zPullbackShortEntryTriggered(1.15, zExtreme = 1.2, pullbackZ = 0.07))
        assertTrue(zPullbackShortEntryTriggered(1.12, zExtreme = 1.2, pullbackZ = 0.07))
    }

    @Test
    fun buildZStrategyPortfolioMetrics_pullbackEntry_delaysLongUntilBounce() {
        val points = listOf(
            point(0, z = 0.0, spread = 10.0),
            point(1, z = -0.85, spread = 10.0),
            point(2, z = -1.20, spread = 10.1),
            point(3, z = -1.10, spread = 10.2),
            point(4, z = -0.60, spread = 10.5)
        )
        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null)

        val immediate = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "immediate",
            entryPullbackZ = 0.0,
            exitMode = ZStrategyExitMode.FixedThreshold
        )!!
        val pullback = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "pullback",
            entryPullbackZ = 0.07,
            exitMode = ZStrategyExitMode.FixedThreshold
        )!!

        assertEquals(1, immediate.closedTrades.size)
        assertEquals(1, pullback.closedTrades.size)
        assertTrue(immediate.closedTrades[0].entryDate < pullback.closedTrades[0].entryDate)
    }

    private fun point(index: Int, z: Double, spread: Double): DataPoint {
        val hour = 10 + index / 4
        val minute = (index % 4) * 15
        val date = String.format("2026-05-01 %02d:%02d", hour, minute)
        return testM15Bar(date, z, spread)
    }
}
