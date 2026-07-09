package com.example.moexmvp.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DesktopBarReplayEngineTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5)

    @Test
    fun stepForward_matchesFullCollect() {
        val points = sampleSeries(120)
        val (fullEdges, _) = collectZStrategy15mSignalEdgesFull(points, thresholds = thresholds)
        val engine = BarReplayEngine(
            ReplayConfig(points = points, thresholds = thresholds, startIndex = 0),
        )
        val stepped = mutableListOf<ZStrategy15mSignalEdge>()
        while (true) {
            val frame = engine.stepForward() ?: break
            frame.newSignalThisBar?.let { stepped += it }
        }
        assertEquals(
            fullEdges.map { it.signal to it.bar.timestampMillis },
            stepped.map { it.signal to it.bar.timestampMillis },
        )
    }

    @Test
    fun barReplayDelayMs_scales() {
        assertEquals(900L, barReplayDelayMs(1f))
        assertEquals(90L, barReplayDelayMs(10f))
    }

    private fun sampleSeries(n: Int): List<DataPoint> {
        val start = LocalDateTime.of(2025, 3, 3, 10, 0)
        return (0 until n).map { i ->
            val z = when {
                i < 50 -> 0.0
                i == 50 -> 0.8
                i < 70 -> 0.9
                i == 70 -> 0.4
                else -> 0.0
            }
            val ts = start.plusMinutes(15L * i)
            val millis = ts.atZone(zone).toInstant().toEpochMilli()
            val label = ts.format(portfolio15mLabelFormatter)
            DataPoint(millis, label, 100.0, 99.0, 1.0, 1.0, z)
        }
    }
}
