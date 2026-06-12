package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MoexJournalSyncTest {

    private fun point(ts: Long) = DataPoint(
        timestampMillis = ts,
        tradeDate = "2026-06-10 10:00",
        tatnClose = 500.0,
        tatnpClose = 490.0,
        spreadPercent = 1.0,
        diff = 10.0,
        zScore = 0.5,
    )

    @Test
    fun pickPortfolioExecutionReplayPoints_prefersFresherSimTail() {
        val portfolio = listOf(point(1000L), point(2000L))
        val sim = listOf(point(1000L), point(2000L), point(3000L))
        assertEquals(sim, pickPortfolioExecutionReplayPoints(sim, portfolio))
    }

    @Test
    fun pickPortfolioExecutionReplayPoints_prefersSimWhenSameTailButLonger() {
        val portfolio = listOf(point(1000L), point(2000L))
        val sim = listOf(point(500L), point(1000L), point(2000L))
        assertEquals(sim, pickPortfolioExecutionReplayPoints(sim, portfolio))
    }

    @Test
    fun pickPortfolioExecutionReplayPoints_usesPortfolioWhenNewer() {
        val portfolio = listOf(point(1000L), point(4000L))
        val sim = listOf(point(1000L), point(2000L))
        assertEquals(portfolio, pickPortfolioExecutionReplayPoints(sim, portfolio))
    }

    @Test
    fun strategySignalJournalFingerprint_changesWhenExitAdded() {
        val enter = StrategySignalEvent(1000L, StrategySignalType.EnterShort, 1.0, 1000L)
        val exit = StrategySignalEvent(2000L, StrategySignalType.ExitShort, 0.5, 2000L)
        val fp1 = strategySignalJournalFingerprint(listOf(enter))
        val fp2 = strategySignalJournalFingerprint(listOf(enter, exit))
        assertNotEquals(fp1, fp2)
    }
}
