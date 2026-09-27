package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun chartTakeProfitForecast(
    exitSpreadPercent: Double,
    takeProfitPct: Double = 2.0,
): TakeProfitForecast = TakeProfitForecast(
    exitSpreadPercent = exitSpreadPercent,
    executableExitSpreadPercent = exitSpreadPercent - 0.1,
    entrySpreadPercent = 4.2,
    targetTatnRub = 634.0,
    targetTatnpRub = 603.0,
    netPnlRub = 200.0,
    pnlPercentFromDeposit = takeProfitPct,
    depositRub = 10_000.0,
    entryCommissionRub = 28.0,
    exitCommissionRub = 28.0,
    overnightShortRub = 35.0,
    overnightMarginLoanRub = 0.0,
    overnightDays = 1L,
    takeProfitPct = takeProfitPct,
)

class MarketsSpreadChartTest {

    @Test
    fun buildMarketsSpreadChartReferenceLines_withoutOpenPosition_hasFourLevelLinesOnly() {
        val lines = buildMarketsSpreadChartReferenceLines(openSide = null)
        assertEquals(4, lines.size)
        assertTrue(lines.none { it.label.startsWith("ТП") })
    }

    @Test
    fun buildMarketsSpreadChartReferenceLines_longOpenWithoutForecast_omitsTpLine() {
        val lines = buildMarketsSpreadChartReferenceLines(openSide = ZStrategyPosition.Long)
        assertEquals(4, lines.size)
        assertTrue(lines.none { it.label.startsWith("ТП") })
    }

    @Test
    fun buildMarketsSpreadChartReferenceLines_shortOpenWithoutForecast_omitsTpLine() {
        val lines = buildMarketsSpreadChartReferenceLines(openSide = ZStrategyPosition.Short)
        assertEquals(4, lines.size)
    }

    @Test
    fun buildMarketsSpreadChartReferenceLines_usesExactSharedDynamicForecast() {
        val lines = buildMarketsSpreadChartReferenceLines(
            openSide = ZStrategyPosition.Long,
            takeProfitForecast = chartTakeProfitForecast(exitSpreadPercent = 5.17),
        )
        val tp = lines.last()
        assertEquals("ТП 2%", tp.label)
        assertEquals(5.17, tp.value, 1e-9)
    }

    @Test
    fun buildMarketsSpreadChartReferenceLines_customTakeProfitPctLabel() {
        val lines = buildMarketsSpreadChartReferenceLines(
            openSide = ZStrategyPosition.Long,
            takeProfitForecast = chartTakeProfitForecast(
                exitSpreadPercent = 5.3,
                takeProfitPct = 3.5,
            ),
        )
        assertEquals("ТП 3.5%", lines.last().label)
    }

    @Test
    fun buildMarketsSpreadChartReferenceLines_tpDisabledWhenPctZero() {
        val lines = buildMarketsSpreadChartReferenceLines(
            openSide = ZStrategyPosition.Long,
            takeProfitForecast = chartTakeProfitForecast(
                exitSpreadPercent = 5.3,
                takeProfitPct = 0.0,
            ),
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
