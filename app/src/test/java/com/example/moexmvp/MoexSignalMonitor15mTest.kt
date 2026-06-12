package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

class MoexSignalMonitor15mTest {

    @Test
    fun zOnLastBar_dependsOnLookbackWindow_same15mBars() {
        fun point(ts: Long, spread: Double) = DataPoint(
            timestampMillis = ts,
            tradeDate = "2026-01-01",
            tatnClose = 100.0,
            tatnpClose = 100.0,
            spreadPercent = spread,
            diff = 0.0,
            zScore = 0.0
        )
        val shortWindow = (1L..20L).map { point(it, spread = if (it % 2L == 0L) 1.0 else -1.0) }
        val longWindow = shortWindow + (21L..220L).map {
            point(it, spread = 0.05 * (it % 7 - 3))
        }
        val zShort = applyZScoresRolling(shortWindow, minBarsInWindow = 2).last().zScore
        val zLong = applyZScoresRolling(longWindow, minBarsInWindow = 2).last().zScore
        assertNotEquals(zShort, zLong, 1e-9)
    }

    @Test
    fun zStrategySignalOnLast15mBar_matchesPortfolioEdgeRules() {
        val thresholds = DynamicThresholds(entry = 1.3, exit = 1.2, calculatedDate = null)
        val points = listOf(
            testM15Bar("2026-05-18 10:00", z = -1.0),
            testM15Bar("2026-05-18 10:15", z = -1.5),
        )
        val signal = zStrategySignalOnLast15mBar(points, ZStrategyPosition.Flat, thresholds)
        assertEquals(ZStrategySignal.EnterLong, signal)
    }

    @Test
    fun collectZStrategy15mSignalEdges_replaysBatchCrossingNotVisibleOnLastBarPair() {
        val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)
        val points = testM15BarSeries(
            day = LocalDate.of(2026, 6, 9),
            hour = 10,
            minute = 0,
            zAt = listOf(0.31, 0.55, 0.72, 0.82),
        )
        val lastPairOnly = zStrategySignalOnLast15mBar(points, ZStrategyPosition.Flat, thresholds)
        assertEquals(ZStrategySignal.None, lastPairOnly)

        val (edges, finalPosition) = collectZStrategy15mSignalEdgesSinceProcessedBar(
            points = points,
            lastProcessedBarTimestampMillis = points[0].timestampMillis,
            initialPosition = ZStrategyPosition.Flat,
            thresholds = thresholds,
        )
        assertEquals(1, edges.size)
        assertEquals(ZStrategySignal.EnterShort, edges.single().signal)
        assertEquals(points[2].timestampMillis, edges.single().bar.timestampMillis)
        assertEquals(ZStrategyPosition.Short, finalPosition)
    }

    @Test
    fun collectZStrategy15mSignalEdges_firstRunOnlyChecksLastBarPair() {
        val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)
        val points = testM15BarSeries(
            day = LocalDate.of(2026, 6, 9),
            hour = 10,
            minute = 0,
            zAt = listOf(0.2, 0.65, 0.75),
        )
        val (edges, finalPosition) = collectZStrategy15mSignalEdgesSinceProcessedBar(
            points = points,
            lastProcessedBarTimestampMillis = null,
            initialPosition = ZStrategyPosition.Flat,
            thresholds = thresholds,
        )
        assertEquals(1, edges.size)
        assertEquals(ZStrategySignal.EnterShort, edges.single().signal)
        assertEquals(points.last().timestampMillis, edges.single().bar.timestampMillis)
        assertEquals(ZStrategyPosition.Short, finalPosition)
    }
}
