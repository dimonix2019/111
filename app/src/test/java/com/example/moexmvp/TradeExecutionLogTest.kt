package com.example.moexmvp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeExecutionLogTest {
    @Test
    fun parsePostOrderFill_readsExecutedLotsAndPrice() {
        val json = JSONObject(
            """
            {
              "orderId": "12345",
              "lotsRequested": 67,
              "lotsExecuted": 67,
              "executionReportStatus": "EXECUTION_REPORT_STATUS_FILL",
              "executedOrderPrice": {"units": "537", "nano": 897015000, "currency": "rub"}
            }
            """.trimIndent()
        )
        val parsed = parsePostOrderFill(json)
        assertEquals("12345", parsed.orderId)
        assertEquals(67, parsed.requestedLots)
        assertEquals(67, parsed.executedLots)
        assertEquals(TradeLegFillStatus.Full, parsed.fillStatus)
        assertNotNull(parsed.executedPriceRub)
        assertEquals(537.897, parsed.executedPriceRub!!, 0.01)
    }

    @Test
    fun parsePostOrderFill_detectsPartialFill() {
        val json = JSONObject(
            """
            {
              "orderId": "p1",
              "lotsRequested": 67,
              "lotsExecuted": 40,
              "executionReportStatus": "EXECUTION_REPORT_STATUS_PARTIALLYFILL",
              "executedOrderPrice": {"units": "531", "nano": 600000000, "currency": "rub"}
            }
            """.trimIndent()
        )
        val parsed = parsePostOrderFill(json)
        assertEquals(TradeLegFillStatus.Partial, parsed.fillStatus)
        assertEquals(40, parsed.executedLots)
    }

    @Test
    fun computeLegSlippagePriceBps_buyPositiveWhenPaidMore() {
        val bps = computeLegSlippagePriceBps("покупка 67 лот", 538.0, 537.0)
        assertNotNull(bps)
        assertTrue(bps!! > 0)
    }
}
