package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MoexPortfolioChartThresholdsTest {

    @Test
    fun portfolioChartZThresholds_usesPortfolioValues() {
        val th = portfolioChartZThresholds(realTradeEntry = 0.8, realTradeExit = 0.3)
        assertEquals(0.8, th.entry, 1e-9)
        assertEquals(0.3, th.exit, 1e-9)
    }

    @Test
    fun dailyRecalcDisabledByDefault() {
        assertFalse(DYNAMIC_Z_DAILY_RECALC_ENABLED)
    }
}
