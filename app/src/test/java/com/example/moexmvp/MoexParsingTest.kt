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
    fun parseCandleBars_parsesMinuteCandles() {
        val body = """
            {
              "candles": {
                "columns": ["begin", "open", "high", "low", "close"],
                "data": [
                  ["2026-04-16 10:01:00", 603.0, 603.4, 602.8, 603.2],
                  ["2026-04-16 10:02:00", "603.2", "603.8", "603.0", "603.5"],
                  ["bad-time", 603.0, 603.4, 602.8, 603.2]
                ]
              }
            }
        """.trimIndent()

        val parsed = parseCandleBars(body)
        assertEquals(2, parsed.size)
        assertEquals(603.2, parsed[0].close, 0.0001)
        assertEquals(603.5, parsed[1].close, 0.0001)
        assertEquals(LocalDateTime.parse("2026-04-16T10:01:00"), parsed[0].timestamp)
        assertEquals(LocalDateTime.parse("2026-04-16T10:02:00"), parsed[1].timestamp)
    }

    @Test
    fun aggregateTo15MinuteBars_buildsOhlcPerBucket() {
        val source = listOf(
            CandleBar(
                timestamp = LocalDateTime.parse("2026-04-16T10:01:00"),
                open = 600.1, high = 600.8, low = 599.9, close = 600.4
            ),
            CandleBar(
                timestamp = LocalDateTime.parse("2026-04-16T10:14:00"),
                open = 600.4, high = 601.6, low = 600.2, close = 601.4
            ),
            CandleBar(
                timestamp = LocalDateTime.parse("2026-04-16T10:16:00"),
                open = 602.2, high = 602.7, low = 601.9, close = 602.3
            ),
            CandleBar(
                timestamp = LocalDateTime.parse("2026-04-16T10:29:00"),
                open = 602.3, high = 604.1, low = 602.0, close = 603.9
            )
        )

        val aggregated = aggregateTo15MinuteBars(source)
        assertEquals(2, aggregated.size)
        val first = aggregated[0]
        assertEquals(LocalDateTime.parse("2026-04-16T10:00:00"), first.timestamp)
        assertEquals(600.1, first.open, 0.0001)
        assertEquals(601.6, first.high, 0.0001)
        assertEquals(599.9, first.low, 0.0001)
        assertEquals(601.4, first.close, 0.0001)

        val second = aggregated[1]
        assertEquals(LocalDateTime.parse("2026-04-16T10:15:00"), second.timestamp)
        assertEquals(602.2, second.open, 0.0001)
        assertEquals(604.1, second.high, 0.0001)
        assertEquals(601.9, second.low, 0.0001)
        assertEquals(603.9, second.close, 0.0001)
    }
}
