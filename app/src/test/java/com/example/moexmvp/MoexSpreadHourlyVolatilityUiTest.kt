package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexSpreadHourlyVolatilityUiTest {

    @Test
    fun buildSpreadVolatilityYTicks_topEqualsMaxWithoutExtraLine() {
        val ticks = buildSpreadVolatilityYTicks(minValue = 0.03, maxValue = 0.047)
        assertTrue(ticks.last() >= 0.047)
        assertTrue(ticks.last() <= 0.05 + 1e-9)
        assertTrue(ticks.none { it > 0.05 + 1e-9 })
    }

    @Test
    fun spreadVolatilityDisplayYRange_usesMinAndMaxOfBars() {
        val bars = listOf(
            SpreadHourlyVolatilityBar(10, 0.04, 3, 4),
            SpreadHourlyVolatilityBar(11, 0.06, 3, 4),
            SpreadHourlyVolatilityBar(12, 0.05, 3, 4),
        )
        val range = spreadVolatilityDisplayYRange(bars)
        assertEquals(0.04 to 0.06, range)
    }
}
