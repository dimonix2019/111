package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MoexStrategyTestSimCacheTest {

    @Test
    fun buildM15BarIndexByLabel_mapsTradeDates() {
        val points = listOf(
            point("2026-01-01 10:00"),
            point("2026-01-01 10:15"),
        )
        val index = buildM15BarIndexByLabel(points)
        assertEquals(0, index["2026-01-01 10:00"])
        assertEquals(1, index["2026-01-01 10:15"])
    }

    @Test
    fun buildStrategyTestVisibleAnalytics_buildsVolatilityReport() {
        val chartTail = listOf(
            point("2026-01-01 10:00", z = -1.1),
            point("2026-01-01 10:15", z = -1.0),
            point("2026-01-01 10:30", z = -0.9),
            point("2026-01-01 10:45", z = -0.8),
        )
        val metrics = buildZStrategyPortfolioMetrics(
            points = chartTail,
            thresholds = DynamicThresholds(0.8, 0.7, null),
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "test",
            compoundReturns = false,
            exitMode = ZStrategyExitMode.FixedThreshold,
        ) ?: error("expected metrics")
        val analytics = buildStrategyTestVisibleAnalytics(
            metrics = metrics,
            chartPoints = chartTail,
            m15PointsForRisk = chartTail,
            entryThreshold = 0.8,
        )
        assertEquals(metrics.closedTrades.size, analytics.tradeRiskAssessments.size)
        assertEquals(metrics.closedTrades.size, analytics.chartTradeSegments.size)
        assertNotNull(analytics.spreadHourlyVolatility)
    }

    @Test
    fun buildTradingViewTradeSegmentsFromStrategyTest_includesAllClosedTrades() {
        val points = listOf(
            point("2026-05-19 10:00", z = -1.1),
            point("2026-05-19 10:15", z = -0.6),
            point("2026-05-20 10:00", z = 0.9),
            point("2026-05-20 10:15", z = 0.5),
        )
        val tradeItems = listOf(
            StrategyTestTradeItem(
                trade = PortfolioClosedTrade(
                    direction = ZStrategyPosition.Long,
                    entryDate = "2026-05-19 10:00",
                    exitDate = "2026-05-19 10:15",
                    entrySpreadPercent = 10.0,
                    exitSpreadPercent = 10.4,
                    pnlSpreadPoints = 0.4,
                    grossPnlRubApprox = 100.0,
                    pnlRubApprox = 90.0,
                ),
            ),
            StrategyTestTradeItem(
                trade = PortfolioClosedTrade(
                    direction = ZStrategyPosition.Short,
                    entryDate = "2026-05-20 10:00",
                    exitDate = "2026-05-20 10:15",
                    entrySpreadPercent = 10.0,
                    exitSpreadPercent = 9.6,
                    pnlSpreadPoints = 0.4,
                    grossPnlRubApprox = 80.0,
                    pnlRubApprox = 70.0,
                ),
            ),
        )
        val segments = buildTradingViewTradeSegmentsFromStrategyTest(tradeItems, points)
        assertEquals(2, segments.size)
        assertEquals("1A", segments[0].id)
        assertEquals("2R", segments[1].id)
    }

    @Test
    fun strategyTestM15DataFingerprint_changesWhenSeriesTailChanges() {
        val a = listOf(
            point("2026-01-01 10:00", ts = 1L),
            point("2026-01-01 10:15", ts = 2L),
        )
        val b = listOf(
            point("2026-01-01 10:00", ts = 1L),
            point("2026-01-01 10:15", ts = 3L),
        )
        assertNotEquals(strategyTestM15DataFingerprint(a), strategyTestM15DataFingerprint(b))
    }

    private fun point(label: String, z: Double = 0.0, ts: Long = 0L): DataPoint =
        DataPoint(
            timestampMillis = ts,
            tradeDate = label,
            tatnClose = 100.0,
            tatnpClose = 95.0,
            spreadPercent = 5.0,
            diff = 5.0,
            zScore = z,
        )
}
