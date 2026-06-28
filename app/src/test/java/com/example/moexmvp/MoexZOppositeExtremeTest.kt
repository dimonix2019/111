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
        val points = testM15BarSeries(
            day = java.time.LocalDate.of(2026, 5, 1),
            hour = 10,
            minute = 0,
            zAt = listOf(0.0, -0.5, -1.1, -1.3, -1.0, 0.5, 1.5),
            spread = 10.0,
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
            periodDescription = "test противоп. экстремум",
            exitMode = ZStrategyExitMode.OppositeExtreme,
        )

        assertEquals("2026-05-01 11:00", fixed?.closedTrades?.single()?.exitDate)
        assertEquals("2026-05-01 11:30", opposite?.closedTrades?.single()?.exitDate)
        assertTrue(opposite?.periodDescription.orEmpty().contains("противоп. экстремум"))
    }

    private fun point(index: Int, z: Double, spread: Double): DataPoint {
        val hour = 10 + index / 4
        val minute = (index % 4) * 15
        val date = String.format("2026-05-01 %02d:%02d", hour, minute)
        return testM15Bar(date, z, spread)
    }
}
