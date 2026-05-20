package com.example.moexmvp

import org.junit.Assert.assertEquals
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
