package com.example.moexmvp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TinkoffProdWindowPnlTest {

    @Test
    fun summarizeProdSpreadOperations_prefersAccountPayments() {
        val day = Instant.parse("2026-06-28T16:11:00Z").toEpochMilli()
        val feeDay = Instant.parse("2026-06-28T16:11:05Z").toEpochMilli()
        val ops = listOf(
            tradeOp(day, ticker = "TATN", payment = -45_760.0, yield = -177.1, commission = 15.02, qty = 80),
            tradeOp(day + 60_000, ticker = "TATNP", payment = -52_800.0, yield = -150.1, commission = 15.05, qty = 110),
            feeOp(feeDay, payment = -15.1),
        )
        val summary = summarizeProdSpreadOperations(
            operations = ops,
            fromMillis = day - 1_000,
            toMillis = day + 120_000,
        )
        assertEquals(ProdSpreadWindowPnlSource.AccountPayments, summary.source)
        assertEquals(3, summary.roundTripCount)
        assertEquals(-45_760.0 + (-52_800.0) + (-15.1), summary.netRub, 0.05)
        assertTrue(summary.commissionRub > 25.0)
    }

    @Test
    fun summarizeProdSpreadOperations_fallsBackToYieldWhenNoPayments() {
        val day = Instant.parse("2026-06-28T16:11:00Z").toEpochMilli()
        val ops = listOf(
            sellOp(day, ticker = "TATN", yield = -177.1, commission = 15.02),
            sellOp(day + 60_000, ticker = "TATNP", yield = -150.1, commission = 15.05),
        )
        val summary = summarizeProdSpreadOperations(
            operations = ops,
            fromMillis = day - 1_000,
            toMillis = day + 120_000,
        )
        assertEquals(ProdSpreadWindowPnlSource.YieldMinusCommission, summary.source)
        assertEquals(2, summary.roundTripCount)
        assertTrue(summary.netRub < -300.0)
    }

    @Test
    fun buildClosedRowsFromProdOperationsWindow_usesYieldAndTradeNotional() {
        val day = Instant.parse("2026-06-28T16:13:00Z").toEpochMilli()
        val rows = buildClosedRowsFromProdOperationsWindow(
            operations = listOf(
                tradeOp(
                    day,
                    ticker = "TATN",
                    payment = -45_760.0,
                    yield = -177.1,
                    commission = 15.0,
                    qty = 80,
                    side = "SELL",
                ),
            ),
            fromMillis = day - 1_000,
            toMillis = day + 1_000,
        )
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("брокер", row.confirmLabel)
        assertEquals(-177.1, row.netPnlRubApprox, 0.05)
        assertEquals(-177.1, row.legLongPnlSplitRubApprox, 0.05)
        assertEquals("80 ак.", row.volumeText)
        assertTrue(row.longLegSideRu.contains("продажа"))
        assertTrue(row.longLegSideRu.contains("80 ак."))
        assertTrue(row.longLegSideRu.contains("45760"))
    }

    @Test
    fun buildClosedRowsFromProdOperationsWindow_skipsFeeOnlyAndYieldLessOps() {
        val day = Instant.parse("2026-06-28T16:11:00Z").toEpochMilli()
        val rows = buildClosedRowsFromProdOperationsWindow(
            operations = listOf(
                feeOp(day, payment = -15.1),
                tradeOp(day + 1_000, ticker = "TATN", payment = -45_760.0, yield = -170.0, commission = 15.0, qty = 80),
            ),
            fromMillis = day - 1_000,
            toMillis = day + 120_000,
        )
        assertEquals(1, rows.size)
        assertEquals(-170.0, rows.single().netPnlRubApprox, 0.05)
    }

    @Test
    fun flattenSpreadOperationsFromApi_readsYieldFromNestedTradesAndInstrumentUid() {
        val day = Instant.parse("2026-06-28T16:13:00Z").toEpochMilli()
        val cursorItem = JSONObject()
            .put("id", "op-1")
            .put("date", Instant.ofEpochMilli(day).toString())
            .put("instrumentUid", "TATN_TQBR")
            .put("operationType", "OPERATION_TYPE_SELL")
            .put("payment", money(-45_760.0))
            .put("commission", money(15.0))
            .put("quantity", 80)
            .put(
                "tradesInfo",
                JSONObject().put(
                    "trades",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("yield", money(-95.0))
                            .put("quantity", 80),
                    ),
                ),
            )
        val flat = flattenSpreadOperationsFromApi(listOf(cursorItem))
        assertEquals(1, flat.size)
        val rows = buildClosedRowsFromProdOperationsWindow(
            operations = flat,
            fromMillis = day - 1_000,
            toMillis = day + 1_000,
        )
        assertEquals(1, rows.size)
        assertEquals(-95.0, rows.single().netPnlRubApprox, 0.05)
        assertEquals("TATN", rows.single().longLegTicker)
    }

    @Test
    fun ensureProdBrokerSummaryRow_whenOnlyFeesInSummary() {
        val day = Instant.parse("2026-06-28T16:11:05Z").toEpochMilli()
        val summary = ProdSpreadWindowPnlSummary(
            netRub = -185.0,
            grossYieldRub = -185.0,
            commissionRub = 185.0,
            roundTripCount = 12,
            fromMillis = day - 86_400_000,
            toMillis = day,
            source = ProdSpreadWindowPnlSource.AccountPayments,
        )
        val rows = ensureProdBrokerSummaryRow(emptyList(), summary)
        assertEquals(1, rows.size)
        assertEquals("счёт", rows.single().confirmLabel)
        assertEquals(-185.0, rows.single().netPnlRubApprox, 0.01)
        assertEquals("12 оп.", rows.single().volumeText)
    }

    private fun tradeOp(
        millis: Long,
        ticker: String,
        payment: Double,
        yield: Double,
        commission: Double = 0.0,
        qty: Int = 80,
        side: String = "SELL",
    ): JSONObject = JSONObject()
        .put("date", Instant.ofEpochMilli(millis).toString())
        .put("ticker", ticker)
        .put("operationType", "OPERATION_TYPE_$side")
        .put("payment", money(payment))
        .put("yield", money(yield))
        .put("commission", money(commission))
        .put("quantity", qty)

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
