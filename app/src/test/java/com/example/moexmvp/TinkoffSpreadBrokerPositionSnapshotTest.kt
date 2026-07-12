package com.example.moexmvp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TinkoffSpreadBrokerPositionSnapshotTest {

    @Test
    fun parseSpreadBrokerPositionSnapshot_openWhenBothLegsPresent() {
        val portfolio = portfolio(
            position("TATN", quantity = 80, expectedYield = 125.0, price = 572.4),
            position("TATNP", quantity = -110, expectedYield = -40.0, price = 538.7),
        )

        val snapshot = parseSpreadBrokerPositionSnapshot(portfolio, StrategySignalType.EnterLong)

        assertEquals(SpreadBrokerPositionState.Open, snapshot.state)
        assertNotNull(snapshot.pnl)
        assertEquals(125.0, snapshot.pnl!!.longLegYieldRub, 0.001)
        assertEquals(-40.0, snapshot.pnl.shortLegYieldRub, 0.001)
        assertEquals(80, snapshot.pnl.longLegQuantity)
        assertEquals(-110, snapshot.pnl.shortLegQuantity)
    }

    @Test
    fun parseSpreadBrokerPositionSnapshot_closedWhenNoTatnTatnpPositions() {
        val portfolio = portfolio()

        val snapshot = parseSpreadBrokerPositionSnapshot(portfolio, StrategySignalType.EnterLong)

        assertEquals(SpreadBrokerPositionState.Closed, snapshot.state)
        assertNull(snapshot.pnl)
    }

    @Test
    fun parseSpreadBrokerPositionSnapshot_partialWhenOneLegRemains() {
        val portfolio = portfolio(
            position("TATN", quantity = 80, expectedYield = 125.0, price = 572.4),
        )

        val snapshot = parseSpreadBrokerPositionSnapshot(portfolio, StrategySignalType.EnterLong)

        assertEquals(SpreadBrokerPositionState.Partial, snapshot.state)
        assertNull(snapshot.pnl)
    }

    private fun portfolio(vararg positions: JSONObject): JSONObject =
        JSONObject().put(
            "portfolio",
            JSONObject().put("positions", JSONArray().apply { positions.forEach(::put) }),
        )

    private fun position(
        ticker: String,
        quantity: Int,
        expectedYield: Double,
        price: Double,
    ): JSONObject =
        JSONObject()
            .put("ticker", ticker)
            .put("quantity", money(quantity.toDouble()))
            .put("expectedYield", money(expectedYield))
            .put("currentPrice", money(price))

    private fun money(value: Double): JSONObject {
        val units = value.toLong()
        val nano = ((value - units) * 1_000_000_000L).toLong()
        return JSONObject()
            .put("units", units)
            .put("nano", nano)
    }
}
