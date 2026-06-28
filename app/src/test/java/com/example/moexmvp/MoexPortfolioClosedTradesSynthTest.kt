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

    @Test
    fun resolveClosedTradeExitWallMillis_prefersJournalWallClockOverEarlierBar() {
        val entryMs = mskMillis(2026, 6, 28, 19, 11)
        val barMs = mskMillis(2026, 6, 28, 18, 30)
        val exitReceivedMs = mskMillis(2026, 6, 28, 19, 13)
        val exitEvent = StrategySignalEvent(
            timestampMillis = barMs,
            signalType = StrategySignalType.ExitLong,
            zScore = -0.44,
            receivedAtMillis = exitReceivedMs,
        )
        val resolved = resolveClosedTradeExitWallMillis(
            openExecutedAtMillis = entryMs,
            exitEvent = exitEvent,
            exitBarTimestampMillis = barMs,
        )
        assertEquals(exitReceivedMs, resolved)
    }

    @Test
    fun buildClosedRows_onProd_usesActualNotionalWithoutLeverageMultiplier() {
        val entryMs = mskMillis(2026, 6, 28, 19, 11)
        val exitMs = mskMillis(2026, 6, 28, 19, 13)
        val open = SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = StrategySignalType.EnterLong,
            zScore = -1.49,
            barTimestampMillis = mskMillis(2026, 6, 28, 18, 30),
            executedAtMillis = entryMs,
            entrySpreadPercent = 5.55,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-06-28 19:11",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "80+80",
            confirmLabel = "авто · тест",
            correlationTag = "t",
            notificationIdsText = "—",
            legs = emptyList(),
            quantityLots = 80,
            executionNotionalRub = 39_544.0,
        )
        val journal = listOf(
            StrategySignalEvent(open.barTimestampMillis, StrategySignalType.EnterLong, -1.49, entryMs),
            StrategySignalEvent(
                mskMillis(2026, 6, 28, 18, 30),
                StrategySignalType.ExitLong,
                -0.44,
                exitMs,
            ),
        )
        val points = listOf(
            point(open.barTimestampMillis, spread = 5.55, z = -1.49),
            point(mskMillis(2026, 6, 28, 18, 45), spread = 5.50, z = -0.44),
        )
        val sandboxPnl = buildClosedRowsFromSandboxOpensAndJournalExits(
            openExecutions = listOf(open),
            allJournalEvents = journal,
            points = points,
            ledger = emptyList(),
            pushLog = emptyList(),
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            portfolioLedgerIncludeAuto = true,
            executionMode = TinkoffExecutionMode.Sandbox,
        ).first.single().netPnlRubApprox
        val prodPnl = buildClosedRowsFromSandboxOpensAndJournalExits(
            openExecutions = listOf(open),
            allJournalEvents = journal,
            points = points,
            ledger = emptyList(),
            pushLog = emptyList(),
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            portfolioLedgerIncludeAuto = true,
            executionMode = TinkoffExecutionMode.Prod,
        ).first.single().netPnlRubApprox
        assertTrue(
            "Prod fallback PnL must not use 100k×7 leverage",
            kotlin.math.abs(prodPnl) < kotlin.math.abs(sandboxPnl) / 3.0,
        )
        assertEquals("2026-06-28 19:13", buildClosedRowsFromSandboxOpensAndJournalExits(
            openExecutions = listOf(open),
            allJournalEvents = journal,
            points = points,
            ledger = emptyList(),
            pushLog = emptyList(),
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            portfolioLedgerIncludeAuto = true,
            executionMode = TinkoffExecutionMode.Prod,
        ).first.single().exitTimeMsk)
    }

    private fun mskMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.time.LocalDateTime.of(year, month, day, hour, minute)
            .atZone(java.time.ZoneId.of("Europe/Moscow"))
            .toInstant()
            .toEpochMilli()

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
