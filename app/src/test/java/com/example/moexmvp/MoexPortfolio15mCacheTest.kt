package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MoexPortfolio15mCacheTest {

    @Test
    fun fetchChunkDateWindows_coverFullRange() {
        val from = LocalDate.of(2026, 4, 1)
        val till = LocalDate.of(2026, 5, 17)
        val chunkDays = 21L
        val ends = mutableListOf<LocalDate>()
        var chunkStart = from
        while (!chunkStart.isAfter(till)) {
            val chunkEnd = minOf(chunkStart.plusDays(chunkDays - 1), till)
            ends += chunkEnd
            chunkStart = chunkEnd.plusDays(1)
        }
        assertEquals(till, ends.last())
        assertEquals(LocalDate.of(2026, 4, 21), ends.first())
    }
}
