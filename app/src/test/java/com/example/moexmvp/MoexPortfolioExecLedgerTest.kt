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

    @Test
    fun journalEventsForExecutionPortfolioTab_journalPairsDespiteUnrelatedLedger() {
        val ledger = listOf(
            PortfolioExecutionLedgerEntry(9_999L, StrategySignalType.EnterLong, PortfolioExecSource.AUTO)
        )
        val events = listOf(
            StrategySignalEvent(1_000L, StrategySignalType.EnterShort, 1.14, 1_000L),
            StrategySignalEvent(3_000L, StrategySignalType.ExitShort, 0.28, 3_000L)
        )
        val filtered = journalEventsForExecutionPortfolioTab(events, ledger, portfolioLedgerIncludeAuto = true)
        assertEquals(2, filtered.size)
        assertEquals(StrategySignalType.EnterShort, filtered[0].signalType)
        assertEquals(StrategySignalType.ExitShort, filtered[1].signalType)
    }

    @Test
    fun filterSandboxExecutions_showsTestTradesInAutoMode() {
        val testExec = SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = StrategySignalType.EnterLong,
            zScore = 1.5,
            barTimestampMillis = 1000L,
            executedAtMillis = 2000L,
            entrySpreadPercent = 3.0,
            source = PortfolioExecSource.MANUAL,
            directionLabel = "long",
            entryTimeMsk = "2026-05-18 10:00",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка 1 лот",
            shortLegSideRu = "продажа 1 лот",
            volumeText = "1+1 лот",
            confirmLabel = "ручное · тест",
            correlationTag = "tag",
            notificationIdsText = "—",
            legs = emptyList()
        )
        val manualExec = testExec.copy(tradeId = "D-002", confirmLabel = "ручное")
        val autoExec = testExec.copy(tradeId = "D-003", source = PortfolioExecSource.AUTO, confirmLabel = "авто")

        val autoMode = filterSandboxExecutionsByPortfolioMode(
            listOf(testExec, manualExec, autoExec),
            portfolioLedgerIncludeAuto = true
        )
        assertEquals(listOf("D-001", "D-003"), autoMode.map { it.tradeId })

        val manualMode = filterSandboxExecutionsByPortfolioMode(
            listOf(testExec, manualExec, autoExec),
            portfolioLedgerIncludeAuto = false
        )
        assertEquals(listOf("D-001", "D-002"), manualMode.map { it.tradeId })
    }

    @Test
    fun filterConfirmedRows_showsTestTradesInAutoMode() {
        fun row(confirm: String) = PortfolioConfirmedTradeTableRow(
            tradeId = confirm,
            directionLabel = "long",
            entryTimeMsk = "2026-05-18 10:00",
            exitTimeMsk = "2026-05-18 11:00",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "1+1",
            confirmLabel = confirm,
            entryZ = 1.0,
            exitZ = 0.5,
            notificationIdsText = "—",
            legLongPnlSplitRubApprox = 0.0,
            legShortPnlSplitRubApprox = 0.0,
            grossPnlRubApprox = 0.0,
            netPnlRubApprox = 0.0,
            commissionRubApprox = 0.0,
            overnightRubApprox = 0.0,
            entrySignalId = "—",
            entrySignalBarTimeMsk = "—",
            entrySignalReceivedMsk = "—"
        )

        val rows = listOf(
            row("ручное · тест"),
            row("ручное"),
            row("авто"),
            row("сигнал"),
        )
        val autoMode = filterConfirmedTableRowsByPortfolioMode(rows, portfolioLedgerIncludeAuto = true)
        assertEquals(setOf("ручное · тест", "авто", "сигнал"), autoMode.map { it.confirmLabel }.toSet())

        val manualMode = filterConfirmedTableRowsByPortfolioMode(rows, portfolioLedgerIncludeAuto = false)
        assertEquals(setOf("ручное · тест", "ручное", "сигнал"), manualMode.map { it.confirmLabel }.toSet())
    }

    @Test
    fun journalEventsForChartTrades_matchesPortfolioJournalPairs() {
        val ledger = listOf(
            PortfolioExecutionLedgerEntry(9_999L, StrategySignalType.EnterLong, PortfolioExecSource.AUTO)
        )
        val events = listOf(
            StrategySignalEvent(1_000L, StrategySignalType.EnterShort, 1.14, 1_000L),
            StrategySignalEvent(3_000L, StrategySignalType.ExitShort, 0.28, 3_000L),
            StrategySignalEvent(5_000L, StrategySignalType.EnterShort, 0.85, 5_000L),
        )
        val chart = journalEventsForChartTrades(events, ledger)
        val portfolio = journalEventsForExecutionPortfolioTab(events, ledger, portfolioLedgerIncludeAuto = true)
        assertEquals(portfolio, chart)
        assertEquals(3, chart.size)
    }

    @Test
    fun portfolioTradeSourceTypeLetter_mapsConfirmLabelToSingleLetter() {
        assertEquals("Р", portfolioTradeSourceTypeLetter("ручное"))
        assertEquals("Р", portfolioTradeSourceTypeLetter("ручное · тест"))
        assertEquals("А", portfolioTradeSourceTypeLetter("авто"))
        assertEquals("—", portfolioTradeSourceTypeLetter(""))
    }

    @Test
    fun portfolioTradeChartBadgeText_combinesNumberAndType() {
        assertEquals("3А", portfolioTradeChartBadgeText("3 long", "авто"))
        assertEquals("2Р", portfolioTradeChartBadgeText("2 short", "ручное"))
    }
}
