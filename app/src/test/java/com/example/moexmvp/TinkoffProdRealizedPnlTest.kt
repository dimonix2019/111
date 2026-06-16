package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TinkoffProdRealizedPnlTest {

    @Test
    fun parseFormattedRubString_parsesRussianFormat() {
        assertEquals(9918.76, parseFormattedRubString("9 918,76 ₽")!!, 0.01)
        assertEquals(10000.0, parseFormattedRubString("10000.00 ₽")!!, 0.01)
    }

    @Test
    fun computeProdClosedTradePnl_prefersRealizedCashDeltaOverMtmLegs() {
        val record = ProdClosedSpreadExecRecord(
            tradeId = "D-003",
            signalType = StrategySignalType.EnterShort,
            zScore = 1.17,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 0.0,
            source = PortfolioExecSource.AUTO,
            directionLabel = "short",
            entryTimeMsk = "2026-06-16 13:15",
            longLegTicker = "TATNP",
            shortLegTicker = "TATN",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "6+6 лот",
            confirmLabel = "авто",
            correlationTag = "tag",
            quantityLots = 6,
            executionNotionalRub = 10_000.0,
            exitTimestampMillis = 3_000L,
            exitZScore = 0.12,
            longLegYieldRub = 616.0,
            shortLegYieldRub = -475.0,
            realizedNetRub = -82.0,
            entryPortfolioCashRub = 10_000.0,
            exitPortfolioCashRub = 9_918.0,
            operationsCommissionRub = 27.5,
        )
        val pnl = computeProdClosedTradePnl(record, commissionPercentPerSide = 0.04)
        assertEquals(-82.0, pnl.netRub, 0.01)
        assertTrue(pnl.netRub < 100.0)
    }
}
