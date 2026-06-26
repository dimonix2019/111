package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexZOppositeExtremeTest {

    @Test
    fun oppositeExtremeExitLong_exitsOnCrossUpThroughPositiveThreshold() {
        assertFalse(oppositeExtremeExitLong(1.5, 1.7, oppositeThreshold = 1.8))
        assertTrue(oppositeExtremeExitLong(1.5, 1.9, oppositeThreshold = 1.8))
        assertFalse(oppositeExtremeExitLong(-0.5, 0.5, oppositeThreshold = 1.8))
    }

    @Test
    fun oppositeExtremeExitShort_exitsOnCrossDownThroughNegativeThreshold() {
        assertFalse(oppositeExtremeExitShort(-1.5, -1.7, oppositeThreshold = 1.8))
        assertTrue(oppositeExtremeExitShort(-1.5, -1.9, oppositeThreshold = 1.8))
    }

    @Test
    fun buildZStrategyPortfolioMetrics_oppositeExtreme_holdsUntilOppositeSide() {
        val points = listOf(
            point(0, z = 0.0, spread = 10.0),
            point(1, z = -0.9, spread = 10.0),
            point(2, z = -1.2, spread = 10.2),
            point(3, z = -0.4, spread = 10.4),
            point(4, z = 0.6, spread = 10.6),
            point(5, z = 1.3, spread = 10.8),
        )
        val thresholds = DynamicThresholds(entry = 1.0, exit = 1.2, calculatedDate = null)

        val fixed = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "test",
            exitMode = ZStrategyExitMode.FixedThreshold,
        )
        val opposite = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "test",
            exitMode = ZStrategyExitMode.OppositeExtreme,
        )

        assertEquals("2026-05-01 10:45", fixed?.closedTrades?.single()?.exitDate)
        assertEquals("2026-05-01 11:15", opposite?.closedTrades?.single()?.exitDate)
        assertTrue(opposite?.periodDescription.orEmpty().contains("противоп. экстремум"))
    }

    private fun point(index: Int, z: Double, spread: Double): DataPoint {
        val hour = 10 + index / 4
        val minute = (index % 4) * 15
        val date = String.format("2026-05-01 %02d:%02d", hour, minute)
        return testM15Bar(date, z, spread)
    }
}
