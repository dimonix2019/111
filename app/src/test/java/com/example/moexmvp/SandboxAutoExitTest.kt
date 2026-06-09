package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SandboxAutoExitTest {

    @Test
    fun findOpenTradeForStrategyExit_picksLatestMatchingEntry() {
        val olderShort = sandboxOpen("D-001", StrategySignalType.EnterShort, 1000L)
        val newerShort = sandboxOpen("D-002", StrategySignalType.EnterShort, 2000L)
        val longOpen = sandboxOpen("D-003", StrategySignalType.EnterLong, 1500L)

        val found = findOpenTradeForStrategyExit(
            listOf(olderShort, newerShort, longOpen),
            StrategySignalType.ExitShort,
        )
        assertEquals("D-002", found?.tradeId)
    }

    @Test
    fun findOpenTradeForStrategyExit_exitLongMatchesEnterLong() {
        val shortOpen = sandboxOpen("D-001", StrategySignalType.EnterShort, 1000L)
        val longOpen = sandboxOpen("D-002", StrategySignalType.EnterLong, 2000L)

        val found = findOpenTradeForStrategyExit(
            listOf(shortOpen, longOpen),
            StrategySignalType.ExitLong,
        )
        assertEquals("D-002", found?.tradeId)
    }

    @Test
    fun findOpenTradeForStrategyExit_returnsNullWhenNoMatchingOpen() {
        val shortOpen = sandboxOpen("D-001", StrategySignalType.EnterShort, 1000L)
        assertNull(
            findOpenTradeForStrategyExit(
                listOf(shortOpen),
                StrategySignalType.ExitLong,
            )
        )
    }

    @Test
    fun findOpenTradeForStrategyExit_ignoresManualTrades() {
        val manualLong = sandboxOpen("D-M", StrategySignalType.EnterLong, 3000L)
            .copy(source = PortfolioExecSource.MANUAL, confirmLabel = "ручное")
        assertNull(
            findOpenTradeForStrategyExit(
                listOf(manualLong),
                StrategySignalType.ExitLong,
            )
        )
    }

    private fun sandboxOpen(
        tradeId: String,
        signalType: StrategySignalType,
        barTs: Long,
    ) = SandboxSpreadExecUi(
        tradeId = tradeId,
        signalType = signalType,
        zScore = 1.0,
        barTimestampMillis = barTs,
        executedAtMillis = barTs,
        entrySpreadPercent = 3.0,
        source = PortfolioExecSource.AUTO,
        directionLabel = "short",
        entryTimeMsk = "2026-05-18 10:00",
        longLegTicker = "TATNP",
        shortLegTicker = "TATN",
        longLegSideRu = "покупка",
        shortLegSideRu = "продажа",
        volumeText = "1+1",
        confirmLabel = "авто",
        correlationTag = "t",
        notificationIdsText = "—",
        legs = emptyList(),
    )
}
