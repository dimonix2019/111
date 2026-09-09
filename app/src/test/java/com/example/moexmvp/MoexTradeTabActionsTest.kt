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
    fun walkSpreadEpisodes_longRoundTrip() {
        val t0 = 1_700_000_000_000L
        val ops = listOf(
            op(t0, "TATN", qty = 60.0, price = 590.0),      // buy TATN
            op(t0 + 1_000, "TATNP", qty = -60.0, price = 571.0), // sell TATNP
            op(t0 + 86_400_000, "TATN", qty = -60.0, price = 595.0),  // sell TATN
            op(t0 + 86_401_000, "TATNP", qty = 60.0, price = 572.0),  // buy TATNP
        )
        val fees = listOf((t0 + 500L) to -20.0, (t0 + 86_400_500L) to -20.0)
        val episodes = walkSpreadEpisodes(ops, fees)
        assertEquals(1, episodes.size)
        val ep = episodes[0]
        assertEquals(60.0, ep.peakLots, 1e-9)
        // cash: −60×590 + 60×571 + 60×595 − 60×572 − 40 = −35400 + 34260 + 35700 − 34320 − 40 = 200
        assertEquals(200.0, ep.cashRub, 1e-6)
        assertEquals(590.0, ep.openVwap("TATN")!!, 1e-9)
        assertEquals(595.0, ep.closeVwap("TATN")!!, 1e-9)
    }

    @Test
    fun walkSpreadEpisodes_shortRoundTrip_andTwoEpisodes() {
        val t0 = 1_700_000_000_000L
        val ops = listOf(
            // SHORT: sell TATN, buy TATNP → flat
            op(t0, "TATN", qty = -60.0, price = 590.0),
            op(t0 + 1_000, "TATNP", qty = 60.0, price = 571.0),
            op(t0 + 3_600_000, "TATN", qty = 60.0, price = 588.0),
            op(t0 + 3_601_000, "TATNP", qty = -60.0, price = 571.5),
            // LONG сразу после
            op(t0 + 7_200_000, "TATN", qty = 60.0, price = 589.0),
            op(t0 + 7_201_000, "TATNP", qty = -60.0, price = 571.2),
            op(t0 + 10_800_000, "TATN", qty = -60.0, price = 589.5),
            op(t0 + 10_801_000, "TATNP", qty = 60.0, price = 571.0),
        )
        val episodes = walkSpreadEpisodes(ops, emptyList())
        assertEquals(2, episodes.size)
        // SHORT: +60×590 − 60×571 − 60×588 + 60×571.5 = 35400 − 34260 − 35280 + 34290 = 150
        assertEquals(150.0, episodes[0].cashRub, 1e-6)
        // LONG: −60×589 + 60×571.2 + 60×589.5 − 60×571 = −35340 + 34272 + 35370 − 34260 = 42
        assertEquals(42.0, episodes[1].cashRub, 1e-6)
    }

    @Test
    fun walkSpreadEpisodes_openEpisodeNotReturned() {
        val t0 = 1_700_000_000_000L
        val ops = listOf(
            op(t0, "TATN", qty = 60.0, price = 590.0),
            op(t0 + 1_000, "TATNP", qty = -60.0, price = 571.0),
        )
        assertTrue(walkSpreadEpisodes(ops, emptyList()).isEmpty())
    }
}
