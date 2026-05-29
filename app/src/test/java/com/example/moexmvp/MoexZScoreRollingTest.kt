package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MoexZScoreRollingTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    @Test
    fun applyZScoresRolling_causal_pastBarUnchangedWhenFutureAppended() {
        val base = buildDailySpreadSeries(days = 45, spreadAtDay = { 1.0 + it * 0.01 })
        val zAt10 = applyZScoresRolling(base.take(11), lookbackDays = 30L, minBarsInWindow = 2)
            .last()
            .zScore
        val extended = applyZScoresRolling(base, lookbackDays = 30L, minBarsInWindow = 2)
        val zAt10Extended = extended[10].zScore
        assertEquals(zAt10, zAt10Extended, 1e-9)
    }

    @Test
    fun applyZScoresRolling_differsFromGlobalOnRegimeShift() {
        val calm = (0 until 200).map { spreadPoint(it, spread = 1.0) }
        val wide = (200 until 250).map { spreadPoint(it, spread = 2.5) }
        val points = calm + wide
        val globalLast = applyZScores(points).last().zScore
        val rollingLast = applyZScoresRolling(points, lookbackDays = 30L, minBarsInWindow = 2).last().zScore
        assertNotEquals(globalLast, rollingLast, 1e-6)
    }

    @Test
    fun applyZScoresRolling_warmupZerosUntilMinBars() {
        val points = (0 until 10).map { spreadPoint(it, spread = 5.0) }
        val z = applyZScoresRolling(points, lookbackDays = 30L, minBarsInWindow = 20)
        assertTrue(z.all { it.zScore == 0.0 })
    }

    private fun buildDailySpreadSeries(
        days: Int,
        spreadAtDay: (Int) -> Double
    ): List<DataPoint> {
        return (0 until days).map { day ->
            val dt = LocalDateTime.of(2026, 1, 1, 12, 0).plusDays(day.toLong())
            DataPoint(
                timestampMillis = dt.atZone(zone).toInstant().toEpochMilli(),
                tradeDate = dt.format(portfolio15mLabelFormatter),
                tatnClose = 100.0,
                tatnpClose = 100.0,
                spreadPercent = spreadAtDay(day),
                diff = 0.0,
                zScore = 0.0
            )
        }
    }

    private fun spreadPoint(index: Int, spread: Double): DataPoint {
        val dt = LocalDateTime.of(2026, 1, 1, 10, 0).plusMinutes(index * 15L)
        return DataPoint(
            timestampMillis = dt.atZone(zone).toInstant().toEpochMilli(),
            tradeDate = dt.format(portfolio15mLabelFormatter),
            tatnClose = 100.0,
            tatnpClose = 100.0,
            spreadPercent = spread,
            diff = 0.0,
            zScore = 0.0
        )
    }
}
