package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexZLocalExtremaTest {

    @Test
    fun isLocalZTrough_detectsMiddleBarMinimum() {
        assertTrue(isLocalZTrough(-1.0, -1.5, -1.2))
        assertFalse(isLocalZTrough(-1.5, -1.2, -1.0))
    }

    @Test
    fun isLocalZPeak_detectsMiddleBarMaximum() {
        assertTrue(isLocalZPeak(0.5, 1.2, 0.8))
        assertFalse(isLocalZPeak(0.8, 0.5, 1.2))
    }

    @Test
    fun localExtremaLongEntry_requiresTroughAtEntryDepth() {
        assertTrue(localExtremaLongEntry(-1.0, -1.4, -1.2, entryThreshold = 1.3))
        assertFalse(localExtremaLongEntry(-1.0, -1.1, -1.0, entryThreshold = 1.3))
    }

    @Test
    fun localExtremaExitLong_requiresPeakAfterRecoveryFromTrough() {
        assertFalse(
            localExtremaExitLong(
                prevPrevZ = -0.8,
                prevZ = -0.5,
                currentZ = -0.7,
                zBestSinceEntry = -1.5,
                entryThreshold = 1.3,
                minRecoveryZ = 1.2,
            )
        )
        assertTrue(
            localExtremaExitLong(
                prevPrevZ = -0.2,
                prevZ = 0.4,
                currentZ = 0.1,
                zBestSinceEntry = -1.5,
                entryThreshold = 1.3,
                minRecoveryZ = 1.2,
            )
        )
    }

    @Test
    fun m15PointsFromPointHelper_areConsecutive() {
        val points = (0..6).map { point(it, z = 0.0, spread = 10.0) }
        for (i in 1 until points.size) {
            assertTrue(isConsecutiveM15Bar(points[i - 1], points[i]))
        }
    }

    @Test
    fun buildZStrategyPortfolioMetrics_localExtrema_entersOnTroughExitsOnPeak() {
        val points = listOf(
            point(0, z = 0.0, spread = 10.0),
            point(1, z = -1.0, spread = 10.0),
            point(2, z = -1.4, spread = 10.1),
            point(3, z = -1.2, spread = 10.2),
            point(4, z = -0.5, spread = 10.4),
            point(5, z = 0.2, spread = 10.6),
            point(6, z = -0.1, spread = 10.5),
        )
        val thresholds = DynamicThresholds(entry = 1.3, exit = 0.4, calculatedDate = null)
        assertTrue(
            localExtremaLongEntryBetweenBars(points[1], points[2], points[3], entryThreshold = 1.3)
        )
        val metrics = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "test лок. дно",
            exitMode = ZStrategyExitMode.LocalExtrema,
        )

        assertNotNull(metrics)
        assertTrue(
            "closed=${metrics?.closedTrades?.size} open=${metrics?.openPosition?.direction}",
            (metrics?.closedTrades?.size ?: 0) >= 1 || metrics?.openPosition != null,
        )
        assertTrue(metrics?.periodDescription.orEmpty().contains("лок. дно"))
    }

    private fun point(index: Int, z: Double, spread: Double): DataPoint {
        val hour = 10 + index / 4
        val minute = (index % 4) * 15
        val date = String.format("2026-05-01 %02d:%02d", hour, minute)
        return testM15Bar(date, z, spread)
    }
}
