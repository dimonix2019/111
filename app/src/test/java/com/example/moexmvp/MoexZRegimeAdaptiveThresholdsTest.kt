package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MoexZRegimeAdaptiveThresholdsTest {

    @Test
    fun regimeMapsToTightOrWideFour() {
        val cfg = ZRegimeAdaptiveThresholdConfig()
        assertEquals(cfg.tightFour, zRegimeToAdaptiveFour(ZConcentrationRegime.NegativeBand, cfg))
        assertEquals(cfg.tightFour, zRegimeToAdaptiveFour(ZConcentrationRegime.PositiveBand, cfg))
        assertEquals(cfg.wideFour, zRegimeToAdaptiveFour(ZConcentrationRegime.Neutral, cfg))
        assertEquals(cfg.wideFour, zRegimeToAdaptiveFour(ZConcentrationRegime.Dispersed, cfg))
    }

    @Test
    fun adaptiveSeries_noLookAhead_usesWalkForwardWindowPlan() {
        val days = (0 until 80).map { LocalDate.of(2026, 1, 5).plusDays(it.toLong()) }
        val points = days.flatMapIndexed { di, day ->
            val regimeZ = when {
                di < 30 -> -0.8
                di < 50 -> 0.9
                else -> 0.05
            }
            (0 until 4).map { slot ->
                val minute = slot * 15
                DataPoint(
                    timestampMillis = di * 86_400_000L + slot * 900_000L,
                    tradeDate = String.format("%04d-%02d-%02d %02d:%02d", day.year, day.monthValue, day.dayOfMonth, 10 + minute / 60, minute % 60),
                    tatnClose = 500.0,
                    tatnpClose = 490.0,
                    spreadPercent = 2.0,
                    diff = 10.0,
                    zScore = regimeZ + slot * 0.01,
                )
            }
        }
        val cfg = ZRegimeAdaptiveThresholdConfig(windowTradingDays = 10, minTrainWindows = 3)
        val plans = buildZRegimeAdaptiveWindowPlans(points, cfg)!!
        assertTrue(plans.size >= 5)
        val trained = plans.filter { it.predictedRegime != null }
        assertTrue(trained.isNotEmpty())
        for (plan in trained) {
            assertEquals(
                zRegimeToAdaptiveFour(plan.predictedRegime!!, cfg),
                plan.appliedFour,
            )
        }

        val series = buildZRegimeAdaptiveFourThresholdSeries(points, cfg)!!
        assertEquals(points.size, series.size)
        assertTrue(series.all { it.isValid() })

        val dayToWin = buildDayToZRegimeWindowIndex(plans)
        val midBar = points[points.size / 2]
        val day = parseBarLocalDateMsk(midBar.tradeDate)!!
        val winIdx = dayToWin[day]!!
        assertEquals(plans[winIdx].appliedFour, series[points.indexOf(midBar)])
    }

    @Test
    fun adaptiveSeries_containsBothTightAndWideFromWindowPlans() {
        val points = buildSyntheticRegimePoints(
            negativeDays = 25,
            positiveDays = 25,
            neutralDays = 25,
        )
        val cfg = ZRegimeAdaptiveThresholdConfig(windowTradingDays = 10, minTrainWindows = 2)
        val plans = buildZRegimeAdaptiveWindowPlans(points, cfg)!!
        val applied = plans.map { it.appliedFour.entryLong }.toSet()
        assertTrue(applied.contains(cfg.tightFour.entryLong))
        assertTrue(applied.contains(cfg.wideFour.entryLong))
        val series = buildZRegimeAdaptiveFourThresholdSeries(points, cfg)!!
        assertTrue(series.all { it.isValid() })
    }

    private fun buildSyntheticRegimePoints(
        negativeDays: Int,
        positiveDays: Int,
        neutralDays: Int,
    ): List<DataPoint> {
        val start = LocalDate.of(2026, 1, 6)
        fun dayPoints(offset: Int, z: Double) = (0 until 6).map { slot ->
            val day = start.plusDays(offset.toLong())
            DataPoint(
                timestampMillis = offset * 86_400_000L + slot * 900_000L,
                tradeDate = String.format("%04d-%02d-%02d 10:%02d", day.year, day.monthValue, day.dayOfMonth, slot * 15),
                tatnClose = 500.0,
                tatnpClose = 490.0,
                spreadPercent = 2.0,
                diff = 10.0,
                zScore = z,
            )
        }
        val all = mutableListOf<DataPoint>()
        var off = 0
        repeat(negativeDays) { all += dayPoints(off++, -0.9) }
        repeat(positiveDays) { all += dayPoints(off++, 0.9) }
        repeat(neutralDays) { all += dayPoints(off++, 0.05) }
        return all
    }
}
