package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MoexPortfolioM15ZPersistTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)

    private fun entity(ts: Long, spread: Double, z: Double? = null, snap: Double? = null) =
        PortfolioM15SpreadEntity(
            tsMillis = ts,
            tatnClose = 650.0,
            tatnpClose = 600.0,
            spreadPercent = spread,
            diff = 50.0,
            persistedZScore = z,
            spreadAtZSnapshot = snap,
        )

    @Test
    fun toDataPointWithPersistedZ_usesSnapshotWhenPresent() {
        val ts = java.time.LocalDate.of(2026, 6, 10)
            .atTime(10, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val point = entity(ts, spread = 8.0, z = 0.72).toDataPointWithPersistedZ()
        assertEquals(0.72, point.zScore, 1e-9)
    }

    @Test
    fun fillM15ZScoresInPlace_skipsWhenAllPersisted() {
        val ts0 = java.time.LocalDate.of(2026, 1, 1).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val ts1 = ts0 + 15 * 60_000L
        val entities = listOf(
            entity(ts0, 8.0, z = 0.1),
            entity(ts1, 8.1, z = 0.2),
        )
        val points = entities.map { it.toDataPoint() }.toMutableList()
        val before = points.map { it.zScore }
        assertFalse(fillM15ZScoresInPlace(points, entities))
        assertEquals(before, points.map { it.zScore })
    }

    @Test
    fun fillM15ZScoresInPlace_recalcsFormingBarDespitePersistedZ() {
        val step = 15 * 60_000L
        val bucket = currentM15BucketStartMillis()
        val ts0 = bucket - 81 * step
        val entities = (0 until 82).map { i ->
            val ts = ts0 + i * step
            entity(ts, spread = 7.0 + i * 0.001, z = if (i < 81) 0.5 else 0.99)
        }
        val points = entities.map { it.toDataPoint() }.toMutableList()
        val lastIdx = points.lastIndex
        points[lastIdx] = points[lastIdx].copy(spreadPercent = 8.5)
        assertTrue(isM15FormingBarIndex(points, lastIdx))
        assertTrue(fillM15ZScoresInPlace(points, entities))
        assertNotEquals(0.99, points[lastIdx].zScore, 1e-9)
    }

    @Test
    fun applySpreadGuardZScoresInPlace_dampensSingleBarSpike() {
        val ts0 = java.time.LocalDate.of(2026, 6, 10).atTime(17, 45).atZone(zone).toInstant().toEpochMilli()
        val ts1 = ts0 + 15 * 60_000L
        val step = 15 * 60_000L
        val points = (0 until 82).map { i ->
            val ts = ts1 - (81 - i) * step
            val spread = when (ts) {
                ts0 -> 6.74
                ts1 -> 7.28
                else -> 7.0 + (i % 5) * 0.01
            }
            DataPoint(
                timestampMillis = ts,
                tradeDate = if (ts == ts0) "2026-06-10 17:45" else if (ts == ts1) "2026-06-10 18:00" else "x",
                tatnClose = 650.0,
                tatnpClose = 600.0,
                spreadPercent = spread,
                diff = 50.0,
                zScore = 0.0,
            )
        }.toMutableList()
        applySpreadGuardZScoresInPlace(points)
        val bar1745 = points[points.size - 2]
        val bar1800 = points.last()
        val z1800 = bar1800.zScore
        val naive = applyZScoresDefault(points.map { it.copy(zScore = 0.0) }).last().zScore
        val sig = determineZStrategySignalBetweenBars(
            bar1745,
            bar1800,
            ZStrategyPosition.Flat,
            DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null),
        )
        assertTrue("naive Z should cross entry: $naive", naive >= 0.7)
        assertTrue("guarded Z should stay below entry: $z1800", z1800 < 0.7)
        assertEquals(ZStrategySignal.None, sig)
        val raw1745 = applyZScoresDefault(points.map { it.copy(zScore = 0.0) })[points.size - 2].zScore
        assertEquals("Z before spike bar must not shift", raw1745, bar1745.zScore, 1e-9)
    }

    @Test
    fun applyJournalObservedZOverlay_fixesPrevBarForEnterShortCrossing() {
        val ts0 = java.time.LocalDate.of(2026, 6, 10).atTime(6, 30).atZone(zone).toInstant().toEpochMilli()
        val ts1 = ts0 + 15 * 60_000L
        val points = mutableListOf(
            DataPoint(ts0, "2026-06-10 06:30", 650.0, 600.0, 7.0, 50.0, 0.85),
            DataPoint(ts1, "2026-06-10 06:45", 650.0, 600.0, 7.1, 50.0, 0.40),
        )
        val events = listOf(
            StrategySignalEvent(
                timestampMillis = ts1,
                signalType = StrategySignalType.EnterShort,
                zScore = 1.145,
            ),
        )
        applyJournalObservedZOverlay(points, events, thresholds)
        val sig = determineZStrategySignalBetweenBars(
            points[0],
            points[1],
            ZStrategyPosition.Flat,
            thresholds,
        )
        assertEquals(ZStrategySignal.EnterShort, sig)
        assertTrue(points[0].zScore < thresholds.entry)
        assertEquals(1.145, points[1].zScore, 1e-9)
    }

    @Test
    fun applyJournalObservedZOverlay_insertsSyntheticBarOnSessionGap() {
        val zone = java.time.ZoneId.of("Europe/Moscow")
        val tsPrev = java.time.LocalDate.of(2026, 6, 9).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val tsCur = java.time.LocalDate.of(2026, 6, 10).atTime(6, 45).atZone(zone).toInstant().toEpochMilli()
        val points = mutableListOf(
            DataPoint(tsPrev, "2026-06-09 23:30", 650.0, 600.0, 7.0, 50.0, 0.37),
            DataPoint(tsCur, "2026-06-10 06:45", 650.0, 600.0, 7.1, 50.0, 1.14),
        )
        val events = listOf(
            StrategySignalEvent(
                timestampMillis = tsCur,
                signalType = StrategySignalType.EnterShort,
                zScore = 1.145,
            ),
        )
        applyJournalObservedZOverlay(points, events, thresholds)
        assertEquals(3, points.size)
        assertEquals("2026-06-10 06:30", points[1].tradeDate)
        val sig = determineZStrategySignalBetweenBars(
            points[1],
            points[2],
            ZStrategyPosition.Flat,
            thresholds,
        )
        assertEquals(ZStrategySignal.EnterShort, sig)
    }
}
