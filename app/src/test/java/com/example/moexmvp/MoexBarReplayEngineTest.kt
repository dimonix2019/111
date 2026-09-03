package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MoexBarReplayEngineTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)

    @Test
    fun stepForward_advancesCursorAndClipsVisiblePoints() {
        val points = sampleFlatThenCross(n = 60)
        val engine = BarReplayEngine(
            ReplayConfig(points = points, thresholds = thresholds, startIndex = 48),
        )
        assertEquals(48, engine.cursor)
        assertEquals(ReplayState.Idle, engine.state)
        val frame0 = engine.frameAtCursor()
        assertEquals(49, frame0.visiblePoints.size)
        assertEquals(points[48].tradeDate, frame0.barLabel)

        val next = engine.stepForward()
        assertNotNull(next)
        assertEquals(49, engine.cursor)
        assertEquals(50, next!!.visiblePoints.size)
        assertEquals(points.subList(0, 50), next.visiblePoints)
    }

    @Test
    fun seekTo_and_stepBackward_pausePlaying() {
        val points = sampleFlatThenCross(n = 80)
        val engine = BarReplayEngine(
            ReplayConfig(points = points, thresholds = thresholds, startIndex = 48),
        )
        engine.play()
        assertEquals(ReplayState.Playing, engine.state)
        engine.seekTo(60)
        assertEquals(60, engine.cursor)
        engine.play()
        engine.stepBackward()
        assertEquals(ReplayState.Paused, engine.state)
        assertEquals(59, engine.cursor)
    }

    @Test
    fun stepForward_atEnd_returnsNullAndIdle() {
        val points = sampleFlatThenCross(n = 55)
        val engine = BarReplayEngine(
            ReplayConfig(points = points, thresholds = thresholds, startIndex = 48),
        )
        engine.seekTo(points.lastIndex)
        assertNull(engine.stepForward())
        assertEquals(ReplayState.Idle, engine.state)
    }

    @Test
    fun barReplayDelayMs_scalesWithSpeed() {
        assertEquals(900L, barReplayDelayMs(1f))
        assertEquals(1800L, barReplayDelayMs(0.5f))
        assertEquals(90L, barReplayDelayMs(10f))
        assertEquals(50L, barReplayDelayMs(100f))
    }

    @Test
    fun visibleIndexRange_clipsToAbout30Days() {
        // >40 календарных дней непрерывных 15м баров, чтобы окно 30д обрезало хвост.
        val start = LocalDateTime.of(2025, 1, 1, 10, 0)
        val points = (0 until 4000).map { i ->
            val ts = start.plusMinutes(15L * i)
            pointAt(ts, z = 0.0)
        }
        val cursor = points.lastIndex
        val range = barReplayVisibleIndexRange(points, cursor, visibleDays = 30L)
        assertFalse(range.isEmpty())
        assertEquals(cursor, range.last)
        val days = java.time.Duration.between(
            java.time.Instant.ofEpochMilli(points[range.first].timestampMillis),
            java.time.Instant.ofEpochMilli(points[range.last].timestampMillis),
        ).toDays()
        assertTrue("window days=$days", days in 29..31)
        assertTrue(range.first > 0)
        assertTrue(range.first < cursor)
    }

    @Test
    fun stepForward_signals_match_collectZStrategy15mSignalEdgesFull() {
        val points = sampleFlatThenCross(n = 120)
        val (fullEdges, _) = collectZStrategy15mSignalEdgesFull(points, thresholds = thresholds)
        val engine = BarReplayEngine(
            ReplayConfig(points = points, thresholds = thresholds, startIndex = 0),
        )
        val stepped = mutableListOf<ZStrategy15mSignalEdge>()
        while (true) {
            val frame = engine.stepForward() ?: break
            frame.newSignalThisBar?.let { stepped += it }
        }
        assertEquals(fullEdges.map { it.signal to it.bar.timestampMillis }, stepped.map { it.signal to it.bar.timestampMillis })
        assertEquals(fullEdges.size, engine.frameAtCursor().signalEdgesSoFar.size)
    }

    @Test
    fun seekTo_rebuildsSameEdgesAsFullPrefix() {
        val points = sampleFlatThenCross(n = 100)
        val engine = BarReplayEngine(
            ReplayConfig(points = points, thresholds = thresholds, startIndex = 0),
        )
        engine.seekTo(80)
        val (prefixEdges, _) = collectZStrategy15mSignalEdgesFull(
            points = points.subList(0, 81),
            thresholds = thresholds,
        )
        assertEquals(
            prefixEdges.map { it.signal to it.bar.timestampMillis },
            engine.frameAtCursor().signalEdgesSoFar.map { it.signal to it.bar.timestampMillis },
        )
    }

    @Test
    fun formatReplaySpeed_labels() {
        assertEquals("0.5×", formatReplaySpeed(0.5f))
        assertEquals("1×", formatReplaySpeed(1f))
        assertEquals("10×", formatReplaySpeed(10f))
    }

    private fun sampleFlatThenCross(n: Int): List<DataPoint> {
        val start = LocalDateTime.of(2025, 3, 3, 10, 0)
        return (0 until n).map { i ->
            val z = when {
                i < 50 -> 0.0
                i == 50 -> 0.8
                i < 70 -> 0.9
                i == 70 -> 0.4
                else -> 0.0
            }
            pointAt(start.plusMinutes(15L * i), z)
        }
    }

    private fun pointAt(ts: LocalDateTime, z: Double): DataPoint {
        val millis = ts.atZone(zone).toInstant().toEpochMilli()
        val label = ts.format(portfolio15mLabelFormatter)
        return DataPoint(
            timestampMillis = millis,
            tradeDate = label,
            tatnClose = 100.0,
            tatnpClose = 99.0,
            spreadPercent = 1.0,
            diff = 1.0,
            zScore = z,
        )
    }
}
