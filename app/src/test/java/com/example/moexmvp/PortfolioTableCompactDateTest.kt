package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioTableCompactDateTest {

    @Test
    fun compactPortfolioTableDateLabel_shortensYear() {
        assertEquals("26-06-01 10:15", compactPortfolioTableDateLabel("2026-06-01 10:15"))
        assertEquals("1 long 26-06-01 10:15", compactPortfolioTableDateLabel("1 long 2026-06-01 10:15"))
        assertEquals("—", compactPortfolioTableDateLabel("—"))
    }
}
