package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MoexOpenTradeSpreadDriversTest {

    private fun longExec() = SandboxSpreadExecUi(
        tradeId = "D-001",
        signalType = StrategySignalType.EnterLong,
        zScore = -2.16,
        barTimestampMillis = 1_000L,
        executedAtMillis = 2_000L,
        entrySpreadPercent = 6.0,
        source = PortfolioExecSource.AUTO,
        directionLabel = "long",
        entryTimeMsk = "2026-06-29 16:30",
        longLegTicker = "TATN",
        shortLegTicker = "TATNP",
        longLegSideRu = "покупка 80 лот",
        shortLegSideRu = "продажа 80 лот",
        volumeText = "80+80",
        confirmLabel = "авто",
        correlationTag = "t",
        notificationIdsText = "—",
        legs = emptyList(),
        quantityLots = 80,
        executionNotionalRub = 89_440.0,
    )

    @Test
    fun enrichOpenTradeGroupSpreadDrivers_longSpreadWidening_positiveDelta() {
        val points = listOf(
            DataPoint(1_000L, "2026-06-29 16:30", 576.0, 542.0, 6.0, 0.0, -2.16),
            DataPoint(2_000L, "2026-06-29 18:00", 576.0, 542.0, 6.62, 0.0, 0.49),
        )
        val enriched = enrichOpenTradeGroupSpreadDrivers(longExec().toTradeGroup(), longExec(), points)
        assertEquals(6.0, enriched.entrySpreadPercent, 0.001)
        assertEquals(6.62, enriched.currentSpreadPercent, 0.001)
        assertEquals(0.62, enriched.spreadMtmPoints, 0.001)
        assertEquals(89_440.0, enriched.executionNotionalRub, 0.01)
    }

    @Test
    fun formatPortfolioOpenTradeNotional_includesVolumeAndRub() {
        val text = formatPortfolioOpenTradeNotional("80+80", 89_440.0)
        assertEquals("80+80 · ≈89440 ₽", text)
    }

    @Test
    fun formatPortfolioSpreadPercent_formatsPercent() {
        assertEquals("6.62%", formatPortfolioSpreadPercent(6.62))
        assertEquals("—", formatPortfolioSpreadPercent(Double.NaN))
    }

    @Test
    fun buildPortfolioTradesBuckets_openGroupHasSpreadDrivers() {
        val exec = longExec()
        val points = listOf(
            DataPoint(1_000L, "2026-06-29 16:30", 576.0, 542.0, 6.0, 0.0, -2.16),
            DataPoint(2_000L, "2026-06-29 18:00", 576.0, 542.0, 6.62, 0.0, 0.49),
        )
        val (open, _) = buildPortfolioTradesBuckets(
            openExecutions = listOf(exec),
            closedRows = emptyList(),
            lookbackDays = 7,
            m15Points = points,
        )
        val group = open.groups.single()
        assertFalse(group.entrySpreadPercent.isNaN())
        assertFalse(group.spreadMtmPoints.isNaN())
        assertEquals(89_440.0, group.executionNotionalRub, 0.01)
    }
}
