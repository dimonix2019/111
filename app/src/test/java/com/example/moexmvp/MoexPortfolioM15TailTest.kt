package com.example.moexmvp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexPortfolioM15TailTest {

    @Test
    fun portfolio15mSeriesTailStale_whenLastBarOld() {
        val old = DataPoint(
            timestampMillis = System.currentTimeMillis() - 5L * 24 * 60 * 60 * 1000,
            tradeDate = "2026-05-13 18:45",
            tatnClose = 1.0,
            tatnpClose = 1.0,
            spreadPercent = 1.0,
            diff = 0.0,
            zScore = 0.0
        )
        assertTrue(portfolio15mSeriesTailStale(listOf(old)))
    }

    @Test
    fun portfolio15mSeriesTailStale_falseWhenRecent() {
        val recent = DataPoint(
            timestampMillis = System.currentTimeMillis() - 2 * 60 * 60 * 1000,
            tradeDate = "2026-05-17 14:00",
            tatnClose = 1.0,
            tatnpClose = 1.0,
            spreadPercent = 1.0,
            diff = 0.0,
            zScore = 0.0
        )
        assertFalse(portfolio15mSeriesTailStale(listOf(recent)))
    }
}
