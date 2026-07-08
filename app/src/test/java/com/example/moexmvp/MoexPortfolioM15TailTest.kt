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

    @Test
    fun portfolio15mSeriesIntradayStale_whenTwoBucketsBehindDuringSession() {
        val zone = moexZoneId
        val nowMillis = java.time.ZonedDateTime.of(2026, 6, 26, 15, 25, 0, 0, zone)
            .toInstant().toEpochMilli()
        val lastMillis = java.time.ZonedDateTime.of(2026, 6, 26, 13, 0, 0, 0, zone)
            .toInstant().toEpochMilli()
        val stale = DataPoint(
            timestampMillis = lastMillis,
            tradeDate = "2026-06-26 13:00",
            tatnClose = 1.0,
            tatnpClose = 1.0,
            spreadPercent = 1.0,
            diff = 0.0,
            zScore = 0.0,
        )
        assertTrue(portfolio15mSeriesIntradayStale(listOf(stale), nowMillis = nowMillis))
    }

    @Test
    fun portfolio15mSeriesIntradayStale_falseWhenPreviousBucketDuringSession() {
        val zone = moexZoneId
        val now = java.time.ZonedDateTime.of(2026, 6, 26, 15, 10, 0, 0, zone)
        val bucketStart = currentM15BucketStartMillis(now.toInstant(), zone)
        val prevBucket = DataPoint(
            timestampMillis = bucketStart - 15L * 60_000L,
            tradeDate = "2026-06-27 15:00",
            tatnClose = 1.0,
            tatnpClose = 1.0,
            spreadPercent = 1.0,
            diff = 0.0,
            zScore = 0.0,
        )
        assertFalse(
            portfolio15mSeriesIntradayStale(
                listOf(prevBucket),
                nowMillis = now.toInstant().toEpochMilli(),
            ),
        )
    }

    @Test
    fun portfolio15mSeriesIntradayStale_falseAfterSessionSameDay() {
        val zone = moexZoneId
        val now = java.time.ZonedDateTime.of(2026, 6, 26, 20, 0, 0, 0, zone)
        val lastBar = DataPoint(
            timestampMillis = java.time.ZonedDateTime.of(2026, 6, 26, 18, 45, 0, 0, zone)
                .toInstant().toEpochMilli(),
            tradeDate = "2026-06-27 18:45",
            tatnClose = 1.0,
            tatnpClose = 1.0,
            spreadPercent = 1.0,
            diff = 0.0,
            zScore = 0.0,
        )
        assertFalse(
            portfolio15mSeriesIntradayStale(
                listOf(lastBar),
                nowMillis = now.toInstant().toEpochMilli(),
            ),
        )
    }

    @Test
    fun portfolio15mSeriesIntradayStale_falseWhenFresh() {
        val fresh = DataPoint(
            timestampMillis = System.currentTimeMillis() - 5 * 60 * 1000,
            tradeDate = "2026-05-17 14:00",
            tatnClose = 1.0,
            tatnpClose = 1.0,
            spreadPercent = 1.0,
            diff = 0.0,
            zScore = 0.0,
        )
        assertFalse(portfolio15mSeriesIntradayStale(listOf(fresh)))
    }
}
