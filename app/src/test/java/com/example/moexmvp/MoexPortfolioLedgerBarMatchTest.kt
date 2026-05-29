package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexPortfolioLedgerBarMatchTest {

    @Test
    fun ledgerEntryMatchesSignalBar_exactAndWithinTolerance() {
        val pairs = setOf(Pair(StrategySignalType.EnterLong, 1_000_000L))
        assertTrue(
            ledgerEntryMatchesSignalBar(pairs, StrategySignalType.EnterLong, 1_000_000L)
        )
        assertTrue(
            ledgerEntryMatchesSignalBar(
                pairs,
                StrategySignalType.EnterLong,
                1_000_000L + 15 * 60 * 1000L
            )
        )
        assertFalse(
            ledgerEntryMatchesSignalBar(
                pairs,
                StrategySignalType.EnterLong,
                1_000_000L + 31 * 60 * 1000L
            )
        )
        assertFalse(
            ledgerEntryMatchesSignalBar(pairs, StrategySignalType.EnterShort, 1_000_000L)
        )
    }

    @Test
    fun snapTimestampToNearestPortfolioBar_picksClosest() {
        val points = listOf(
            dataPoint(1000L, 1.0),
            dataPoint(2000L, 2.0),
            dataPoint(3000L, 3.0)
        )
        assertEquals(2000L, snapTimestampToNearestPortfolioBar(points, 2100L))
        assertEquals(1000L, snapTimestampToNearestPortfolioBar(points, 50L))
    }

    private fun dataPoint(ts: Long, z: Double) = DataPoint(
        timestampMillis = ts,
        tradeDate = "t",
        tatnClose = 100.0,
        tatnpClose = 99.0,
        spreadPercent = 1.0,
        diff = 1.0,
        zScore = z
    )
}
