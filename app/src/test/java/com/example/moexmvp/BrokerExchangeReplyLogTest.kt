package com.example.moexmvp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerExchangeReplyLogTest {

    @Test
    fun summarizeRequest_mapsFigiAndSellDirection() {
        val body = JSONObject()
            .put("instrumentId", TINKOFF_MOEX_TATN_FIGI)
            .put("direction", "ORDER_DIRECTION_SELL")
            .put("quantity", 54)
        val req = summarizeTinkoffOrderRequest(body)
        assertEquals("TATN", req.ticker)
        assertEquals("SELL", req.direction)
        assertEquals(54, req.lots)
    }

    @Test
    fun summarizeRequest_mapsTatnpFigiBuy() {
        val body = JSONObject()
            .put("instrument_id", TINKOFF_MOEX_TATNP_FIGI)
            .put("order_direction", "ORDER_DIRECTION_BUY")
            .put("quantity_lots", 54)
        val req = summarizeTinkoffOrderRequest(body)
        assertEquals("TATNP", req.ticker)
        assertEquals("BUY", req.direction)
        assertEquals(54, req.lots)
    }

    @Test
    fun summarizeHttpReply_successFill() {
        val request = JSONObject()
            .put("instrumentId", TINKOFF_MOEX_TATN_FIGI)
            .put("direction", "ORDER_DIRECTION_SELL")
            .put("quantity", 54)
        val response = JSONObject()
            .put("orderId", "ord-1")
            .put("lotsRequested", 54)
            .put("lotsExecuted", 54)
            .put("executionReportStatus", "EXECUTION_REPORT_STATUS_FILL")
            .put(
                "executedOrderPrice",
                JSONObject().put("units", "617").put("nano", 640000000).put("currency", "rub"),
            )
        val reply = summarizeTinkoffOrderHttpReply(
            method = "PostOrder",
            request = request,
            httpCode = 200,
            responseText = response.toString(),
            ok = true,
            purpose = "spread_entry",
            atMillis = 1_736_000_000_000L,
        )
        assertTrue(reply.ok)
        assertEquals("TATN", reply.ticker)
        assertEquals("SELL", reply.direction)
        assertEquals(54, reply.lots)
        assertEquals(54, reply.executedLots)
        assertEquals("ord-1", reply.orderId)
        assertTrue(reply.execStatus.contains("FILL"))
        val line = formatBrokerExchangeReplyLine(reply)
        assertTrue(line.contains("[spread_entry]"))
        assertTrue(line.contains("PostOrder"))
        assertTrue(line.contains("OK"))
        assertTrue(line.contains("oid=ord-1"))
        assertTrue(line.contains("exec=54"))
    }

    @Test
    fun summarizeHttpReply_notEnoughAssets_matches12SepShort() {
        val request = JSONObject()
            .put("instrumentId", TINKOFF_MOEX_TATNP_FIGI)
            .put("direction", "ORDER_DIRECTION_BUY")
            .put("quantity", 54)
        val response = JSONObject()
            .put("code", 3)
            .put("message", "Not enough assets to process the request")
        val reply = summarizeTinkoffOrderHttpReply(
            method = "PostOrder",
            request = request,
            httpCode = 400,
            responseText = response.toString(),
            ok = false,
            purpose = "spread_entry",
            atMillis = 1_736_000_000_000L,
        )
        assertFalse(reply.ok)
        assertEquals("TATNP", reply.ticker)
        assertEquals("BUY", reply.direction)
        assertEquals("3", reply.apiCode)
        assertTrue(reply.message.contains("Not enough assets"))
        val line = formatBrokerExchangeReplyLine(reply)
        assertTrue(line, line.contains("FAIL"))
        assertTrue(line, line.contains("HTTP 400"))
        assertTrue(line, line.contains("code=3"))
        assertTrue(line, line.contains("TATNP BUY ×54"))
        assertTrue(line, line.contains("Not enough assets"))
    }

    @Test
    fun summarizeHttpReply_getMaxLots() {
        val request = JSONObject().put("instrumentId", TINKOFF_MOEX_TATN_FIGI)
        val response = JSONObject()
            .put("buyLimits", JSONObject().put("buyMaxLots", 10))
            .put("sellLimits", JSONObject().put("sellMaxLots", 0))
            .put("buyMarginLimits", JSONObject().put("buyMaxLots", 20))
            .put("sellMarginLimits", JSONObject().put("sellMaxLots", 0))
        val reply = summarizeTinkoffOrderHttpReply(
            method = "GetMaxLots",
            request = request,
            httpCode = 200,
            responseText = response.toString(),
            ok = true,
            purpose = "max_lots",
        )
        assertTrue(reply.ok)
        assertEquals("TATN", reply.ticker)
        assertTrue(reply.message.contains("buy=20"))
        assertTrue(reply.message.contains("sell=0"))
        val line = formatBrokerExchangeReplyLine(reply)
        assertTrue(line.contains("[max_lots]"))
        assertTrue(line.contains("GetMaxLots"))
    }

    @Test
    fun tickerFromFigi_prefersTatnpBeforeTatnSubstring() {
        assertEquals("TATNP", tickerFromTinkoffInstrumentId(TINKOFF_MOEX_TATNP_FIGI))
        assertEquals("TATN", tickerFromTinkoffInstrumentId(TINKOFF_MOEX_TATN_FIGI))
        assertEquals("TATNP", tickerFromTinkoffInstrumentId("TATNP_TQBR"))
        assertEquals("TATN", tickerFromTinkoffInstrumentId("TATN_TQBR"))
    }
}
