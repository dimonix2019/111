package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MoexPortfolioM15ZPersistTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun entity(ts: Long, spread: Double, z: Double? = null) = PortfolioM15SpreadEntity(
        tsMillis = ts,
        tatnClose = 650.0,
        tatnpClose = 600.0,
        spreadPercent = spread,
        diff = 50.0,
        persistedZScore = z,
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
    fun ensureM15PointsZScoresInPlace_skipsWhenAllPersisted() {
        val ts0 = java.time.LocalDate.of(2026, 1, 1).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val ts1 = ts0 + 15 * 60_000L
        val entities = listOf(
            entity(ts0, 8.0, z = 0.1),
            entity(ts1, 8.1, z = 0.2),
        )
        val points = entities.map { it.toDataPoint() }.toMutableList()
        val before = points.map { it.zScore }
        assertFalse(ensureM15PointsZScoresInPlace(points, entities))
        assertEquals(before, points.map { it.zScore })
    }

    @Test
    fun ensureM15PointsZScoresInPlace_recalcsWhenAnyMissing() {
        val zoneId = zone
        val entities = (0 until 60).map { i ->
            val date = java.time.LocalDate.of(2026, 1, 1).plusDays(i.toLong())
            val ts = date.atTime(10, 0).atZone(zoneId).toInstant().toEpochMilli()
            entity(ts, spread = 8.0 + i * 0.01, z = if (i == 59) null else 0.0)
        }
        val points = entities.map { it.toDataPoint() }.toMutableList()
        assertTrue(ensureM15PointsZScoresInPlace(points, entities))
        val reference = applyZScoresDefault(entities.map { it.copy(persistedZScore = null).toDataPoint() })
        assertEquals(reference.last().zScore, points.last().zScore, 1e-9)
    }

    @Test
    fun persistM15ZScoreSnapshots_updatesFromFirstMissingOnly() {
        val ts0 = java.time.LocalDate.of(2026, 6, 10).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val ts1 = ts0 + 15 * 60_000L
        val ts2 = ts1 + 15 * 60_000L
        val entities = listOf(
            entity(ts0, 8.0, z = 0.11),
            entity(ts1, 8.1, z = null),
            entity(ts2, 8.2, z = null),
        )
        val points = listOf(
            entities[0].toDataPoint(),
            entities[1].toDataPoint().copy(zScore = 0.55),
            entities[2].toDataPoint().copy(zScore = 0.66),
        )
        val firstStale = entities.indexOfFirst { it.persistedZScore == null }
        assertEquals(1, firstStale)
        val updated = (firstStale until entities.size).map { i ->
            entities[i].copy(persistedZScore = points[i].zScore)
        }
        assertEquals(2, updated.size)
        assertEquals(0.55, updated[0].persistedZScore!!, 1e-9)
        assertEquals(0.66, updated[1].persistedZScore!!, 1e-9)
        assertEquals(0.11, entities[0].persistedZScore!!, 1e-9)
    }
}
