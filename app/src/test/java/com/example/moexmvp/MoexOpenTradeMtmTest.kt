package com.example.moexmvp

import org.junit.Assert.assertEquals
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
}
