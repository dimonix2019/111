package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MoexMarketsM15ZChartTest {

    @Test
    fun buildZScoreCandlesFromM15Points_keepsStrategyZAsClose() {
        val points = listOf(
            point("2026-05-19 10:00", z = -0.4),
            point("2026-05-19 10:15", z = 0.7),
            point("2026-05-19 10:30", z = 0.2)
        )

        val candles = buildZScoreCandlesFromM15Points(points)

        assertEquals(points.map { it.tradeDate }, candles.map { it.label })
        assertEquals(points.map { it.zScore }, candles.map { it.close })
        assertEquals(-0.4, candles[0].open, 1e-9)
        assertEquals(-0.4, candles[1].open, 1e-9)
        assertEquals(0.7, candles[1].high, 1e-9)
        assertEquals(-0.4, candles[1].low, 1e-9)
    }

    @Test
    fun filterM15PointsForMarketsPeriod_filtersWithoutRecomputingZ() {
        val points = listOf(
            point("2026-05-17 10:00", z = -1.0),
            point("2026-05-19 10:00", z = 0.5),
            point("2026-05-20 10:00", z = 1.5)
        )

        val visible = filterM15PointsForMarketsPeriod(points, Period.OneDay)

        assertEquals(listOf("2026-05-19 10:00", "2026-05-20 10:00"), visible.map { it.tradeDate })
        assertEquals(listOf(0.5, 1.5), visible.map { it.zScore })
    }

    @Test
    fun chartRightPlotPaddingPx_usesTenPercentWhenAboveMinimum() {
        assertEquals(100f, chartRightPlotPaddingPx(1000f), 0.01f)
        assertEquals(16f, chartRightPlotPaddingPx(100f), 0.01f)
    }

    @Test
    fun chartInitialWindowForLastCalendarDays_showsTailMonth() {
        val points = (0 until 100).map { day ->
            val d = java.time.LocalDate.of(2025, 12, 1).plusDays(day.toLong())
            point("${d} 10:00", z = day * 0.01)
        }
        val (width, start) = chartInitialWindowForLastCalendarDays(points, visibleDays = 30L)
        assertTrue(width < 1f)
        assertTrue(start > 0f)
        assertTrue(start + width <= 1.02f)
    }

    @Test
    fun visibleCandleYRange_zoomsVerticalSpan() {
        val (w1, _) = chartInitialWindowForLastCalendarDays(emptyList(), visibleDays = 30L)
        assertEquals(1f, w1, 0.01f)
        val (visMin, visMax) = visibleCandleYRange(0.0, 2.0, yZoom = 2f, viewCenter = 1.0)
        assertEquals(1.0, visMax - visMin, 0.01)
    }

    @Test
    fun filterM15PointsForMarketsPeriod_oneWeek_subsetOfMonth() {
        val points = (0 until 60).map { day ->
            val d = java.time.LocalDate.of(2026, 4, 1).plusDays(day.toLong())
            point("${d} 10:00", z = day * 0.01)
        }
        val week = filterM15PointsForMarketsPeriod(points, Period.OneWeek)
        val month = filterM15PointsForMarketsPeriod(points, Period.OneMonth)
        assertTrue(week.size in 1..month.size)
        assertTrue(month.size <= points.size)
    }

    @Test
    fun buildZScoreMarkersFromStrategyTestTrades_indicesWithinFilteredPoints() {
        val points = listOf(
            point("2026-05-19 10:00", z = -0.9),
            point("2026-05-19 10:15", z = 0.2),
            point("2026-05-20 10:00", z = 0.5)
        )
        val trades = listOf(
            StrategyTestTradeItem(
                trade = PortfolioClosedTrade(
                    direction = ZStrategyPosition.Long,
                    entryDate = "2026-05-19 10:00",
                    exitDate = "2026-05-19 10:15",
                    entrySpreadPercent = 10.0,
                    exitSpreadPercent = 10.4,
                    pnlSpreadPoints = 0.4,
                    grossPnlRubApprox = 100.0,
                    pnlRubApprox = 90.0
                )
            )
        )
        val markers = buildZScoreMarkersFromStrategyTestTrades(points, trades)
        assertTrue(markers.all { it.index in points.indices })
    }

    @Test
    fun chartCandleBodyWidthPx_growsWithFewerVisibleCandles() {
        val wide = chartCandleBodyWidthPx(plotWidthPx = 400f, visibleCandleCount = 10f)
        val zoomed = chartCandleBodyWidthPx(plotWidthPx = 400f, visibleCandleCount = 3f)
        assertTrue(zoomed > wide)
        assertTrue(zoomed > 14f)
    }

    @Test
    fun visibleCandleYRange_allowsViewCenterOutsideData() {
        val (visMin, visMax) = visibleCandleYRange(0.0, 2.0, yZoom = 1f, viewCenter = 5.0)
        assertTrue(visMin > 2.0)
        assertTrue(visMax > 2.0)
    }

    @Test
    fun buildZScoreMarkersFromStrategyTestTrades_assignsTradeNumbers() {
        val points = listOf(
            point("2026-05-19 10:00", z = -0.9),
            point("2026-05-19 10:15", z = 0.2),
            point("2026-05-20 10:00", z = 0.5)
        )
        val trades = listOf(
            StrategyTestTradeItem(
                trade = PortfolioClosedTrade(
                    direction = ZStrategyPosition.Long,
                    entryDate = "2026-05-19 10:00",
                    exitDate = "2026-05-19 10:15",
                    entrySpreadPercent = 10.0,
                    exitSpreadPercent = 10.4,
                    pnlSpreadPoints = 0.4,
                    grossPnlRubApprox = 100.0,
                    pnlRubApprox = 90.0
                )
            )
        )
        val markers = buildZScoreMarkersFromStrategyTestTrades(points, trades)
        assertEquals(2, markers.size)
        assertEquals(listOf("#1", "#1"), markers.map { it.badgeText })
    }

    @Test
    fun strategyMetricsAndZCandlesShareSameM15InputCloses() {
        val points = listOf(
            point("2026-05-19 10:00", z = 0.0, spread = 10.0),
            point("2026-05-19 10:15", z = -0.9, spread = 10.0),
            point("2026-05-19 10:30", z = -0.6, spread = 10.4)
        )
        val candles = buildZScoreCandlesFromM15Points(points)
        val metrics = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null),
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "test"
        )

        assertEquals(points.map { it.zScore }, candles.map { it.close })
        assertEquals(1, metrics?.closedTrades?.size)
    }

    private fun point(label: String, z: Double, spread: Double = 10.0): DataPoint {
        val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
        return DataPoint(
            timestampMillis = ts.atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(),
            tradeDate = label,
            tatnClose = 100.0,
            tatnpClose = 90.0,
            spreadPercent = spread,
            diff = 10.0,
            zScore = z
        )
    }
}
