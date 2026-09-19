package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MoexStrategyTestProfitTakeTest {

    @Test
    fun forcedProfitTake_changesPnlVsBaselineOnVolatileSeries() {
        val points = (0 until 80).map { i ->
            dp("", kotlin.math.sin(i / 7.0) * 1.3, 4.8 + kotlin.math.cos(i / 5.0) * 0.6, i)
        }
        val th = DynamicThresholds(0.7, 0.5, null)
        val base = runSim(points, th, ZStrategySimOptions())!!
        val tp3 = runSim(points, th, ZStrategySimOptions(forcedProfitTakePercent = 3.0))!!
        assertTrue(base.closedTrades.isNotEmpty())
        assertTrue(tp3.closedTrades.isNotEmpty())
    }

    @Test
    fun buildStrategyTestProfitTakeCompare_includesBaselineAndThreeLevels() {
        val points = (0 until 120).map { i ->
            dp("", kotlin.math.sin(i / 9.0) * 1.2, 4.5 + kotlin.math.sin(i / 11.0) * 0.4, i)
        }
        val simPoints = prepareM15PointsForZStrategySim(points)
        val rows = buildStrategyTestProfitTakeCompare(
            simPoints = simPoints,
            thresholds = DynamicThresholds(0.7, 0.5, null),
            accountSizeRub = 100_000.0,
            capitalUsagePercent = 80.0,
            leverageForLots = 7.0,
            commissionPercentPerSide = 0.04,
            compoundReturns = false,
            baseSimOptions = ZStrategySimOptions(),
        )!!
        assertEquals(4, rows.size)
        assertEquals(null, rows.first().profitTakePercent)
        assertEquals(listOf(2.0, 3.0, 5.0), rows.drop(1).map { it.profitTakePercent })
    }

    private fun runSim(
        points: List<DataPoint>,
        thresholds: DynamicThresholds,
        simOptions: ZStrategySimOptions,
    ): PortfolioMetrics? =
        buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 10_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "tp-test",
            compoundReturns = false,
            exitMode = ZStrategyExitMode.FixedThreshold,
            simOptions = simOptions,
        )

    private fun dp(label: String, z: Double, spread: Double, index: Int): DataPoint {
        val baseMs = 1_700_000_000_000L
        val ts = baseMs + index * 900_000L
        val dt = java.time.Instant.ofEpochMilli(ts).atZone(moexZoneId).toLocalDateTime()
        val autoLabel = String.format(
            Locale.US,
            "%04d-%02d-%02d %02d:%02d",
            dt.year,
            dt.monthValue,
            dt.dayOfMonth,
            dt.hour,
            dt.minute,
        )
        return DataPoint(
            timestampMillis = ts,
            tradeDate = label.ifBlank { autoLabel },
            tatnClose = 650.0,
            tatnpClose = 620.0,
            spreadPercent = spread,
            diff = 30.0,
            zScore = z,
        )
    }
}
