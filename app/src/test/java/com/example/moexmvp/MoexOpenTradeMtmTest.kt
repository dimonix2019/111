package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexOpenTradeMtmTest {

    @Test
    fun longMtm_positiveWhenSpreadWidens() {
        val rub = estimateOpenSpreadMtmRub(
            signalType = StrategySignalType.EnterLong,
            entrySpreadPercent = 1.0,
            currentSpreadPercent = 1.5,
            notionalRub = 100_000.0,
            leverage = 7.0
        )
        assertTrue(rub > 0.0)
    }

    @Test
    fun shortMtm_positiveWhenSpreadNarrows() {
        val rub = estimateOpenSpreadMtmRub(
            signalType = StrategySignalType.EnterShort,
            entrySpreadPercent = 2.0,
            currentSpreadPercent = 1.0,
            notionalRub = 100_000.0,
            leverage = 7.0
        )
        assertTrue(rub > 0.0)
    }

    @Test
    fun enrichOpenSandbox_usesCurrentZAndMtm() {
        val exec = SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = StrategySignalType.EnterLong,
            zScore = PORTFOLIO_TEST_SIGNAL_Z_MARKER,
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
            legs = emptyList()
        )
        val points = listOf(
            DataPoint(0L, "a", 0.0, 0.0, 1.0, 0.0, 0.0),
            DataPoint(1L, "b", 0.0, 0.0, 2.0, 0.0, 1.5)
        )
        val out = enrichOpenSandboxExecutions(listOf(exec), points, 100_000.0, 7.0).single()
        assertEquals(1.5, out.exitZDisplay, 0.001)
        assertEquals(1.5, out.zScore, 0.001)
        assertTrue(out.netPnlRubApprox > 0.0)
    }
}
