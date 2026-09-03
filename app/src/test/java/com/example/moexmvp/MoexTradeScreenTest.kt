package com.example.moexmvp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexTradeScreenTest {
    @Test
    fun detectBrokerSpreadPosition_longPair() {
        val portfolio = JSONObject(
            """
            {
              "totalAmountPortfolio": {"units": "100078", "nano": 0, "currency": "rub"},
              "positions": [
                {
                  "ticker": "TATN",
                  "quantity": {"units": "246", "nano": 0},
                  "currentPrice": {"units": "577", "nano": 100000000, "currency": "rub"},
                  "averagePositionPrice": {"units": "577", "nano": 100000000, "currency": "rub"},
                  "expectedYield": {"units": "-120", "nano": 0, "currency": "rub"}
                },
                {
                  "ticker": "TATNP",
                  "quantity": {"units": "-246", "nano": 0},
                  "currentPrice": {"units": "558", "nano": 400000000, "currency": "rub"},
                  "averagePositionPrice": {"units": "558", "nano": 400000000, "currency": "rub"},
                  "expectedYield": {"units": "-101", "nano": 0, "currency": "rub"}
                }
              ]
            }
            """.trimIndent()
        )
        val snap = detectBrokerSpreadPosition(portfolio)
        assertEquals(ZStrategyPosition.Long, snap.side)
        assertEquals(246, snap.tatnLots)
        assertEquals(-246, snap.tatnpLots)
        assertEquals(-221.0, snap.expectedYieldRub!!, 0.01)
        assertEquals(100_078.0, snap.portfolioTotalRub!!, 0.01)
        assertTrue(snap.spreadPercent!! > 3.0)
    }

    @Test
    fun parseSpreadLegAveragePrices_readsBothLegs() {
        val portfolio = JSONObject(
            """
            {
              "positions": [
                {"ticker": "TATN", "averagePositionPrice": {"units": "577", "nano": 100000000}},
                {"ticker": "TATNP", "averagePositionPrice": {"units": "558", "nano": 400000000}}
              ]
            }
            """.trimIndent()
        )
        val avg = parseSpreadLegAveragePrices(portfolio)
        assertEquals(577.1, avg.tatnAvgPriceRub!!, 0.01)
        assertEquals(558.4, avg.tatnpAvgPriceRub!!, 0.01)
    }

    @Test
    fun computeSpreadNotionalRub_sumsBothLegs() {
        val n = computeSpreadNotionalRub(
            tatnLots = 246,
            tatnpLots = -246,
            tatnPriceRub = 577.1,
            tatnpPriceRub = 558.4,
        )!!
        assertEquals(279_333.0, n, 1.0)
    }

    @Test
    fun mainTabNavTabs_marketsFirstWebDeskLast() {
        val tabs = MainTab.navTabs
        assertEquals(MainTab.Markets, tabs.first())
        assertEquals(MainTab.WebDesk, tabs[tabs.size - 2])
        assertEquals(MainTab.About, tabs.last())
        assertTrue(MainTab.Trade in tabs)
    }

    @Test
    fun computeMarginCallHeadroom_zonesByPct() {
        val margin = MarginAttributesSnapshot(
            liquidPortfolioRub = 140_000.0,
            correctedMarginRub = 100_000.0,
            startingMarginRub = 100_000.0,
            amountOfMissingFundsRub = null,
        )
        val green = computeMarginCallHeadroom(margin)!!
        assertEquals(MarginCallHeadroomZone.Green, green.zone)
        assertEquals(40_000.0, green.freeRub, 0.01)
        assertEquals(40.0, green.pct, 0.01)

        val yellow = computeMarginCallHeadroom(
            margin.copy(liquidPortfolioRub = 120_000.0),
        )!!
        assertEquals(MarginCallHeadroomZone.Yellow, yellow.zone)
        assertEquals(20.0, yellow.pct, 0.01)

        val red = computeMarginCallHeadroom(
            margin.copy(liquidPortfolioRub = 105_000.0),
        )!!
        assertEquals(MarginCallHeadroomZone.Red, red.zone)
        assertEquals(5.0, red.pct, 0.01)
    }

    @Test
    fun takeProfitExitSpread_long_movesSpreadUp() {
        val exit = takeProfitExitSpread(
            side = ZStrategyPosition.Long,
            entrySpreadPercent = 3.35,
            depositRub = 60_000.0,
            effNotionalRub = 280_000.0,
            takeProfitPct = 2.0,
            exitCommissionRub = 112.0,
            overnightRub = 175.0,
        )!!
        assertTrue(exit > 3.35)
    }

    @Test
    fun takeProfitExitSpread_short_movesSpreadDown() {
        val exit = takeProfitExitSpread(
            side = ZStrategyPosition.Short,
            entrySpreadPercent = 5.5,
            depositRub = 60_000.0,
            effNotionalRub = 280_000.0,
            takeProfitPct = 2.0,
            exitCommissionRub = 112.0,
            overnightRub = 0.0,
        )!!
        assertTrue(exit < 5.5)
    }

    @Test
    fun computeTakeProfitForecast_netNearTwoPctOfDeposit() {
        val forecast = computeTakeProfitForecast(
            side = ZStrategyPosition.Long,
            entrySpreadPercent = 3.35,
            depositRub = 60_000.0,
            notionalRub = 280_000.0,
            lots = 246,
            fillTatnRub = 577.1,
            fillTatnpRub = 558.4,
            entryTimeMsk = "2026-09-01 21:00",
            takeProfitPct = 2.0,
        )!!
        assertTrue(forecast.exitSpreadPercent > 3.35)
        assertTrue(forecast.netPnlRub > 0)
        assertEquals(60_000.0, forecast.depositRub, 1.0)
        assertTrue(forecast.pnlPercentFromDeposit in 1.0..2.5)
    }

    @Test
    fun overnightFeePerDayRub_premiumTiers() {
        assertEquals(35.0, overnightFeePerDayRub(40_000.0), 0.01)
        assertEquals(175.0, overnightFeePerDayRub(200_000.0), 0.01)
    }
}
