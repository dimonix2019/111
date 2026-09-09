package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketsSpreadChartTest {

    @Test
    fun buildMarketsSpreadChartReferenceLines_withoutOpenPosition_hasFourLevelLinesOnly() {
        val lines = buildMarketsSpreadChartReferenceLines(openSide = null)
        assertEquals(4, lines.size)
        assertTrue(lines.none { it.label.startsWith("ТП") })
    }

    @Test
    fun buildMarketsSpreadChartReferenceLines_longOpen_addsTpLineAtExitNarrowWhenNoEntry() {
        val lines = buildMarketsSpreadChartReferenceLines(openSide = ZStrategyPosition.Long)
        assertEquals(5, lines.size)
        val tp = lines.last()
        assertEquals("ТП 2%", tp.label)
        assertEquals(DEFAULT_SPREAD_EXIT_NARROW, tp.value, 1e-9)
    }

    @Test
    fun buildMarketsSpreadChartReferenceLines_shortOpen_addsTpLineAtExitWideWhenNoEntry() {
        val lines = buildMarketsSpreadChartReferenceLines(openSide = ZStrategyPosition.Short)
        assertEquals(5, lines.size)
        assertEquals(DEFAULT_SPREAD_EXIT_WIDE, lines.last().value, 1e-9)
    }

    @Test
    fun buildMarketsSpreadChartReferenceLines_longWithEntry_usesTakeProfitSpread() {
        val lines = buildMarketsSpreadChartReferenceLines(
            openSide = ZStrategyPosition.Long,
            openEntrySpread = 3.2,
            depositRub = 10_000.0,
            notionalRub = 70_000.0,
        )
        val tp = lines.last()
        assertEquals("ТП 2%", tp.label)
        assertTrue(tp.value > 3.2)
        assertTrue(tp.value < DEFAULT_SPREAD_EXIT_NARROW + 0.5)
    }

    @Test
    fun buildMarketsSpreadChartReferenceLines_tpDisabledWhenPctZero() {
        val lines = buildMarketsSpreadChartReferenceLines(
            openSide = ZStrategyPosition.Long,
            takeProfitPct = 0.0,
        )
        assertEquals(4, lines.size)
    }

    @Test
    fun resolveMarketsSpreadChartOpenSide_prefersOpenExecution() {
        val exec = SandboxSpreadExecUi(
            tradeId = "t1",
            signalType = StrategySignalType.EnterShort,
            zScore = 1.0,
            barTimestampMillis = 0L,
            executedAtMillis = 0L,
            entrySpreadPercent = 6.1,
            source = PortfolioExecSource.AUTO,
            directionLabel = "Short",
            entryTimeMsk = "2026-09-04 10:00",
            longLegTicker = "TATNP",
            shortLegTicker = "TATN",
            longLegSideRu = "Покупка",
            shortLegSideRu = "Продажа",
            volumeText = "1+1",
            confirmLabel = "AUTO",
            correlationTag = "x",
            notificationIdsText = "",
            legs = emptyList(),
        )
        assertEquals(
            ZStrategyPosition.Short,
            resolveMarketsSpreadChartOpenSide(listOf(exec), ZStrategyPosition.Long),
        )
    }

    @Test
    fun resolveMarketsSpreadChartOpenSide_fallsBackToBrokerSide() {
        assertEquals(
            ZStrategyPosition.Long,
            resolveMarketsSpreadChartOpenSide(emptyList(), ZStrategyPosition.Long),
        )
        assertEquals(null, resolveMarketsSpreadChartOpenSide(emptyList(), ZStrategyPosition.Flat))
    }
}
