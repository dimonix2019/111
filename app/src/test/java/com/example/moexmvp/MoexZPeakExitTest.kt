package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexZPeakExitTest {

    @Test
    fun zPeakTrailingExitLong_exitsOnBounceFromMin() {
        val zBest = -1.2
        assertFalse(zPeakTrailingExitLong(-1.15, zBest, entryThreshold = 0.8, trailZ = 0.2))
        assertTrue(zPeakTrailingExitLong(-0.95, zBest, entryThreshold = 0.8, trailZ = 0.2))
    }

    @Test
    fun zPeakTrailingExitLong_requiresEntryDepth() {
        assertFalse(zPeakTrailingExitLong(-0.5, zBestSinceEntry = -0.5, entryThreshold = 0.8, trailZ = 0.2))
    }

    @Test
    fun zPeakTrailingExitShort_exitsOnBounceFromMax() {
        val zBest = 1.3
        assertFalse(zPeakTrailingExitShort(1.2, zBest, entryThreshold = 0.8, trailZ = 0.2))
        assertTrue(zPeakTrailingExitShort(1.05, zBest, entryThreshold = 0.8, trailZ = 0.2))
    }

    @Test
    fun parseZStrategyExitMode_fallsBackToFixedThreshold() {
        assertEquals(ZStrategyExitMode.ZPeakTrailing, parseZStrategyExitMode("ZPeakTrailing"))
        assertEquals(ZStrategyExitMode.FixedThreshold, parseZStrategyExitMode("unknown"))
        assertEquals(ZStrategyExitMode.FixedThreshold, parseZStrategyExitMode(null))
    }

    @Test
    fun buildZStrategyPortfolioMetrics_usesSelectedExitMode() {
        val points = listOf(
            point(0, z = 0.0, spread = 10.0),
            point(1, z = -0.9, spread = 10.0),
            point(2, z = -1.2, spread = 10.2),
            point(3, z = -0.95, spread = 10.4),
            point(4, z = -0.6, spread = 10.6)
        )
        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null)

        val fixed = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "test",
            exitMode = ZStrategyExitMode.FixedThreshold
        )
        val trailing = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "test",
            exitMode = ZStrategyExitMode.ZPeakTrailing,
            zPeakTrailZ = 0.2
        )

        assertEquals("2026-05-01 11:00", fixed?.closedTrades?.single()?.exitDate)
        assertEquals("2026-05-01 10:45", trailing?.closedTrades?.single()?.exitDate)
        assertTrue(trailing?.periodDescription.orEmpty().contains("трейл Z 0.20"))
    }

    private fun point(index: Int, z: Double, spread: Double): DataPoint {
        val hour = 10 + index / 4
        val minute = (index % 4) * 15
        val date = String.format("2026-05-01 %02d:%02d", hour, minute)
        return testM15Bar(date, z, spread)
    }
}
