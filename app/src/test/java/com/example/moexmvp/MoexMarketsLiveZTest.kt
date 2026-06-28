package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MoexMarketsLiveZTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun point(ts: Long, spread: Double, z: Double = 0.0) = DataPoint(
        timestampMillis = ts,
        tradeDate = "x",
        tatnClose = 650.0,
        tatnpClose = 600.0,
        spreadPercent = spread,
        diff = 50.0,
        zScore = z,
    )

    @Test
    fun spreadPercentFromPairCloses_matchesM15Formula() {
        assertEquals(8.333333333333334, spreadPercentFromPairCloses(650.0, 600.0)!!, 1e-9)
    }

    @Test
    fun buildM15PointsWithLiveFormingFrom1m_appendsFormingBarAndZ() {
        val step = 15 * 60_000L
        val bucket = currentM15BucketStartMillis()
        val ts0 = bucket - 80 * step
        val history = (0 until 80).map { i ->
            point(ts0 + i * step, 7.0 + i * 0.01, z = 0.1)
        }
        val patched = buildM15PointsWithLiveFormingFrom1m(history, tatnClose = 680.0, tatnpClose = 600.0)!!
        assertEquals(history.size + 1, patched.size)
        assertTrue(patched.last().timestampMillis >= bucket)
        assertNotEquals(0.0, patched.last().zScore, 1e-9)
    }

    @Test
    fun liveZScoreFromIntraday1m_recomputesFormingBar() {
        val zone = ZoneId.of("Europe/Moscow")
        val now = LocalDate.of(2026, 6, 26).atTime(10, 15).atZone(zone)
        val step = 15 * 60_000L
        val bucket = currentM15BucketStartMillis(now.toInstant(), zone)
        val history = (0 until 80).map { i ->
            point(bucket - (80 - i) * step, 7.0 + i * 0.01, z = 0.1)
        }
        val snap = MarketsIntraday1mSnapshot(
            tatn = listOf(CandlePoint("10:00", 680.0, 681.0, 679.0, 680.0)),
            tatnp = listOf(CandlePoint("10:00", 600.0, 601.0, 599.0, 600.0)),
            tatnLastBarMillis = bucket,
            tatnpLastBarMillis = bucket,
        )
        val z = resolveUnifiedLiveZSnapshot(history, snap, now = now).zScore
        requireNotNull(z)
        assertNotEquals(0.1, z, 1e-9)
    }

    @Test
    fun m15PointsWithLiveFormingFromIntraday1m_fallsBackWhenSnapEmpty() {
        val history = listOf(point(0L, 7.0, z = 0.2))
        val snap = MarketsIntraday1mSnapshot(
            tatn = emptyList(),
            tatnp = emptyList(),
            tatnLastBarMillis = 0L,
            tatnpLastBarMillis = 0L,
        )
        assertEquals(history, m15PointsWithLiveFormingFromIntraday1m(history, snap))
    }

    @Test
    fun buildIntraday1mZScoreSeries_replaysMinuteSpreads() {
        val step = 15 * 60_000L
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val history = (0 until 80).map { i ->
            point(todayStart - (80 - i) * step, 7.0 + i * 0.01, z = 0.1)
        }
        val aligned = AlignedIntraday1mQuotes(
            labels = listOf("10:00", "10:01", "10:02"),
            tatnCloses = listOf(650.0, 651.0, 652.0),
            tatnpCloses = listOf(600.0, 600.5, 601.0),
        )
        val series = buildIntraday1mZScoreSeries(history, aligned, zone = zone)
        requireNotNull(series)
        assertEquals(3, series.labels.size)
        assertEquals(3, series.zScores.size)
        assertTrue(series.zScores.all { it.isFinite() })
    }

    @Test
    fun intraday1mLabelToM15BucketMs_floorsToQuarterHour() {
        val zone = ZoneId.of("Europe/Moscow")
        val ms10 = intraday1mLabelToM15BucketMs("10:07", zone)
        val ms14 = intraday1mLabelToM15BucketMs("10:14", zone)
        assertEquals(ms10, ms14)
    }

    @Test
    fun resolveUnifiedLiveZSnapshot_before0700_ignoresLive1m() {
        val step = 15 * 60_000L
        val day = LocalDate.of(2026, 6, 26)
        val zone = ZoneId.of("Europe/Moscow")
        val closedTs = day.minusDays(1).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val formingTs = day.atTime(6, 45).atZone(zone).toInstant().toEpochMilli()
        val history = (0 until 78).map { i ->
            point(closedTs - (78 - i) * step, 7.0 + i * 0.01, z = -0.5)
        } + listOf(
            point(closedTs, 8.33, z = -1.68),
            point(formingTs, 9.99, z = 0.77),
        )
        val snap = MarketsIntraday1mSnapshot(
            tatn = listOf(CandlePoint("06:50", 680.0, 681.0, 679.0, 680.0)),
            tatnp = listOf(CandlePoint("06:50", 600.0, 601.0, 599.0, 600.0)),
            tatnLastBarMillis = formingTs,
            tatnpLastBarMillis = formingTs,
        )
        val morning = day.atTime(6, 50).atZone(zone)
        val unified = resolveUnifiedLiveZSnapshot(history, snap, now = morning)
        val legacyLive = buildM15PointsWithLiveFormingFrom1m(
            history,
            tatnClose = 680.0,
            tatnpClose = 600.0,
        )!!.last().zScore
        val closedZ = rollingZForClosedM15Bar(history, morning)
        assertFalse(unified.liveFrom1m)
        assertNotEquals(legacyLive, unified.zScore)
        assertEquals(closedZ, unified.zScore)
    }

    @Test
    fun resolveUnifiedLiveZSnapshot_summaryMatchesShutterPath() {
        val zone = ZoneId.of("Europe/Moscow")
        val now = LocalDate.of(2026, 6, 26).atTime(10, 15).atZone(zone)
        val step = 15 * 60_000L
        val bucket = currentM15BucketStartMillis(now.toInstant(), zone)
        val history = (0 until 80).map { i ->
            point(bucket - (80 - i) * step, 7.0 + i * 0.01, z = 0.1)
        }
        val snap = MarketsIntraday1mSnapshot(
            tatn = listOf(CandlePoint("10:00", 680.0, 681.0, 679.0, 680.0)),
            tatnp = listOf(CandlePoint("10:00", 600.0, 601.0, 599.0, 600.0)),
            tatnLastBarMillis = bucket,
            tatnpLastBarMillis = bucket,
        )
        val a = resolveUnifiedLiveZSnapshot(history, snap, now = now)
        val b = resolveUnifiedLiveZSnapshot(history, snap, now = now)
        assertEquals(a.zScore, b.zScore)
        assertEquals(a.spreadPercent, b.spreadPercent)
        assertEquals(a.monitorPoints.last().zScore, b.monitorPoints.last().zScore)
        assertEquals(a.liveFrom1m, b.liveFrom1m)
    }

    @Test
    fun applyLiveZToM15ChartSeries_patchesLastCandleClose() {
        val pts = listOf(
            point(0L, 7.0, z = 0.2),
            point(15 * 60_000L, 7.1, z = 0.5),
        )
        val candles = listOf(
            CandlePoint("a", 0.0, 0.2, 0.0, 0.2),
            CandlePoint("b", 0.2, 0.5, 0.2, 0.5),
        )
        val (outPts, outCandles) = applyLiveZToM15ChartSeries(pts, candles, liveZ = 0.91)
        assertEquals(0.91, outPts.last().zScore, 1e-9)
        assertEquals(0.91, outCandles.last().close, 1e-9)
    }
}
