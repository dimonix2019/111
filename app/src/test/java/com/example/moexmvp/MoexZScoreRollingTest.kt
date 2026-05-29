package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MoexZScoreRollingTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun point(dayOffset: Int, minute: Int, spread: Double): DataPoint {
        val ldt = LocalDateTime.of(2026, 1, 1, 10, 0)
            .plusDays(dayOffset.toLong())
            .plusMinutes(minute * 15L)
        val millis = ldt.atZone(zone).toInstant().toEpochMilli()
        return DataPoint(
            timestampMillis = millis,
            tradeDate = ldt.format(portfolio15mLabelFormatter),
            tatnClose = 100.0,
            tatnpClose = 100.0,
            spreadPercent = spread,
            diff = 0.0,
            zScore = 0.0
        )
    }

    @Test
    fun rolling_causal_pastBarUnchangedWhenFutureAppended() {
        val short = (0 until 11).map { point(it / 96, it % 96, 1.0 + it * 0.01) }
        val full = (0 until 45).map { point(it / 96, it % 96, 1.0 + it * 0.01) }
        val zShort = applyZScoresRolling(short, lookbackDays = 30, minBarsInWindow = 2)
        val zFull = applyZScoresRolling(full, lookbackDays = 30, minBarsInWindow = 2)
        assertEquals(zShort.last().zScore, zFull[10].zScore, 1e-9)
    }

    @Test
    fun rolling_differsFromGlobal_onRegimeShift() {
        val calm = (0 until 200).map { point(it / 96, it % 96, 1.0) }
        val spike = (0 until 8).map { point(200, it, 3.5) }
        val all = calm + spike
        val idx = 100
        val zGlobal = applyZScores(all)[idx].zScore
        val zRoll = applyZScoresRolling(all, lookbackDays = 30, minBarsInWindow = 2)[idx].zScore
        assertTrue(kotlin.math.abs(zGlobal - zRoll) > 0.1)
    }

    @Test
    fun rolling_warmupZerosUntilMinBars() {
        val points = (0 until 10).map { point(0, it, 5.0) }
        val zs = applyZScoresRolling(points, lookbackDays = 30, minBarsInWindow = 20)
        assertTrue(zs.all { it.zScore == 0.0 })
    }

    @Test
    fun defaultMode_isRolling30() {
        val points = (0 until 60).map { point(it / 96, it % 96, 1.0 + (it % 5) * 0.1) }
        val rolling = applyZScoresRolling(points)
        val default = applyZScoresDefault(points)
        assertEquals(
            rolling.map { it.zScore },
            default.map { it.zScore }
        )
    }
}
