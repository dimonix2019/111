package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test

class MoexZStrategyPositionSyncTest {

    private fun enterExec(
        signalType: StrategySignalType,
        barTs: Long,
        tradeId: String = "D-$barTs",
    ) = SandboxSpreadExecUi(
        tradeId = tradeId,
        signalType = signalType,
        zScore = -2.0,
        barTimestampMillis = barTs,
        executedAtMillis = barTs,
        entrySpreadPercent = 3.0,
        source = PortfolioExecSource.AUTO,
        directionLabel = "long",
        entryTimeMsk = "2026-06-29 16:30",
        longLegTicker = "TATN",
        shortLegTicker = "TATNP",
        longLegSideRu = "покупка",
        shortLegSideRu = "продажа",
        volumeText = "1+1",
        confirmLabel = "авто",
        correlationTag = "t",
        notificationIdsText = "—",
        legs = emptyList(),
    )

    @Test
    fun inferPosition_fromLatestOpenEnterLong() {
        val opens = listOf(
            enterExec(StrategySignalType.EnterShort, 1_000L),
            enterExec(StrategySignalType.EnterLong, 2_000L),
        )
        assertEquals(ZStrategyPosition.Long, inferZStrategyPositionFromOpenExecutions(opens))
    }

    @Test
    fun stillOpen_filtersPairedJournalExit() {
        val open = enterExec(StrategySignalType.EnterLong, 1_000L)
        val journal = listOf(
            StrategySignalEvent(1_000L, StrategySignalType.EnterLong, -2.0, 1_000L),
            StrategySignalEvent(2_000L, StrategySignalType.ExitLong, -1.0, 2_000L),
        )
        val still = stillOpenExecutionsAfterJournalExits(listOf(open), journal)
        assertEquals(0, still.size)
    }

    @Test
    fun stillOpen_keepsOpenWhenNoExitInJournal() {
        val open = enterExec(StrategySignalType.EnterLong, 1_000L)
        val journal = listOf(
            StrategySignalEvent(1_000L, StrategySignalType.EnterLong, -2.0, 1_000L),
        )
        val still = stillOpenExecutionsAfterJournalExits(listOf(open), journal)
        assertEquals(1, still.size)
        assertEquals(StrategySignalType.EnterLong, still.first().signalType)
    }

    @Test
    fun reconcile_flatToLong_whenOpenExecutionExists() {
        val reconciled = reconcileZStrategyPositionWithOpenTrade(
            savedPosition = ZStrategyPosition.Flat,
            openExecutions = listOf(enterExec(StrategySignalType.EnterLong, 1_000L)),
        )
        assertEquals(ZStrategyPosition.Long, reconciled)
    }

    @Test
    fun reconcile_keepsFlat_whenNoOpenExecution() {
        assertEquals(
            ZStrategyPosition.Flat,
            reconcileZStrategyPositionWithOpenTrade(ZStrategyPosition.Flat, emptyList()),
        )
    }

    @Test
    fun reconcile_openTradeWinsOnMismatch() {
        val reconciled = reconcileZStrategyPositionWithOpenTrade(
            savedPosition = ZStrategyPosition.Short,
            openExecutions = listOf(enterExec(StrategySignalType.EnterLong, 1_000L)),
        )
        assertEquals(ZStrategyPosition.Long, reconciled)
    }
}
