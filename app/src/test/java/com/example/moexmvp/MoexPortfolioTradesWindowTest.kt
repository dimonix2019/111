package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MoexPortfolioTradesWindowTest {

    @Test
    fun filterOpen_byExecutedAtWithinThreeDays() {
        val zone = ZoneId.of("Europe/Moscow")
        val windowStart = ZonedDateTime.of(2026, 5, 13, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val old = sandboxExec(executedAt = windowStart - 1)
        val fresh = sandboxExec(executedAt = windowStart + 60_000)
        val filtered = filterSandboxExecutionsInWindow(listOf(old, fresh), lookbackDays = 3L, windowStartMillis = windowStart)
        assertEquals(1, filtered.size)
        assertEquals(fresh.tradeId, filtered.single().tradeId)
    }

    @Test
    fun buildBuckets_sumsClosedPnl() {
        val row = PortfolioConfirmedTradeTableRow(
            tradeId = "T-1",
            directionLabel = "long",
            entryTimeMsk = "2026-05-15 10:00",
            exitTimeMsk = "2026-05-16 11:00",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "L",
            shortLegSideRu = "S",
            volumeText = "1+1",
            confirmLabel = "ручное",
            entryZ = 1.0,
            exitZ = 0.5,
            notificationIdsText = "—",
            legLongPnlSplitRubApprox = 500.0,
            legShortPnlSplitRubApprox = 500.0,
            grossPnlRubApprox = 1100.0,
            netPnlRubApprox = 1000.0
        )
        val zone = ZoneId.of("Europe/Moscow")
        val windowStart = ZonedDateTime.of(2026, 5, 13, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val (open, closed) = buildPortfolioTradesBuckets(emptyList(), listOf(row), lookbackDays = 3L, windowStartMillis = windowStart)
        assertEquals(0, open.tradeCount)
        assertEquals(1, closed.tradeCount)
        assertEquals(1000.0, closed.totalPnlRub, 0.01)
    }

  private fun sandboxExec(executedAt: Long) = SandboxSpreadExecUi(
        tradeId = "D-1",
        signalType = StrategySignalType.EnterLong,
        zScore = 1.0,
        barTimestampMillis = executedAt,
        executedAtMillis = executedAt,
        entrySpreadPercent = 1.0,
        source = PortfolioExecSource.MANUAL,
        directionLabel = "long",
        entryTimeMsk = "x",
        longLegTicker = "TATN",
        shortLegTicker = "TATNP",
        longLegSideRu = "L",
        shortLegSideRu = "S",
        volumeText = "1+1",
        confirmLabel = "ручное",
        correlationTag = "t",
        notificationIdsText = "—",
        legs = emptyList()
    )
}
