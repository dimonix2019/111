package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun brokerSnap(
        side: ZStrategyPosition,
        tatnLots: Int,
        tatnpLots: Int,
    ) = BrokerSpreadPositionSnap(
        side = side,
        tatnLots = tatnLots,
        tatnpLots = tatnpLots,
        expectedYieldRub = null,
        tatnPriceRub = 577.0,
        tatnpPriceRub = 558.0,
        portfolioTotalRub = 100_000.0,
    )

    @Test
    fun flattenLegsForBrokerSnap_longSellsTatnBuysTatnp() {
        val legs = flattenLegsForBrokerSnap(brokerSnap(ZStrategyPosition.Long, 246, -246))
        assertEquals(2, legs.size)
        assertEquals("TATN", legs[0].ticker)
        assertFalse(legs[0].buy)
        assertEquals(246, legs[0].lots)
        assertEquals("TATNP", legs[1].ticker)
        assertTrue(legs[1].buy)
        assertEquals(246, legs[1].lots)
    }

    @Test
    fun flattenLegsForBrokerSnap_shortBuysTatnSellsTatnp() {
        val legs = flattenLegsForBrokerSnap(brokerSnap(ZStrategyPosition.Short, -80, 80))
        assertEquals("TATN", legs[0].ticker)
        assertTrue(legs[0].buy)
        assertEquals(80, legs[0].lots)
        assertEquals("TATNP", legs[1].ticker)
        assertFalse(legs[1].buy)
        assertEquals(80, legs[1].lots)
    }

    @Test
    fun flattenLegsForBrokerSnap_oneSidedLeftover() {
        val legs = flattenLegsForBrokerSnap(brokerSnap(ZStrategyPosition.Flat, 40, 0))
        assertEquals(1, legs.size)
        assertEquals("TATN", legs[0].ticker)
        assertFalse(legs[0].buy)
        assertEquals(40, legs[0].lots)
    }

    @Test
    fun flattenLegsForBrokerSnap_flatEmpty() {
        assertTrue(flattenLegsForBrokerSnap(brokerSnap(ZStrategyPosition.Flat, 0, 0)).isEmpty())
    }

    @Test
    fun tradeTabManualEntryBlockReason_blocksLeftoverLegs() {
        assertTrue(
            tradeTabManualEntryBlockReason(brokerSnap(ZStrategyPosition.Flat, 40, 0))
                ?.contains("экстренное закрытие") == true,
        )
        assertEquals(null, tradeTabManualEntryBlockReason(brokerSnap(ZStrategyPosition.Flat, 0, 0)))
    }

    @Test
    fun shouldOfferPendingVirtualTrade_rejectsSameBarAfterCancel() {
        val ts = 1_700_000_000_000L
        assertFalse(
            shouldOfferPendingVirtualTrade(
                rejectedTimestampMillis = ts,
                rejectedTypeName = StrategySignalType.EnterLong.name,
                signalType = StrategySignalType.EnterLong,
                timestampMillis = ts,
            ),
        )
        assertTrue(
            shouldOfferPendingVirtualTrade(
                rejectedTimestampMillis = ts,
                rejectedTypeName = StrategySignalType.EnterLong.name,
                signalType = StrategySignalType.EnterShort,
                timestampMillis = ts,
            ),
        )
        assertTrue(
            shouldOfferPendingVirtualTrade(
                rejectedTimestampMillis = ts,
                rejectedTypeName = StrategySignalType.EnterLong.name,
                signalType = StrategySignalType.EnterLong,
                timestampMillis = ts + 900_000L,
            ),
        )
    }

    @Test
    fun pendingVirtualTradeCard_hiddenWhenTradeAlreadyOpen() {
        val pending = PendingVirtualTradeProposal(
            signalType = StrategySignalType.EnterLong,
            zScore = 1.44,
            timestampMillis = 1_700_000_000_000L,
            entryThreshold = 1.3,
            exitThreshold = 1.2,
            receivedAtMillis = 1_700_000_000_000L,
        )
        assertTrue(shouldShowPendingVirtualTradeCard(pending, tradeOpen = false))
        assertFalse(shouldShowPendingVirtualTradeCard(pending, tradeOpen = true))
        assertFalse(
            shouldShowPendingVirtualTradeCard(
                pending,
                tradeOpen = false,
                savedPosition = ZStrategyPosition.Long,
            ),
        )
        assertFalse(shouldShowPendingVirtualTradeCard(null, tradeOpen = false))
        assertFalse(shouldRestorePendingVirtualFromJournal(ZStrategyPosition.Long))
        assertFalse(shouldRestorePendingVirtualFromJournal(ZStrategyPosition.Short))
        assertTrue(shouldRestorePendingVirtualFromJournal(ZStrategyPosition.Flat))
    }
}
