package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexTradeTabActionsTest {

    private fun op(
        ms: Long,
        ticker: String,
        qty: Double,
        price: Double,
    ): TInvestSpreadOp {
        // payment: покупка (qty>0) = −qty×price, продажа (qty<0) = +|qty|×price
        return TInvestSpreadOp(ms, ticker, qty, price, -qty * price)
    }

    @Test
    fun pairSpreadLegs_pairsOppositeTickersWithinMinute() {
        val t0 = 1_700_000_000_000L
        val events = pairSpreadLegs(
            listOf(
                op(t0, "TATN", 60.0, 590.0),
                op(t0 + 1_000, "TATNP", -60.0, 571.0),
            ),
        )
        assertEquals(1, events.size)
        assertEquals("LONG", events[0].side)
        assertEquals(60.0, events[0].tatnQty, 1e-9)
        assertEquals(-60.0, events[0].tatnpQty, 1e-9)
    }

    @Test
    fun pairSpreadLegs_exitAndReentrySameMinuteDoNotMerge() {
        // 06.09 10:21: выход (−360/+360) и сразу вход (+360/−360) в ту же минуту.
        val t0 = 1_700_000_000_000L
        val events = pairSpreadLegs(
            listOf(
                op(t0, "TATN", -360.0, 596.3),
                op(t0 + 5_000, "TATNP", 360.0, 580.4),
                op(t0 + 30_000, "TATN", 360.0, 596.9),
                op(t0 + 35_000, "TATNP", -360.0, 579.9),
            ),
        )
        assertEquals(2, events.size)
        assertEquals("SHORT", events[0].side) // выход из лонга
        assertEquals("LONG", events[1].side)  // новый вход
    }

    @Test
    fun matchSpreadTrades_longRoundTripPaymentPnl() {
        val t0 = 1_700_000_000_000L
        val events = pairSpreadLegs(
            listOf(
                op(t0, "TATN", 60.0, 590.0),
                op(t0 + 1_000, "TATNP", -60.0, 571.0),
                op(t0 + 86_400_000, "TATN", -60.0, 595.0),
                op(t0 + 86_401_000, "TATNP", 60.0, 572.0),
            ),
        )
        val fees = listOf((t0 + 500L) to -20.0, (t0 + 86_400_500L) to -20.0)
        val trades = matchSpreadTrades(events, fees)
        assertEquals(1, trades.size)
        val t = trades[0]
        assertEquals("Long", t.direction)
        assertEquals(60, t.lots)
        // −60×590 + 60×571 + 60×595 − 60×572 − 40 = 200
        assertEquals(200.0, t.pnlRub, 1e-6)
    }

    @Test
    fun matchSpreadTrades_shortRoundTrip() {
        val t0 = 1_700_000_000_000L
        val events = pairSpreadLegs(
            listOf(
                op(t0, "TATN", -60.0, 590.0),
                op(t0 + 1_000, "TATNP", 60.0, 571.0),
                op(t0 + 3_600_000, "TATN", 60.0, 588.0),
                op(t0 + 3_601_000, "TATNP", -60.0, 571.5),
            ),
        )
        val trades = matchSpreadTrades(events, emptyList())
        assertEquals(1, trades.size)
        assertEquals("Short", trades[0].direction)
        // +60×590 − 60×571 − 60×588 + 60×571.5 = 150
        assertEquals(150.0, trades[0].pnlRub, 1e-6)
    }

    @Test
    fun matchSpreadTrades_partialFillUsesPriceBasedPnl() {
        // Выход с частичным fill: TATN продали только 35 из 360 (payment = 35×price).
        val t0 = 1_700_000_000_000L
        val events = pairSpreadLegs(
            listOf(
                op(t0, "TATN", 360.0, 596.9),
                op(t0 + 1_000, "TATNP", -360.0, 579.87),
                // выход: TATN −35 (payment 35×588.82), TATNP +360
                TInvestSpreadOp(t0 + 86_400_000, "TATN", -35.0, 588.82, 35.0 * 588.82),
                op(t0 + 86_401_000, "TATNP", 360.0, 574.49),
            ),
        )
        val trades = matchSpreadTrades(events, emptyList())
        assertEquals(1, trades.size)
        // price-based на 360 лотах: 360 × ((588.82−596.90) − (574.49−579.87)) = −972
        assertEquals(-972.0, trades[0].pnlRub, 1.0)
    }

    @Test
    fun matchSpreadTrades_openTradeNotReturned() {
        val t0 = 1_700_000_000_000L
        val events = pairSpreadLegs(
            listOf(
                op(t0, "TATN", 60.0, 590.0),
                op(t0 + 1_000, "TATNP", -60.0, 571.0),
            ),
        )
        assertTrue(matchSpreadTrades(events, emptyList()).isEmpty())
    }
}
