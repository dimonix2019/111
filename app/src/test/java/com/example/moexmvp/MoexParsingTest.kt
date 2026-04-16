package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
}
