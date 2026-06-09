package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PortfolioTestEntryBarTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun currentM15BucketStartMillis_alignsToQuarterHour() {
        val instant = Instant.parse("2026-06-05T06:17:30Z") // 09:17:30 MSK
        val bucket = currentM15BucketStartMillis(instant, zone)
        val label = formatPortfolioExecutionTableMsk(bucket)
        assertEquals("2026-06-05 09:15", label)
    }

    @Test
    fun currentM15BucketStartMillis_onExactBoundary() {
        val instant = Instant.parse("2026-06-05T06:00:00Z") // 09:00 MSK
        val bucket = currentM15BucketStartMillis(instant, zone)
        assertEquals("2026-06-05 09:00", formatPortfolioExecutionTableMsk(bucket))
    }
}
