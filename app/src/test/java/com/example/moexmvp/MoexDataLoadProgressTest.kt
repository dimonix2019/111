package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MoexDataLoadProgressTest {

    @Test
    fun estimateM15BarCount_oneDay() {
        val d = LocalDate.of(2026, 5, 20)
        assertEquals(PORTFOLIO_M15_BARS_PER_CALENDAR_DAY_ESTIMATE, estimateM15BarCount(d, d))
    }

    @Test
    fun countMoexDateChunks_splitsBy21Days() {
        val from = LocalDate.of(2026, 1, 1)
        val till = LocalDate.of(2026, 2, 1)
        assertEquals(2, countMoexDateChunks(from, till, chunkDays = 21L))
    }

    @Test
    fun formatDataLoadProgressSummary_cacheAndMoex() {
        val summary = formatDataLoadProgressSummary(
            DataLoadProgress(
                phase = DataLoadPhase.MoexDownload,
                cacheBarsLoaded = 1200,
                cacheBarsTotal = 1200,
                moexBarsLoaded = 340,
                moexBarsTotal = 800,
                moexChunkIndex = 2,
                moexChunkTotal = 5,
            )
        )
        assertTrue(summary.contains("Кэш: 1200 / 1200"))
        assertTrue(summary.contains("MOEX: 340 / 800"))
        assertTrue(summary.contains("чанк 2/5"))
    }
}
