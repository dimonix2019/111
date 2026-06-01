package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexMarketsM15LookbackTest {

    @Test
    fun marketsM15LookbackDays_oneDay_isSmall() {
        val days = marketsM15LookbackDays(Period.OneDay)
        assertEquals(1L + Z_SCORE_ROLLING_LOOKBACK_DAYS + 7L, days)
        assertTrue(days < 60L)
    }

    @Test
    fun marketsM15LookbackDays_oneYear_coversChartAndRollingZ() {
        val days = marketsM15LookbackDays(Period.OneYear)
        assertEquals(365L + Z_SCORE_ROLLING_LOOKBACK_DAYS + 7L, days)
    }
}
