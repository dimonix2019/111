package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MoexParsingTest {

    @Test
    fun parseClosePage_parsesRowsAndTotalFromCursor() {
        val body = """
            {
              "history": {
                "columns": ["TRADEDATE", "CLOSE"],
                "data": [
                  ["2026-01-10", 610.5],
                  ["2026-01-11", "611.7"],
                  ["bad-date", 612.3]
                ]
              },
              "history.cursor": {
                "columns": ["INDEX", "TOTAL", "PAGESIZE"],
                "data": [[0, 140, 100]]
              }
            }
        """.trimIndent()

        val page = parseHistoryPage(body)
        assertEquals(2, page.rows.size)
        assertEquals(140, page.total)
        assertTrue(page.rows.containsKey(LocalDate.parse("2026-01-10")))
        assertTrue(page.rows.containsKey(LocalDate.parse("2026-01-11")))
    }

    @Test
    fun parseClosePage_worksWithoutCursorSection() {
        val body = """
            {
              "history": {
                "columns": ["TRADEDATE", "CLOSE"],
                "data": [
                  ["2026-02-01", 620.1]
                ]
              }
            }
        """.trimIndent()

        val page = parseHistoryPage(body)
        assertEquals(1, page.rows.size)
        assertEquals(null, page.total)
    }

    @Test
    fun shouldContinuePagination_respectsPageAndTotal() {
        assertTrue(
            shouldContinuePagination(
                pageRows = 100,
                pageSize = 100,
                start = 0,
                total = 250
            )
        )
        assertTrue(
            shouldContinuePagination(
                pageRows = 100,
                pageSize = 100,
                start = 100,
                total = 250
            )
        )
        assertFalse(
            shouldContinuePagination(
                pageRows = 50,
                pageSize = 100,
                start = 200,
                total = 250
            )
        )
        assertFalse(
            shouldContinuePagination(
                pageRows = 100,
                pageSize = 100,
                start = 200,
                total = 250
            )
        )
        assertFalse(
            shouldContinuePagination(
                pageRows = 0,
                pageSize = 100,
                start = 0,
                total = null
            )
        )
    }

    @Test
    fun parseCandleCloseSeries_parsesMinuteCandles() {
        val body = """
            {
              "candles": {
                "columns": ["begin", "close"],
                "data": [
                  ["2026-04-16 10:01:00", 603.2],
                  ["2026-04-16 10:02:00", "603.5"],
                  ["bad-time", 603.7]
                ]
              }
            }
        """.trimIndent()

        val parsed = parseCandleCloseSeries(body)
        assertEquals(2, parsed.size)
        assertEquals(603.2, parsed[LocalDateTime.parse("2026-04-16T10:01:00")]!!, 0.0001)
        assertEquals(603.5, parsed[LocalDateTime.parse("2026-04-16T10:02:00")]!!, 0.0001)
    }

    @Test
    fun aggregateTo15Minutes_keepsLastValueInEachBucket() {
        val source = linkedMapOf(
            LocalDateTime.parse("2026-04-16T10:01:00") to 600.1,
            LocalDateTime.parse("2026-04-16T10:14:00") to 601.4,
            LocalDateTime.parse("2026-04-16T10:16:00") to 602.2,
            LocalDateTime.parse("2026-04-16T10:29:00") to 603.9
        )

        val aggregated = aggregateTo15Minutes(source)
        assertEquals(2, aggregated.size)
        assertEquals(601.4, aggregated[LocalDateTime.parse("2026-04-16T10:00:00")]!!, 0.0001)
        assertEquals(603.9, aggregated[LocalDateTime.parse("2026-04-16T10:15:00")]!!, 0.0001)
    }
}
