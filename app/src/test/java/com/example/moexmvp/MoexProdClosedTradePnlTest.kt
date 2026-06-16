package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexProdClosedTradePnlTest {

    @Test
    fun computeProdClosedTradePnlFromBroker_usesLegYieldsNotMoexSpread() {
        val exec = SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = StrategySignalType.EnterLong,
            zScore = -0.86,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 6.2,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-06-16 07:15",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка 10 лот",
            shortLegSideRu = "продажа 10 лот",
            volumeText = "10+10 лот",
            confirmLabel = "авто",
            correlationTag = "tag",
            notificationIdsText = "—",
            legs = emptyList(),
            quantityLots = 10,
            executionNotionalRub = 11_120.0,
        )
        val broker = SpreadLegBrokerPnl(longLegYieldRub = -31.91, shortLegYieldRub = 40.0)
        val pnl = computeProdClosedTradePnlFromBroker(
            execution = exec,
            brokerPnl = broker,
            exitTimestampMillis = 3_000L,
            commissionPercentPerSide = 0.04,
        )
        assertEquals(8.09, pnl.grossRub, 0.03)
        assertTrue(pnl.netRub < pnl.grossRub)
        assertTrue(pnl.netRub < 1073.0)
    }

    @Test
    fun buildClosedRowsFromProdBrokerLog_prefersBrokerLegPnl() {
        val record = ProdClosedSpreadExecRecord(
            tradeId = "D-001",
            signalType = StrategySignalType.EnterLong,
            zScore = -0.86,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 6.2,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-06-16 07:15",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка 10 лот",
            shortLegSideRu = "продажа 10 лот",
            volumeText = "10+10 лот",
            confirmLabel = "авто",
            correlationTag = "tag",
            quantityLots = 10,
            executionNotionalRub = 11_120.0,
            exitTimestampMillis = 4_000L,
            exitZScore = -0.5,
            longLegYieldRub = -31.91,
            shortLegYieldRub = 40.0,
        )
        val row = buildClosedRowsFromProdBrokerLog(
            records = listOf(record),
            journalEvents = emptyList(),
            pushLog = emptyList(),
            commissionPercentPerSide = 0.04,
        ).single()
        assertEquals(-31.91, row.legLongPnlSplitRubApprox, 0.02)
        assertEquals(40.0, row.legShortPnlSplitRubApprox, 0.01)
        assertTrue(row.netPnlRubApprox < 200.0)
    }

    @Test
    fun mergeClosedPortfolioTableRowsPreferBroker_overridesSynthMoexRow() {
        val synth = PortfolioConfirmedTradeTableRow(
            tradeId = "T-O001",
            directionLabel = "long",
            entryTimeMsk = "2026-06-16 07:15",
            exitTimeMsk = "2026-06-16 11:15",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "10+10 лот",
            confirmLabel = "авто",
            entryZ = -0.86,
            exitZ = -0.5,
            notificationIdsText = "—",
            legLongPnlSplitRubApprox = 500.0,
            legShortPnlSplitRubApprox = 573.0,
            grossPnlRubApprox = 1073.0,
            netPnlRubApprox = 1073.0,
        )
        val broker = synth.copy(
            tradeId = "T-P001",
            legLongPnlSplitRubApprox = -31.91,
            legShortPnlSplitRubApprox = 40.0,
            grossPnlRubApprox = 8.09,
            netPnlRubApprox = 4.0,
        )
        val merged = mergeClosedPortfolioTableRowsPreferBroker(
            fromReplay = emptyList(),
            fromOpens = listOf(synth),
            fromProdBroker = listOf(broker),
        )
        assertEquals(1, merged.size)
        assertEquals(-31.91, merged.single().legLongPnlSplitRubApprox, 0.02)
    }

    @Test
    fun mergePortfolioClosedTableRowsForMode_onProd_dropsSignalReplayRows() {
        fun signalRow(pnl: Double) = PortfolioConfirmedTradeTableRow(
            tradeId = "T-001",
            directionLabel = "long",
            entryTimeMsk = "2026-06-16 07:15",
            exitTimeMsk = "2026-06-16 11:15",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "1+1 лот",
            confirmLabel = "сигнал",
            entryZ = -0.86,
            exitZ = -0.31,
            notificationIdsText = "—",
            legLongPnlSplitRubApprox = pnl / 2,
            legShortPnlSplitRubApprox = pnl / 2,
            grossPnlRubApprox = pnl,
            netPnlRubApprox = pnl,
        )
        val autoBroker = signalRow(81.0).copy(
            tradeId = "T-P001",
            confirmLabel = "авто",
            legLongPnlSplitRubApprox = -45.0,
            legShortPnlSplitRubApprox = 181.0,
        )
        val merged = mergePortfolioClosedTableRowsForMode(
            mode = TinkoffExecutionMode.Prod,
            fromReplay = listOf(signalRow(1073.0), signalRow(2625.0)),
            fromOpens = emptyList(),
            fromProdBroker = listOf(autoBroker),
        )
        assertEquals(1, merged.size)
        assertEquals("авто", merged.single().confirmLabel)
        assertTrue(merged.single().netPnlRubApprox < 500.0)
    }

    @Test
    fun filterConfirmedTableRowsByPortfolioMode_onProd_hidesSignalRows() {
        fun row(confirm: String) = PortfolioConfirmedTradeTableRow(
            tradeId = "T-001",
            directionLabel = "long",
            entryTimeMsk = "t",
            exitTimeMsk = "t",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "1+1",
            confirmLabel = confirm,
            entryZ = 0.0,
            exitZ = 0.0,
            notificationIdsText = "—",
            legLongPnlSplitRubApprox = 0.0,
            legShortPnlSplitRubApprox = 0.0,
            grossPnlRubApprox = 0.0,
            netPnlRubApprox = 0.0,
        )
        val filtered = filterConfirmedTableRowsByPortfolioMode(
            rows = listOf(row("сигнал"), row("авто")),
            portfolioLedgerIncludeAuto = true,
            executionMode = TinkoffExecutionMode.Prod,
        )
        assertEquals(1, filtered.size)
        assertEquals("авто", filtered.single().confirmLabel)
    }
}
