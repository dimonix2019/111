package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexPortfolioClosedTradesSynthTest {

    @Test
    fun buildClosedRows_whenLedgerEmpty_pairsOpensWithJournalExits() {
        val open = SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = StrategySignalType.EnterLong,
            zScore = 1.5,
            barTimestampMillis = 1000L,
            executedAtMillis = 1000L,
            entrySpreadPercent = 3.0,
            source = PortfolioExecSource.MANUAL,
            directionLabel = "long",
            entryTimeMsk = "2026-05-18 10:00",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "1+1",
            confirmLabel = "ручное",
            correlationTag = "t",
            notificationIdsText = "—",
            legs = emptyList()
        )
        val journal = listOf(
            StrategySignalEvent(1000L, StrategySignalType.EnterLong, 1.5, 1000L),
            StrategySignalEvent(2000L, StrategySignalType.ExitLong, 0.5, 2000L)
        )
        val points = listOf(
            point(1000L, spread = 3.0, z = 1.5),
            point(2000L, spread = 2.5, z = 0.5)
        )
        val (closed, stillOpen) = buildClosedRowsFromSandboxOpensAndJournalExits(
            openExecutions = listOf(open),
            allJournalEvents = journal,
            points = points,
            ledger = emptyList(),
            pushLog = emptyList(),
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            portfolioLedgerIncludeAuto = false
        )
        assertEquals(1, closed.size)
        assertTrue(stillOpen.isEmpty())
    }

    private fun point(ts: Long, spread: Double, z: Double) = DataPoint(
        timestampMillis = ts,
        tradeDate = "2026-05-18 10:00",
        tatnClose = 100.0,
        tatnpClose = 99.0,
        spreadPercent = spread,
        diff = 1.0,
        zScore = z
    )
}
