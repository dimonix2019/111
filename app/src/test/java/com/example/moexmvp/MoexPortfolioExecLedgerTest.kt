package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexPortfolioExecLedgerTest {

    @Test
    fun ledgerEntryPairs_fallsBackToOtherSourceWhenPrimaryEmpty() {
        val ledger = listOf(
            PortfolioExecutionLedgerEntry(1000L, StrategySignalType.EnterLong, PortfolioExecSource.AUTO)
        )
        val manualMode = ledgerEntryPairsForPortfolioReplay(ledger, portfolioLedgerIncludeAuto = false)
        assertEquals(setOf(Pair(StrategySignalType.EnterLong, 1000L)), manualMode)
    }

    @Test
    fun journalEventsForExecutionPortfolioTab_pairsEnterAndExit() {
        val ledger = listOf(
            PortfolioExecutionLedgerEntry(1000L, StrategySignalType.EnterLong, PortfolioExecSource.AUTO)
        )
        val events = listOf(
            StrategySignalEvent(1000L, StrategySignalType.EnterLong, 1.5, 1000L),
            StrategySignalEvent(2000L, StrategySignalType.ExitLong, 0.5, 2000L)
        )
        val filtered = journalEventsForExecutionPortfolioTab(events, ledger, portfolioLedgerIncludeAuto = false)
        assertEquals(2, filtered.size)
        assertEquals(StrategySignalType.EnterLong, filtered[0].signalType)
        assertEquals(StrategySignalType.ExitLong, filtered[1].signalType)
    }

    @Test
    fun journalEventsForExecutionPortfolioTab_dropsOrphanExitWithoutEnter() {
        val ledger = listOf(
            PortfolioExecutionLedgerEntry(1000L, StrategySignalType.EnterLong, PortfolioExecSource.AUTO)
        )
        val events = listOf(
            StrategySignalEvent(2000L, StrategySignalType.ExitLong, 0.5, 2000L)
        )
        val filtered = journalEventsForExecutionPortfolioTab(events, ledger, portfolioLedgerIncludeAuto = false)
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun journalEventsForExecutionPortfolioTab_journalOnlyWhenLedgerEmpty() {
        val events = listOf(
            StrategySignalEvent(1000L, StrategySignalType.EnterShort, -2.0, 1000L),
            StrategySignalEvent(3000L, StrategySignalType.ExitShort, -0.5, 3000L)
        )
        val filtered = journalEventsForExecutionPortfolioTab(events, emptyList(), portfolioLedgerIncludeAuto = false)
        assertEquals(2, filtered.size)
    }
}
