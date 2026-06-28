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
    fun buildBuckets_openShowsLatestOnly() {
        val executedAt = ZonedDateTime.of(2026, 5, 15, 10, 0, 0, 0, ZoneId.of("Europe/Moscow"))
            .toInstant()
            .toEpochMilli()
        val autoOpen = sandboxExec(executedAt = executedAt + 60_000).copy(
            tradeId = "D-A",
            source = PortfolioExecSource.AUTO,
            confirmLabel = "авто",
        )
        val manualOpen = sandboxExec(executedAt = executedAt).copy(
            tradeId = "D-M",
            source = PortfolioExecSource.MANUAL,
            confirmLabel = "ручное",
        )
        val zone = ZoneId.of("Europe/Moscow")
        val windowStart = ZonedDateTime.of(2026, 5, 13, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val (openAll, closedAll) = buildPortfolioTradesBuckets(
            listOf(manualOpen, autoOpen),
            emptyList(),
            lookbackDays = 30L,
            windowStartMillis = windowStart,
        )
        assertEquals(1, openAll.tradeCount)
        assertEquals("D-A", openAll.groups.single().tradeId)
        assertEquals(0, closedAll.tradeCount)
    }

    @Test
    fun buildBuckets_closedSourceFilter_brokerAndTestOnly() {
        val testClosed = closedRow("T-T", "ручное · тест")
        val brokerClosed = closedRow("T-B", "брокер")
        val autoClosed = closedRow("T-A", "авто")
        val windowStart = ZonedDateTime.of(2026, 5, 13, 0, 0, 0, 0, ZoneId.of("Europe/Moscow"))
            .toInstant()
            .toEpochMilli()
        val (_, all) = buildPortfolioTradesBuckets(
            emptyList(),
            listOf(testClosed, brokerClosed, autoClosed),
            lookbackDays = 30L,
            windowStartMillis = windowStart,
            closedSourceFilter = PortfolioClosedTradesSourceFilter.All,
        )
        assertEquals(3, all.tradeCount)
        val (_, brokerOnly) = buildPortfolioTradesBuckets(
            emptyList(),
            listOf(testClosed, brokerClosed, autoClosed),
            lookbackDays = 30L,
            windowStartMillis = windowStart,
            closedSourceFilter = PortfolioClosedTradesSourceFilter.Broker,
        )
        assertEquals(1, brokerOnly.tradeCount)
        assertEquals("T-B", brokerOnly.groups.single().tradeId)
        val (_, testOnly) = buildPortfolioTradesBuckets(
            emptyList(),
            listOf(testClosed, brokerClosed, autoClosed),
            lookbackDays = 30L,
            windowStartMillis = windowStart,
            closedSourceFilter = PortfolioClosedTradesSourceFilter.TestOnly,
        )
        assertEquals(1, testOnly.tradeCount)
        assertEquals("T-T", testOnly.groups.single().tradeId)
    }

    @Test
    fun filterClosedTradesBySourceFilter_testOnly() {
        val rows = listOf(
            closedRow("1", "ручное · тест"),
            closedRow("2", "брокер"),
            closedRow("3", "авто"),
        )
        assertEquals(1, filterClosedTradesBySourceFilter(rows, PortfolioClosedTradesSourceFilter.TestOnly).size)
        assertEquals(1, filterClosedTradesBySourceFilter(rows, PortfolioClosedTradesSourceFilter.Broker).size)
        assertEquals(3, filterClosedTradesBySourceFilter(rows, PortfolioClosedTradesSourceFilter.All).size)
    }

    @Test
    fun buildBuckets_openTradesIgnoreLookbackWindow() {
        val zone = ZoneId.of("Europe/Moscow")
        val windowStart = ZonedDateTime.of(2026, 6, 8, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val oldOpen = sandboxExec(executedAt = windowStart - 86_400_000L).copy(tradeId = "D-OLD")
        val (open, _) = buildPortfolioTradesBuckets(
            listOf(oldOpen),
            emptyList(),
            lookbackDays = 3L,
            windowStartMillis = windowStart,
        )
        assertEquals(1, open.tradeCount)
        assertEquals("D-OLD", open.groups.single().tradeId)
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

    @Test
    fun resolveSingleOpenExecutionForDisplay_picksLatestByExecutedAt() {
        val older = sandboxExec(executedAt = 1_000L).copy(tradeId = "D-OLD")
        val newer = sandboxExec(executedAt = 2_000L).copy(tradeId = "D-NEW")
        assertEquals("D-NEW", resolveSingleOpenExecutionForDisplay(listOf(older, newer))?.tradeId)
    }

    private fun closedRow(tradeId: String, confirm: String) = PortfolioConfirmedTradeTableRow(
        tradeId = tradeId,
        directionLabel = "long",
        entryTimeMsk = "2026-05-15 10:00",
        exitTimeMsk = "2026-05-16 11:00",
        longLegTicker = "TATN",
        shortLegTicker = "TATNP",
        longLegSideRu = "L",
        shortLegSideRu = "S",
        volumeText = "1+1",
        confirmLabel = confirm,
        entryZ = 1.0,
        exitZ = 0.5,
        notificationIdsText = "—",
        legLongPnlSplitRubApprox = 0.0,
        legShortPnlSplitRubApprox = 0.0,
        grossPnlRubApprox = 0.0,
        netPnlRubApprox = 0.0,
    )

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
