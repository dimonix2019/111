package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSpreadSlicesTest {
    @Test
    fun twoLongOpens_keepSeparateBooks() {
        val t1 = 1_000L
        val t2 = 2_000L
        val slices = openSpreadSlices(
            listOf(
                SpreadFillLot("TATN", isBuy = true, lots = 10, price = 660.0, millis = t1),
                SpreadFillLot("TATNP", isBuy = false, lots = 10, price = 630.0, millis = t1 + 1),
                SpreadFillLot("TATN", isBuy = true, lots = 9, price = 650.0, millis = t2),
                SpreadFillLot("TATNP", isBuy = false, lots = 9, price = 620.0, millis = t2 + 1),
            ),
        )
        assertEquals(2, slices.size)
        assertEquals(SpreadTradeBook.Base, slices[0].book)
        assertEquals(10, slices[0].tatnLots)
        assertEquals(-10, slices[0].tatnpLots)
        assertEquals(SpreadTradeBook.DynamicRange, slices[1].book)
        assertEquals(9, slices[1].tatnLots)
        assertEquals(-9, slices[1].tatnpLots)
        assertEquals("база", spreadTradeBookLabel(slices[0].book))
        assertEquals("динамический диапазон", spreadTradeBookLabel(slices[1].book))
    }

    @Test
    fun closingNineLots_leavesTheTenLotTrade() {
        val open = openSpreadSlices(
            listOf(
                SpreadFillLot("TATN", true, 10, 660.0, 1_000),
                SpreadFillLot("TATNP", false, 10, 630.0, 1_001),
                SpreadFillLot("TATN", true, 9, 650.0, 2_000),
                SpreadFillLot("TATNP", false, 9, 620.0, 2_001),
                SpreadFillLot("TATN", false, 9, 640.0, 3_000),
                SpreadFillLot("TATNP", true, 9, 610.0, 3_001),
            ),
        )
        assertEquals(1, open.size)
        assertEquals(10, open[0].tatnLots)
        assertEquals(SpreadTradeBook.Base, open[0].book)
    }

    @Test
    fun sumLine_onlyWhenTwoResults() {
        assertNull(formatOpenSpreadsSumLine(listOf(10.0), listOf(100.0)))
        val line = formatOpenSpreadsSumLine(listOf(100.0, -40.0), listOf(1_000.0, 1_000.0))!!
        assertTrue(line.startsWith("Сумма"))
        assertTrue(line.contains("от депозитов"))
    }
}
