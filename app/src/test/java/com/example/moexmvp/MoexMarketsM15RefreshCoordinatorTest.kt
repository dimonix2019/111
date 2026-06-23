package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexMarketsM15RefreshCoordinatorTest {

    @Test
    fun mergeMarketsM15RefreshRequest_keepsHigherPriority() {
        val low = MarketsM15RefreshRequest(MarketsM15RefreshKind.CATCHUP, "a")
        val high = MarketsM15RefreshRequest(MarketsM15RefreshKind.FORCE_INCR, "b", clearLiveTailZ = true)
        val merged = mergeMarketsM15RefreshRequest(low, high)
        assertEquals(MarketsM15RefreshKind.FORCE_INCR, merged.kind)
        assertTrue(merged.clearLiveTailZ)
        assertTrue(merged.reason.contains("a"))
        assertTrue(merged.reason.contains("b"))
    }

    @Test
    fun mergeMarketsM15RefreshRequest_mergesDebounceMs() {
        val a = MarketsM15RefreshRequest(MarketsM15RefreshKind.CATCHUP, "a", debounceMs = 1000L)
        val b = MarketsM15RefreshRequest(MarketsM15RefreshKind.TAIL_STALE, "b", debounceMs = 3000L)
        val merged = mergeMarketsM15RefreshRequest(a, b)
        assertEquals(3000L, merged.debounceMs)
        assertEquals(MarketsM15RefreshKind.TAIL_STALE, merged.kind)
    }
}
