package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MoexMarketsCacheTest {

    private fun sampleSuccess(): UiState.Success {
        val p = DataPoint(
            timestampMillis = 1_700_000_000_000L,
            tradeDate = "2026-05-13",
            tatnClose = 600.0,
            tatnpClose = 580.0,
            spreadPercent = 3.44,
            diff = 20.0,
            zScore = -1.25
        )
        val c = CandlePoint(label = "10:00", open = 1.0, high = 2.0, low = 0.5, close = 1.5)
        return UiState.Success(
            points = listOf(p),
            loadedAt = "2026-05-13 12:00:00",
            tatnCandles = listOf(c),
            tatnpCandles = emptyList(),
            marketsDataSource = MarketsDataSource.Network
        )
    }

    @Test
    fun encodeDecode_roundTrip_preservesPointsAndCandles() {
        val original = sampleSuccess()
        val savedAt = 1_000_000L
        val json = encodeMarketsSnapshotJson(original, savedAt)
        val decoded = decodeMarketsSnapshotIfFresh(
            raw = json,
            nowMillis = savedAt + 60_000L,
            ttlMs = MARKETS_SNAPSHOT_TTL_MS
        )
        assertNotNull(decoded)
        assertEquals(1, decoded!!.points.size)
        assertEquals(original.points[0].zScore, decoded.points[0].zScore, 1e-9)
        assertEquals(original.points[0].timestampMillis, decoded.points[0].timestampMillis)
        assertEquals(MarketsDataSource.FifteenMinuteCache, decoded.marketsDataSource)
        assertEquals(1, decoded.tatnCandles.size)
        assertEquals(1.5, decoded.tatnCandles[0].close, 1e-9)
        assertEquals(original.loadedAt, decoded.loadedAt)
    }

    @Test
    fun decode_returnsNull_whenOlderThanTtl() {
        val json = encodeMarketsSnapshotJson(sampleSuccess(), savedAtMillis = 1_000_000L)
        val decoded = decodeMarketsSnapshotIfFresh(
            raw = json,
            nowMillis = 1_000_000L + MARKETS_SNAPSHOT_TTL_MS + 1L,
            ttlMs = MARKETS_SNAPSHOT_TTL_MS
        )
        assertNull(decoded)
    }

    @Test
    fun decode_returnsNull_onInvalidJson() {
        assertNull(decodeMarketsSnapshotIfFresh("{not json", nowMillis = 0L, ttlMs = 60_000L))
    }

    @Test
    fun decode_returnsNull_whenPointsArrayEmpty() {
        val root = org.json.JSONObject().apply {
            put("savedAtMillis", 1000L)
            put("loadedAt", "x")
            put("points", org.json.JSONArray())
        }
        assertNull(
            decodeMarketsSnapshotIfFresh(
                root.toString(),
                nowMillis = 2000L,
                ttlMs = 60_000L
            )
        )
    }
}
