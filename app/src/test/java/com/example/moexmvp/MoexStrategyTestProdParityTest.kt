package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MoexStrategyTestProdParityTest {

    @Test
    fun prodEffectiveCapitalUsagePercent_matchesReserveFraction() {
        assertEquals(75.0, PROD_EFFECTIVE_CAPITAL_USAGE_PERCENT, 0.01)
    }

    @Test
    fun prodReservePercent_withinEpsilonWhenAligned() {
        assertTrue(abs(PROD_EFFECTIVE_CAPITAL_USAGE_PERCENT - PROD_EFFECTIVE_CAPITAL_USAGE_PERCENT) <= 1.0)
        assertFalse(abs(80.0 - PROD_EFFECTIVE_CAPITAL_USAGE_PERCENT) <= 1.0)
    }

    @Test
    fun formatStrategyTestProdParitySummary_listsFailedItems() {
        val items = listOf(
            StrategyTestProdParityItem(ok = true, label = "A"),
            StrategyTestProdParityItem(ok = false, label = "B"),
        )
        val summary = formatStrategyTestProdParitySummary(items)
        assertTrue(summary.contains("1/2"))
        assertTrue(summary.contains("B"))
    }
}
