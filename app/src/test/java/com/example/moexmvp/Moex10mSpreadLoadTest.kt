package com.example.moexmvp

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Moex10mSpreadLoadTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun aligned10mSpreadEntities_keepsNative10mTimestamps() {
        val tatn = listOf(
            bar("2026-05-19 10:00", 100.0),
            bar("2026-05-19 10:10", 100.1),
            bar("2026-05-19 10:20", 100.2),
        )
        val tatnp = listOf(
            bar("2026-05-19 10:00", 95.0),
            bar("2026-05-19 10:10", 95.1),
            bar("2026-05-19 10:20", 95.2),
        )
        val entities = aligned10mSpreadEntities(tatn, tatnp)
        assertEquals(3, entities.size)
        assertEquals(listOf(0, 10, 20), entities.map {
            LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(it.tsMillis),
                zone,
            ).minute
        })
    }

    @Test
    fun aligned10mSpreadEntities_moreBarsThan15mAggregation() {
        val tatn = (0 until 6).map { i ->
            bar("2026-05-19 10:${(i * 10).toString().padStart(2, '0')}", 100.0 + i * 0.1)
        }
        val tatnp = tatn.map { it.copy(close = it.close - 5.0) }
        val native10 = aligned10mSpreadEntities(tatn, tatnp)
        val agg15 = aggregateTo15MinuteBars(tatn).associateBy { it.timestamp }
        val agg15np = aggregateTo15MinuteBars(tatnp).associateBy { it.timestamp }
        val times15 = agg15.keys.intersect(agg15np.keys)
        assertTrue(native10.size > times15.size)
    }

    @Test
    fun buildZScoreCandlesFromPoints_noIntrabarWicksOn10mSeries() {
        val points = (0 until 12).map { i ->
            val ts = LocalDateTime.of(2026, 5, 19, 10, 0).plusMinutes(i * 10L)
            DataPoint(
                timestampMillis = ts.atZone(zone).toInstant().toEpochMilli(),
                tradeDate = ts.format(portfolio15mLabelFormatter),
                tatnClose = 100.0,
                tatnpClose = 95.0,
                spreadPercent = 5.0 + i * 0.01,
                diff = 5.0,
                zScore = i * 0.05,
            )
        }
        val candles = buildZScoreCandlesFromM15Points(points)
        candles.forEach { c ->
            assertEquals(maxOf(c.open, c.close), c.high, 1e-9)
            assertEquals(minOf(c.open, c.close), c.low, 1e-9)
        }
    }

    private fun bar(label: String, close: Double) = CandleBar(
        timestamp = LocalDateTime.parse(label, portfolio15mLabelFormatter),
        open = close,
        high = close,
        low = close,
        close = close,
    )
}
