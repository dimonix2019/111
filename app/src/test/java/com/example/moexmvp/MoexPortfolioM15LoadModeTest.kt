package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MoexPortfolioM15LoadModeTest {

    @Test
    fun resolveMode_fullRefreshWhenLastBarMoreThanOneDayBehind() {
        val today = LocalDate.of(2026, 5, 17)
        val mode = resolvePortfolioM15LoadModeForLastBar(
            lastBarDay = LocalDate.of(2026, 5, 13),
            todayMoscow = today,
            lastTsAgeMs = 0L
        )
        assertEquals(PortfolioM15LoadMode.FULL_REFRESH, mode)
    }

    @Test
    fun resolveMode_incrementalWhenLastBarYesterday() {
        val today = LocalDate.of(2026, 5, 17)
        val mode = resolvePortfolioM15LoadModeForLastBar(
            lastBarDay = LocalDate.of(2026, 5, 16),
            todayMoscow = today,
            lastTsAgeMs = 0L
        )
        assertEquals(PortfolioM15LoadMode.INCREMENTAL, mode)
    }

    @Test
    fun resolveMode_cacheOnlyWhenLastBarTodayAndFresh() {
        val today = LocalDate.of(2026, 5, 17)
        val mode = resolvePortfolioM15LoadModeForLastBar(
            lastBarDay = today,
            todayMoscow = today,
            lastTsAgeMs = 60_000L
        )
        assertEquals(PortfolioM15LoadMode.CACHE_ONLY, mode)
    }
}
