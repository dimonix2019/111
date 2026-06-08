package com.example.moexmvp

import org.junit.Assert.assertEquals
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
            chartTail = chartTail,
            entryThreshold = 0.8,
        )
        assertEquals(metrics.closedTrades.size, analytics.tradeRiskAssessments.size)
        assertNotNull(analytics.spreadHourlyVolatility)
    }

    private fun point(label: String, z: Double = 0.0): DataPoint =
        DataPoint(
            timestampMillis = 0L,
            tradeDate = label,
            tatnClose = 100.0,
            tatnpClose = 95.0,
            spreadPercent = 5.0,
            diff = 5.0,
            zScore = z,
        )
}
