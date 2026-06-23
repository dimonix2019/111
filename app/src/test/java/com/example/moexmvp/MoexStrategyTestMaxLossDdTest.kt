package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexStrategyTestMaxLossDdTest {

    @Test
    fun resolveStrategyTestMaxLossRub_zeroPercentMeansDisabled() {
        assertEquals(0.0, resolveStrategyTestMaxLossRub(100_000.0, 0.0), 0.01)
    }

    @Test
    fun resolveStrategyTestMaxLossRub_scalesWithAccount() {
        assertEquals(4_000.0, resolveStrategyTestMaxLossRub(100_000.0, 4.0), 0.01)
        assertEquals(400.0, resolveStrategyTestMaxLossRub(10_000.0, 4.0), 0.01)
    }

    @Test
    fun formatStrategyTestMaxLossDdHint_showsRubWhenEnabled() {
        val hint = formatStrategyTestMaxLossDdHint(4.0, 100_000.0)
        assertTrue(hint.contains("4000"))
        assertTrue(hint.contains("4.0%"))
    }

    @Test
    fun formatStrategyTestMaxLossDdHint_offWhenZero() {
        assertTrue(formatStrategyTestMaxLossDdHint(0.0, 100_000.0).contains("выкл"))
    }
}
