package com.example.moexmvp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexZPeakExitTest {

    @Test
    fun zPeakTrailingExitLong_exitsOnBounceFromMin() {
        val zBest = -1.2
        assertFalse(zPeakTrailingExitLong(-1.15, zBest, entryThreshold = 0.8, trailZ = 0.2))
        assertTrue(zPeakTrailingExitLong(-0.95, zBest, entryThreshold = 0.8, trailZ = 0.2))
    }

    @Test
    fun zPeakTrailingExitLong_requiresEntryDepth() {
        assertFalse(zPeakTrailingExitLong(-0.5, zBestSinceEntry = -0.5, entryThreshold = 0.8, trailZ = 0.2))
    }

    @Test
    fun zPeakTrailingExitShort_exitsOnBounceFromMax() {
        val zBest = 1.3
        assertFalse(zPeakTrailingExitShort(1.2, zBest, entryThreshold = 0.8, trailZ = 0.2))
        assertTrue(zPeakTrailingExitShort(1.05, zBest, entryThreshold = 0.8, trailZ = 0.2))
    }
}
