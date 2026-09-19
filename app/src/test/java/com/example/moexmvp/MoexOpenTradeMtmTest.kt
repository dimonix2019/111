package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexOpenTradeMtmTest {

    @Test
    fun longMtm_positiveWhenSpreadWidens() {
        val rub = estimateOpenSpreadMtmGrossRub(
            signalType = StrategySignalType.EnterLong,
            entrySpreadPercent = 1.0,
            currentSpreadPercent = 1.5,
            notionalRub = 100_000.0,
            leverage = 7.0
        )
        assertEquals(3_500.0, rub, 1.0)
    }

    @Test
    fun zeroEntrySpread_usesBarFromPoints_notEntireCurrentSpread() {
        val points = listOf(
            DataPoint(1_000L, "a", 0.0, 0.0, 1.0, 0.0, 0.0),
            DataPoint(2_000L, "b", 0.0, 0.0, 2.5, 0.0, 0.0)
        )
        val rub = estimateOpenSpreadMtmGrossRub(
            signalType = StrategySignalType.EnterLong,
            entrySpreadPercent = 0.0,
            currentSpreadPercent = 2.5,
            notionalRub = 100_000.0,
            leverage = 7.0,
            points = points,
            barTimestampMillis = 1_000L
        )
        // (2.5 - 1.0) * 700_000 / 100 = 10_500, not (2.5 - 0) * 700_000 / 100 = 17_500
        assertEquals(10_500.0, rub, 1.0)
    }

    @Test
    fun enrichOpenSandbox_usesCurrentZAndMtm() {
        val exec = SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = StrategySignalType.EnterLong,
            zScore = PORTFOLIO_TEST_SIGNAL_Z_MARKER,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 0.0,
            source = PortfolioExecSource.MANUAL,
            directionLabel = "long",
            entryTimeMsk = "2026-05-16 10:00",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "LONG",
            shortLegSideRu = "SHORT",
            volumeText = "1+1",
            confirmLabel = "ручное",
            correlationTag = "tag",
            notificationIdsText = "—",
            legs = emptyList()
        )
        val points = listOf(
            DataPoint(1_000L, "a", 0.0, 0.0, 1.0, 0.0, 0.0),
            DataPoint(2_000L, "b", 0.0, 0.0, 2.0, 0.0, 1.5)
        )
        val out = enrichOpenSandboxExecutions(listOf(exec), points, 100_000.0, 7.0, 0.04).single()
        assertEquals(1.0, out.entrySpreadPercent, 0.001)
        assertEquals(1.5, out.exitZDisplay, 0.001)
        assertEquals(1.5, out.zScore, 0.001)
        assertTrue(out.netPnlRubApprox > 0.0)
        assertTrue(out.netPnlRubApprox < 17_000.0)
    }

    @Test
    fun enrichSandboxExecutionsIfNeeded_skipsWhenAlreadyEnriched() {
        val enriched = SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = StrategySignalType.EnterLong,
            zScore = 1.0,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 1.0,
            source = PortfolioExecSource.MANUAL,
            directionLabel = "long",
            entryTimeMsk = "2026-05-16 10:00",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "LONG",
            shortLegSideRu = "SHORT",
            volumeText = "1+1",
            confirmLabel = "ручное",
            correlationTag = "tag",
            notificationIdsText = "—",
            legs = emptyList(),
            exitZDisplay = 1.2,
            netPnlRubApprox = 100.0
        )
        val points = listOf(
            DataPoint(1_000L, "a", 0.0, 0.0, 1.0, 0.0, 0.0),
            DataPoint(2_000L, "b", 0.0, 0.0, 2.0, 0.0, 1.5)
        )
        assertSame(enriched, enrichSandboxExecutionsIfNeeded(listOf(enriched), points, 7.0, 0.04).single())
    }

    @Test
    fun enrichSandboxExecutionsIfNeeded_onProd_preservesBrokerPnlWithMoexPoints() {
        val brokerEnriched = SandboxSpreadExecUi(
            tradeId = "P-001",
            signalType = StrategySignalType.EnterLong,
            zScore = -0.86,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 6.20,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-06-16 07:45",
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
            legLongPnlSplitRubApprox = -31.91,
            legShortPnlSplitRubApprox = 40.0,
            netPnlRubApprox = 4.09,
            exitZDisplay = Double.NaN,
        )
        val points = listOf(
            DataPoint(1_000L, "2026-06-16 07:45", 572.4, 538.7, 6.20, 0.0, -0.86),
            DataPoint(2_000L, "2026-06-16 09:15", 572.4, 538.7, 6.20, 0.0, -1.01),
        )
        val out = enrichSandboxExecutionsIfNeeded(
            executions = listOf(brokerEnriched),
            points = points,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            executionMode = TinkoffExecutionMode.Prod,
        ).single()
        assertEquals(4.09, out.netPnlRubApprox, 0.01)
        assertEquals(-31.91, out.legLongPnlSplitRubApprox, 0.02)
        assertEquals(-1.01, out.exitZDisplay, 0.001)
    }

    @Test
    fun openPnlBrokerSourceLabel_whenBrokerPnlPresent() {
        val exec = SandboxSpreadExecUi(
            tradeId = "P-001",
            signalType = StrategySignalType.EnterLong,
            zScore = -0.86,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 6.20,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-06-16 07:45",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "10+10",
            confirmLabel = "авто",
            correlationTag = "tag",
            notificationIdsText = "—",
            legs = emptyList(),
            legLongPnlSplitRubApprox = -10.0,
            legShortPnlSplitRubApprox = 20.0,
            netPnlRubApprox = 8.0,
        )
        assertEquals("счёт Tinkoff", openPnlBrokerSourceLabel(listOf(exec)))
    }
}
