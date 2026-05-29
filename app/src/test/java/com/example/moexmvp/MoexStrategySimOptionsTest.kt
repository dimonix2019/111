package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MoexStrategySimOptionsTest {

    @Test
    fun isTatnReportBlackoutDay_includesPublicationAndDayBefore() {
        val reportDay = LocalDate.of(2025, 5, 28)
        assertTrue(isTatnReportBlackoutDay(reportDay))
        assertTrue(isTatnReportBlackoutDay(reportDay.minusDays(1)))
        assertFalse(isTatnReportBlackoutDay(reportDay.minusDays(2)))
    }

    @Test
    fun isAtOrAfterMskCutoff_respectsBarTime() {
        val cutoff = LocalTime.of(18, 45)
        assertFalse(isAtOrAfterMskCutoff("2026-05-01 18:30", cutoff))
        assertTrue(isAtOrAfterMskCutoff("2026-05-01 18:45", cutoff))
        assertTrue(isAtOrAfterMskCutoff("2026-05-01 19:00", cutoff))
    }

    @Test
    fun buildZStrategyPortfolioMetrics_pyramiding_addsNotionalOnce() {
        val points = listOf(
            point(0, z = 0.0, spread = 10.0, date = "2026-05-01 10:00"),
            point(1, z = -0.85, spread = 10.0, date = "2026-05-01 10:15"),
            point(2, z = -1.05, spread = 10.2, date = "2026-05-01 10:30"),
            point(3, z = -0.60, spread = 10.5, date = "2026-05-01 10:45")
        )
        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null)
        val base = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "base"
        )!!
        val pyramid = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "pyramid",
            simOptions = ZStrategySimOptions(
                pyramidAddNotionalRub = 50_000.0,
                pyramidZDepth = 1.0
            )
        )!!

        assertEquals(1, base.closedTrades.size)
        assertEquals(1, pyramid.closedTrades.size)
        assertTrue(pyramid.totalCommissionRub > base.totalCommissionRub)
        assertTrue(pyramid.totalPnlRubApprox != base.totalPnlRubApprox)
    }

    @Test
    fun buildZStrategyPortfolioMetrics_closeBeforeClearing_forcesExit() {
        val points = listOf(
            point(0, z = 0.0, spread = 10.0, date = "2026-05-01 17:45"),
            point(1, z = -0.85, spread = 10.0, date = "2026-05-01 18:00"),
            point(2, z = -0.75, spread = 10.1, date = "2026-05-01 18:45")
        )
        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null)
        val hold = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "hold"
        )!!
        val clearing = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "clearing",
            simOptions = ZStrategySimOptions(
                closeBeforeClearingMsk = MOEX_EVENING_CLEARING_CUTOFF_MSK
            )
        )!!

        assertTrue(hold.openPosition != null)
        assertEquals(1, clearing.closedTrades.size)
        assertEquals("2026-05-01 18:45", clearing.closedTrades[0].exitDate)
    }

    @Test
    fun buildZStrategyPortfolioMetrics_skipReportDays_blocksEntry() {
        val reportDay = TATN_REPORT_PUBLICATION_DAYS_MSK.first()
        val points = listOf(
            point(0, z = 0.0, spread = 10.0, date = "$reportDay 10:00"),
            point(1, z = -0.85, spread = 10.0, date = "$reportDay 10:15"),
            point(2, z = -0.60, spread = 10.5, date = "$reportDay 10:30")
        )
        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null)
        val normal = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "normal"
        )!!
        val skip = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "skip",
            simOptions = ZStrategySimOptions(skipTatnReportDays = true)
        )!!

        assertEquals(1, normal.closedTrades.size)
        assertTrue(skip.closedTrades.isEmpty())
        assertEquals(null, skip.openPosition)
    }

    private fun point(index: Int, z: Double, spread: Double, date: String): DataPoint =
        DataPoint(
            timestampMillis = index.toLong(),
            tradeDate = date,
            tatnClose = 100.0,
            tatnpClose = 90.0,
            spreadPercent = spread,
            diff = 0.0,
            zScore = z
        )
}
