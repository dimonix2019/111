package com.example.moexmvp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TinkoffProdWindowPnlTest {

    @Test
    fun summarizeProdSpreadOperations_sumsYieldMinusAllCommissions() {
        val day = Instant.parse("2026-06-28T16:11:00Z").toEpochMilli()
        val feeDay = Instant.parse("2026-06-28T16:11:05Z").toEpochMilli()
        val ops = listOf(
            sellOp(day, ticker = "TATN", yield = -177.1, commission = 15.02),
            sellOp(day + 60_000, ticker = "TATNP", yield = -150.1, commission = 15.05),
            feeOp(feeDay, payment = -15.1),
        )
        val summary = summarizeProdSpreadOperations(
            operations = ops,
            fromMillis = day - 1_000,
            toMillis = day + 120_000,
        )
        assertEquals(2, summary.roundTripCount)
        assertEquals(-177.1 + (-150.1), summary.grossYieldRub, 0.05)
        assertTrue(summary.commissionRub > 40.0)
        assertTrue(summary.netRub < -300.0)
    }

    @Test
    fun buildClosedRowsFromProdOperationsWindow_oneRowPerYieldOp() {
        val day = Instant.parse("2026-06-28T16:13:00Z").toEpochMilli()
        val rows = buildClosedRowsFromProdOperationsWindow(
            operations = listOf(sellOp(day, ticker = "TATN", yield = -177.1, commission = 15.0)),
            fromMillis = day - 1_000,
            toMillis = day + 1_000,
        )
        assertEquals(1, rows.size)
        assertEquals("брокер", rows.single().confirmLabel)
        assertEquals(-192.1, rows.single().netPnlRubApprox, 0.05)
    }

    private fun sellOp(
        millis: Long,
        ticker: String,
        yield: Double,
        commission: Double,
    ): JSONObject = JSONObject()
        .put("date", Instant.ofEpochMilli(millis).toString())
        .put("ticker", ticker)
        .put("yield", money(yield))
        .put("commission", money(commission))
        .put("quantity", 80)

    private fun feeOp(millis: Long, payment: Double): JSONObject = JSONObject()
        .put("date", Instant.ofEpochMilli(millis).toString())
        .put("operationType", "OPERATION_TYPE_BROKER_FEE")
        .put("payment", money(payment))

    private fun money(rub: Double): JSONObject {
        val units = rub.toLong()
        val nano = ((rub - units) * 1_000_000_000).toInt()
        return JSONObject().put("units", units).put("nano", nano)
    }
}
