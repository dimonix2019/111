package com.example.moexmvp

import org.junit.Assert.assertTrue
import org.junit.Test

class MoexClusterDynamicThresholdTest {

    @Test
    fun clusterSeries_noLookAhead_usesOnlyPastBars() {
        val points = (0 until 80).map { i ->
            DataPoint(
                timestampMillis = i * 900_000L,
                tradeDate = "2026-01-01 10:${String.format("%02d", i % 60)}",
                tatnClose = 500.0,
                tatnpClose = 490.0,
                spreadPercent = 2.0,
                diff = 10.0,
                zScore = if (i < 60) 0.1 else 2.0,
            )
        }
        val config = ZClusterThresholdConfig(
            lookbackBars = 20,
            minWindowBars = 10,
            entryPctShort = 85,
            exitPctShort = 55,
            entryPctLong = 15,
            exitPctLong = 45,
            fallback = ZStrategyFourThresholds(0.7, 0.5, 0.7, 0.5),
        )
        val series = buildClusterDynamicFourThresholdSeries(points, config)
        assertTrue(series[59].entryShort < series[79].entryShort)
    }

    @Test
    fun clusterSeries_validFourAtEachBar() {
        val points = (0 until 100).map { i ->
            DataPoint(
                timestampMillis = i * 900_000L,
                tradeDate = "bar$i",
                tatnClose = 500.0,
                tatnpClose = 490.0,
                spreadPercent = 2.0 + kotlin.math.sin(i / 5.0) * 0.5,
                diff = 10.0,
                zScore = kotlin.math.sin(i / 7.0) * 1.5,
            )
        }
        val series = buildClusterDynamicFourThresholdSeries(points, ZClusterThresholdConfig())
        assertTrue(series.all { it.isValid() })
    }
}
