package com.example.moexmvp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexTradeScreenTest {
    @Test
    fun detectBrokerSpreadPosition_figiOnlyWithoutTicker() {
        val portfolio = JSONObject(
            """
            {
              "totalAmountPortfolio": {"units": "100078", "nano": 0, "currency": "rub"},
              "positions": [
                {
                  "figi": "BBG004RVFFC0",
                  "instrumentUid": "TATN_TQBR",
                  "quantity": {"units": "246", "nano": 0},
                  "currentPrice": {"units": "577", "nano": 100000000, "currency": "rub"},
                  "expectedYield": {"units": "-120", "nano": 0, "currency": "rub"}
                },
                {
                  "figi": "BBG004S68829",
                  "instrument_uid": "TATNP_TQBR",
                  "quantity": {"units": "-246", "nano": 0},
                  "currentPrice": {"units": "558", "nano": 400000000, "currency": "rub"},
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
        assertTrue(snap.hasBrokerLegs)
    }

    @Test
    fun detectBrokerSpreadPosition_oneSidedLeftoverIsNotFlatForClose() {
        val portfolio = JSONObject(
            """
            {
              "positions": [
                {
                  "ticker": "TATN",
                  "quantity": {"units": "40", "nano": 0},
                  "currentPrice": {"units": "577", "nano": 0, "currency": "rub"}
                }
              ]
            }
            """.trimIndent()
        )
        val snap = detectBrokerSpreadPosition(portfolio)
        assertEquals(ZStrategyPosition.Flat, snap.side)
        assertTrue(snap.hasBrokerLegs)
        assertEquals(40, snap.tatnLots)
        assertEquals(0, snap.tatnpLots)
    }

    @Test
    fun resolveTatnTatnpTicker_prefersTatnpBeforeTatnSubstring() {
        val tatnp = JSONObject("""{"figi":"BBG004S68829","instrumentUid":"TATNP_TQBR"}""")
        val tatn = JSONObject("""{"FIGI":"BBG004RVFFC0"}""")
        assertEquals("TATNP", resolveTatnTatnpTicker(tatnp))
        assertEquals("TATN", resolveTatnTatnpTicker(tatn))
    }

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
    fun computeMarginCallHeadroom_usesLiquidMinusMinimal() {
        val margin = MarginAttributesSnapshot(
            liquidPortfolioRub = 9_249.0,
            correctedMarginRub = 4_681.0,
            startingMarginRub = 4_681.0,
            amountOfMissingFundsRub = null,
            minimalMarginRub = 2_340.0,
        )
        val head = computeMarginCallHeadroom(margin)!!
        assertEquals(6_909.0, head.freeRub, 0.5)
        assertEquals(MarginCallHeadroomZone.Green, head.zone)
        assertTrue(head.freeRub > 4_630.0)
    }

    @Test
    fun computeMarginCallHeadroom_zonesByPctOfLiquid() {
        val margin = MarginAttributesSnapshot(
            liquidPortfolioRub = 200_000.0,
            correctedMarginRub = 100_000.0,
            startingMarginRub = 100_000.0,
            amountOfMissingFundsRub = null,
            minimalMarginRub = 100_000.0,
        )
        val green = computeMarginCallHeadroom(margin)!!
        assertEquals(MarginCallHeadroomZone.Green, green.zone)
        assertEquals(100_000.0, green.freeRub, 0.01)
        assertEquals(50.0, green.pct, 0.01)

        val yellow = computeMarginCallHeadroom(
            margin.copy(liquidPortfolioRub = 115_000.0),
        )!!
        assertEquals(MarginCallHeadroomZone.Yellow, yellow.zone)
        assertTrue(yellow.pct in 10.0..20.0)

        val red = computeMarginCallHeadroom(
            margin.copy(liquidPortfolioRub = 105_000.0),
        )!!
        assertEquals(MarginCallHeadroomZone.Red, red.zone)
        assertTrue(red.pct < 10.0)
    }

    @Test
    fun computeMarginCallHeadroom_fallsBackToHalfStarting() {
        val head = computeMarginCallHeadroom(
            MarginAttributesSnapshot(
                liquidPortfolioRub = 10_000.0,
                correctedMarginRub = 4_000.0,
                startingMarginRub = 4_000.0,
                amountOfMissingFundsRub = null,
            ),
        )!!
        assertEquals(2_000.0, head.minimalMarginRub, 0.01)
        assertEquals(8_000.0, head.freeRub, 0.01)
    }

    @Test
    fun tradeOpenLegsTitle_oneSidedDoesNotFakePair() {
        assertEquals("Ноги TATN 53 / TATNP 0", tradeOpenLegsTitle("Ноги", 53, 0))
        assertEquals("Long 53+53 лот", tradeOpenLegsTitle("Long", 53, -53))
    }

    @Test
    fun spreadEntryLegPlan_sellsShortLegFirst() {
        val longLegs = spreadEntryLegPlan(StrategySignalType.EnterLong)
        assertEquals("TATNP", longLegs[0].ticker)
        assertFalse(longLegs[0].buy)
        assertEquals("TATN", longLegs[1].ticker)
        assertTrue(longLegs[1].buy)

        val shortLegs = spreadEntryLegPlan(StrategySignalType.EnterShort)
        assertEquals("TATN", shortLegs[0].ticker)
        assertFalse(shortLegs[0].buy)
        assertEquals("TATNP", shortLegs[1].ticker)
        assertTrue(shortLegs[1].buy)
    }

    @Test
    fun canonicalMoexShareInstrumentId_replacesTickerTqbrWithFigi() {
        assertEquals(TINKOFF_MOEX_TATNP_FIGI, canonicalMoexShareInstrumentId("TATNP", "TATNP_TQBR"))
        assertEquals(TINKOFF_MOEX_TATN_FIGI, canonicalMoexShareInstrumentId("TATN", null))
        assertEquals("BBG004RVFFC0", canonicalMoexShareInstrumentId("TATN", "BBG004RVFFC0"))
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

    @Test
    fun takeProfitRubAndPercent_roundTripFromTwoPercent() {
        val deposit = 9_526.0
        val rub = takeProfitRubFromPercent(DEFAULT_TAKE_PROFIT_PCT, deposit)
        assertEquals(190.52, rub, 0.01)
        assertEquals(2.0, takeProfitPercentFromRub(rub, deposit), 1e-9)
    }

    @Test
    fun coerceTakeProfitPct_defaultsAndClamps() {
        assertEquals(DEFAULT_TAKE_PROFIT_PCT, coerceTakeProfitPct(0.0), 0.0)
        assertEquals(DEFAULT_TAKE_PROFIT_PCT, coerceTakeProfitPct(Double.NaN), 0.0)
        assertEquals(TAKE_PROFIT_PCT_MIN, coerceTakeProfitPct(0.01), 1e-9)
        assertEquals(TAKE_PROFIT_PCT_MAX, coerceTakeProfitPct(99.0), 1e-9)
        assertEquals(3.5, coerceTakeProfitPct(3.5), 1e-9)
    }

    @Test
    fun resolveTakeProfitPctFromInputs_defaultWhenEmpty() {
        assertEquals(DEFAULT_TAKE_PROFIT_PCT, resolveTakeProfitPctFromInputs("", "", 9_526.0)!!, 0.0)
        assertEquals(3.0, resolveTakeProfitPctFromInputs("3", "", 9_526.0)!!, 0.0)
        val fromRub = resolveTakeProfitPctFromInputs("", "191", 9_526.0)!!
        assertTrue(fromRub in 2.0..2.1)
        assertNull(resolveTakeProfitPctFromInputs("0", "", 9_526.0))
    }

    @Test
    fun parseAndFilterTakeProfitInput_acceptsComma() {
        assertEquals(2.5, parseTakeProfitNumber("2,5")!!, 1e-9)
        assertEquals("2,5", filterTakeProfitInput("2,5abc"))
        assertEquals("2", formatTakeProfitPctInput(2.0))
        assertEquals("191", formatTakeProfitRubInput(190.52))
    }

    @Test
    fun shouldFireTakeProfit_usesYieldVsDepositPercent() {
        assertFalse(shouldFireTakeProfit(expectedYieldRub = 190.0, depositRub = 9_526.0, takeProfitPct = 2.0))
        assertTrue(shouldFireTakeProfit(expectedYieldRub = 191.0, depositRub = 9_526.0, takeProfitPct = 2.0))
        assertFalse(shouldFireTakeProfit(expectedYieldRub = 500.0, depositRub = 9_526.0, takeProfitPct = 0.0))
        assertFalse(shouldFireTakeProfit(expectedYieldRub = null, depositRub = 9_526.0, takeProfitPct = 2.0))
        assertFalse(shouldFireTakeProfit(expectedYieldRub = -10.0, depositRub = 9_526.0, takeProfitPct = 2.0))
    }

    @Test
    fun takeProfitDepositRub_prefersPortfolioThenCash() {
        assertEquals(9_526.0, takeProfitDepositRub(9_526.0, 1_000.0, 8_000.0), 0.0)
        assertEquals(1_000.0, takeProfitDepositRub(null, 1_000.0, 8_000.0), 0.0)
        assertEquals(8_000.0, takeProfitDepositRub(null, null, 8_000.0), 0.0)
        assertEquals(0.0, takeProfitDepositRub(null, null, null), 0.0)
    }

    @Test
    fun closeNowPnl_longUsesBidForTatnAndAskForTatnp() {
        val quotes = PairQuotes(
            tatn = ShareQuote(last = 619.0, bid = 618.0, ask = 620.0),
            tatnp = ShareQuote(last = 599.0, bid = 598.0, ask = 600.0),
        )
        val pnl = computeCloseNowPnl(
            tatnLots = 53,
            tatnpLots = -53,
            fillTatnRub = 617.5,
            fillTatnpRub = 598.0,
            quotes = quotes,
            fallbackTatnLast = 619.0,
            fallbackTatnpLast = 599.0,
            depositRub = 50_000.0,
            cashRub = 0.0,
            entryTimeMsk = "2026-09-12 10:00",
            nowMillis = java.time.LocalDateTime.of(2026, 9, 12, 12, 0)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli(),
        )!!
        assertEquals(618.0, pnl.closeTatnRub!!, 1e-9)
        assertEquals(600.0, pnl.closeTatnpRub!!, 1e-9)
        assertEquals("book", pnl.quotesMode)
        val gross = 53 * (618.0 - 617.5) + (-53) * (600.0 - 598.0)
        assertEquals(gross, pnl.grossRub, 0.01)
        assertEquals(0.0, pnl.overnightShortRub, 0.01)
        assertEquals(0.0, pnl.overnightMarginLoanRub, 0.01)
    }

    @Test
    fun closeNowPnl_premiumCommissionsAndOvernightTiers() {
        val quotes = PairQuotes(
            tatn = ShareQuote(last = 620.0, bid = 620.0, ask = 620.1),
            tatnp = ShareQuote(last = 600.0, bid = 599.9, ask = 600.0),
        )
        val pnl = computeCloseNowPnl(
            tatnLots = 53,
            tatnpLots = -53,
            fillTatnRub = 617.5,
            fillTatnpRub = 598.0,
            quotes = quotes,
            fallbackTatnLast = 620.0,
            fallbackTatnpLast = 600.0,
            depositRub = 50_000.0,
            cashRub = -23_479.0,
            entryTimeMsk = "2026-09-10 18:00",
            nowMillis = java.time.LocalDateTime.of(2026, 9, 12, 12, 0)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli(),
        )!!
        val entryNotional = 53 * 617.5 + 53 * 598.0
        val exitNotional = 53 * 620.0 + 53 * 600.0
        assertEquals(entryNotional * 0.0004, pnl.entryCommissionRub, 0.02)
        assertEquals(exitNotional * 0.0004, pnl.exitCommissionRub, 0.02)
        assertEquals(2L, pnl.overnightDays)
        assertEquals(35.0 * 2, pnl.overnightShortRub, 0.01)
        assertEquals(23_479.0 * 0.00033 * 2, pnl.overnightMarginLoanRub, 0.05)
        val expectedNet = pnl.grossRub - pnl.entryCommissionRub - pnl.exitCommissionRub -
            pnl.overnightShortRub - pnl.overnightMarginLoanRub
        assertEquals(expectedNet, pnl.netRub, 0.01)
        assertTrue(pnl.netRub < pnl.grossRub)
        assertTrue(formatCloseNowBreakdown(pnl).contains("комиссия"))
        assertTrue(formatCloseNowBreakdown(pnl).contains("перенос"))
        assertTrue(formatCloseNowBreakdown(pnl).contains("маржа"))
    }

    @Test
    fun closeNowPnl_oneSidedLeftoverTatnOnly() {
        val quotes = PairQuotes(
            tatn = ShareQuote(last = 620.0, bid = 619.5, ask = 620.5),
            tatnp = ShareQuote(),
        )
        val pnl = computeCloseNowPnl(
            tatnLots = 53,
            tatnpLots = 0,
            fillTatnRub = 617.5,
            fillTatnpRub = null,
            quotes = quotes,
            fallbackTatnLast = 620.0,
            fallbackTatnpLast = null,
            depositRub = 32_000.0,
            cashRub = 1_000.0,
            entryTimeMsk = "2026-09-12 10:00",
            nowMillis = java.time.LocalDateTime.of(2026, 9, 12, 12, 0)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli(),
        )!!
        assertEquals(53 * (619.5 - 617.5), pnl.grossRub, 0.01)
        assertEquals(0.0, pnl.overnightShortRub, 0.01)
        assertEquals(53 * 617.5 * 0.0004, pnl.entryCommissionRub, 0.02)
        assertEquals(53 * 619.5 * 0.0004, pnl.exitCommissionRub, 0.02)
        assertEquals("book", pnl.quotesMode)
    }

    @Test
    fun closeNowPnl_staleIssLastWithoutBook_usesBrokerMark() {
        val pnl = computeCloseNowPnl(
            tatnLots = 57,
            tatnpLots = -57,
            fillTatnRub = 618.7,
            fillTatnpRub = 593.0,
            quotes = PairQuotes(
                tatn = ShareQuote(last = 618.7),
                tatnp = ShareQuote(last = 599.4),
            ),
            fallbackTatnLast = 617.8,
            fallbackTatnpLast = 593.8,
            depositRub = 9_973.0,
            cashRub = 8_508.0,
            entryTimeMsk = "2026-09-12 11:35",
            nowMillis = java.time.LocalDateTime.of(2026, 9, 12, 11, 38)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli(),
        )!!
        assertEquals(617.8 - marketOrderHalfSpreadRub(617.8), pnl.closeTatnRub!!, 1e-9)
        assertEquals(593.8 + marketOrderHalfSpreadRub(593.8), pnl.closeTatnpRub!!, 1e-9)
        assertTrue(pnl.note.contains("портфеля"))
        assertEquals("last_fallback", pnl.quotesMode)
        val stale = computeCloseNowPnl(
            tatnLots = 57,
            tatnpLots = -57,
            fillTatnRub = 618.7,
            fillTatnpRub = 593.0,
            quotes = PairQuotes(
                tatn = ShareQuote(last = 618.7),
                tatnp = ShareQuote(last = 599.4),
            ),
            fallbackTatnLast = 618.7,
            fallbackTatnpLast = 599.4,
            depositRub = 9_973.0,
            cashRub = 8_508.0,
            entryTimeMsk = "2026-09-12 11:35",
            nowMillis = java.time.LocalDateTime.of(2026, 9, 12, 11, 38)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli(),
        )!!
        assertTrue(kotlin.math.abs(pnl.netRub - stale.netRub) > 1.0)
    }

    @Test
    fun lastForCloseSynth_prefersBrokerWhenIssHasNoBook() {
        assertEquals(
            617.8,
            lastForCloseSynth(ShareQuote(last = 618.7), 617.8),
        )
        assertEquals(
            619.0,
            lastForCloseSynth(ShareQuote(last = 619.0, bid = 618.0, ask = 620.0), 617.8),
        )
    }

    @Test
    fun closeNowPnl_lastFallbackUsesMarketSlipWhenNoBook() {
        val pnl = computeCloseNowPnl(
            tatnLots = 10,
            tatnpLots = -10,
            fillTatnRub = 600.0,
            fillTatnpRub = 580.0,
            quotes = PairQuotes(
                tatn = ShareQuote(last = 601.0),
                tatnp = ShareQuote(last = 581.0),
            ),
            fallbackTatnLast = 601.0,
            fallbackTatnpLast = 581.0,
            depositRub = 20_000.0,
            cashRub = 0.0,
            entryTimeMsk = null,
        )!!
        assertEquals(601.0 - marketOrderHalfSpreadRub(601.0), pnl.closeTatnRub!!, 1e-9)
        assertEquals(581.0 + marketOrderHalfSpreadRub(581.0), pnl.closeTatnpRub!!, 1e-9)
        assertEquals("last_fallback", pnl.quotesMode)
        assertTrue(marketOrderHalfSpreadRub(601.0) > CLOSE_NOW_HALF_TICK_RUB)
    }

    @Test
    fun closeNowPnl_sept12Long57_forecastMatchesCashLeftNotHalfTick() {
        val pnl = computeCloseNowPnl(
            tatnLots = 57,
            tatnpLots = -57,
            fillTatnRub = 618.5,
            fillTatnpRub = 592.8,
            quotes = null,
            fallbackTatnLast = 618.4,
            fallbackTatnpLast = 593.1,
            depositRub = 10_000.0,
            cashRub = 8_504.99,
            entryTimeMsk = "2026-09-12 14:56",
            nowMillis = java.time.LocalDateTime.of(2026, 9, 12, 15, 21)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli(),
        )!!
        val oldHalfTickNet = run {
            val closeTn = 618.4 - CLOSE_NOW_HALF_TICK_RUB
            val closeTp = 593.1 + CLOSE_NOW_HALF_TICK_RUB
            val gross = 57 * (closeTn - 618.5) + (-57) * (closeTp - 592.8)
            val entry = (57 * 618.5 + 57 * 592.8) * 0.0004
            val exit = (57 * closeTn + 57 * closeTp) * 0.0004
            gross - entry - exit
        }
        assertEquals(618.4 - marketOrderHalfSpreadRub(618.4), pnl.closeTatnRub!!, 1e-9)
        assertEquals(593.1 + marketOrderHalfSpreadRub(593.1), pnl.closeTatnpRub!!, 1e-9)
        assertTrue(oldHalfTickNet > -90.0 && oldHalfTickNet < -70.0)
        assertTrue(pnl.netRub < -150.0)
        assertTrue(kotlin.math.abs(pnl.netRub - oldHalfTickNet) > 70.0)
        val cashAfter = pnl.cashAfterCloseRub!!
        assertEquals(9_827.37, cashAfter, 8.0)
        assertEquals(10_000.0 - 9_827.37, 10_000.0 - cashAfter, 8.0)
        assertTrue(formatCloseNowBreakdown(pnl).contains("на счёте"))
    }

    @Test
    fun vwapWalk_sept12ChildFills() {
        val tatnBid = vwapWalk(
            listOf(BookLevel(617.6, 23.0), BookLevel(617.5, 34.0)),
            57.0,
        )!!
        val tatnpAsk = vwapWalk(
            listOf(BookLevel(593.8, 25.0), BookLevel(593.9, 32.0)),
            57.0,
        )!!
        assertEquals(617.5403551, tatnBid, 1e-4)
        assertEquals(593.85614, tatnpAsk, 1e-4)
        val pnl = computeCloseNowPnl(
            tatnLots = 57,
            tatnpLots = -57,
            fillTatnRub = 618.5,
            fillTatnpRub = 592.8,
            quotes = PairQuotes(
                tatn = ShareQuote(last = 618.4, bid = tatnBid, ask = 618.5),
                tatnp = ShareQuote(last = 593.1, bid = 593.0, ask = tatnpAsk),
                source = "tinkoff",
            ),
            fallbackTatnLast = 618.4,
            fallbackTatnpLast = 593.1,
            depositRub = 10_000.0,
            cashRub = 8_504.99,
            entryTimeMsk = "2026-09-12 14:56",
            nowMillis = java.time.LocalDateTime.of(2026, 9, 12, 15, 21)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli(),
        )!!
        assertEquals("book", pnl.quotesMode)
        assertTrue(pnl.note.contains("T‑Invest"))
        assertEquals(9_827.37, pnl.cashAfterCloseRub!!, 1.0)
    }

    @Test
    fun parseTinkoffOrderBookQuote_vwapOnLots() {
        val json = JSONObject(
            """
            {
              "lastPrice": {"units": "618", "nano": 400000000},
              "bids": [
                {"price": {"units": "617", "nano": 600000000}, "quantity": "23"},
                {"price": {"units": "617", "nano": 500000000}, "quantity": "34"}
              ],
              "asks": [
                {"price": {"units": "618", "nano": 500000000}, "quantity": "10"}
              ]
            }
            """.trimIndent(),
        )
        val q = parseTinkoffOrderBookQuote(json, 57.0)!!
        assertEquals(617.5403551, q.bid!!, 1e-4)
        assertEquals(618.5, q.ask!!, 1e-9)
        assertEquals(618.4, q.last!!, 1e-9)
    }

    @Test
    fun mergeCloseNowQuotes_prefersTinkoffBook() {
        val tinkoff = PairQuotes(
            tatn = ShareQuote(last = 618.4, bid = 617.54, ask = 618.5),
            tatnp = ShareQuote(),
            source = "tinkoff",
        )
        val iss = PairQuotes(
            tatn = ShareQuote(last = 619.0, bid = 618.9, ask = 619.1),
            tatnp = ShareQuote(last = 593.2, bid = 593.1, ask = 593.3),
            source = "iss",
        )
        val merged = mergeCloseNowQuotes(tinkoff, iss)!!
        assertEquals(617.54, merged.tatn.bid!!, 1e-9)
        assertEquals(593.3, merged.tatnp.ask!!, 1e-9)
        assertEquals("tinkoff+iss", merged.source)
    }

    @Test
    fun closeNowPnl_sept12_1607_dealerVwapMatchesActualCash() {
        val tatnBid = vwapWalk(
            listOf(BookLevel(617.6, 40.0), BookLevel(617.5, 17.0)),
            57.0,
        )!!
        val tatnpAsk = vwapWalk(
            listOf(BookLevel(593.9, 14.0), BookLevel(594.0, 43.0)),
            57.0,
        )!!
        assertEquals(617.570175, tatnBid, 1e-4)
        assertEquals(593.975439, tatnpAsk, 1e-4)
        val slip = computeCloseNowPnl(
            tatnLots = 57,
            tatnpLots = -57,
            fillTatnRub = 618.363158,
            fillTatnpRub = 592.975439,
            quotes = null,
            fallbackTatnLast = 617.6,
            fallbackTatnpLast = 593.0,
            depositRub = 9_990.0,
            cashRub = 8_525.0,
            entryTimeMsk = "2026-09-12 15:55",
            nowMillis = java.time.LocalDateTime.of(2026, 9, 12, 16, 7)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli(),
        )!!
        val book = computeCloseNowPnl(
            tatnLots = 57,
            tatnpLots = -57,
            fillTatnRub = 618.363158,
            fillTatnpRub = 592.975439,
            quotes = PairQuotes(
                tatn = ShareQuote(last = 617.6, bid = tatnBid, ask = 617.7),
                tatnp = ShareQuote(last = 593.0, bid = 592.9, ask = tatnpAsk),
                source = "tinkoff",
            ),
            fallbackTatnLast = 617.6,
            fallbackTatnpLast = 593.0,
            depositRub = 9_990.0,
            cashRub = 8_525.0,
            entryTimeMsk = "2026-09-12 15:55",
            nowMillis = java.time.LocalDateTime.of(2026, 9, 12, 16, 7)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli(),
        )!!
        assertEquals("book", book.quotesMode)
        assertEquals(-157.45, book.netRub, 1.0)
        assertEquals(9_842.28, book.cashAfterCloseRub!!, 1.5)
        assertTrue(slip.netRub < book.netRub - 30.0)
        assertEquals(616.7, kotlin.math.round(slip.closeTatnRub!! * 10) / 10.0, 0.05)
    }

    @Test
    fun closeNowPnl_partialDealerSidesCountAsBook() {
        val pnl = computeCloseNowPnl(
            tatnLots = 57,
            tatnpLots = -57,
            fillTatnRub = 618.363158,
            fillTatnpRub = 592.975439,
            quotes = PairQuotes(
                tatn = ShareQuote(last = 617.6, bid = 617.570175),
                tatnp = ShareQuote(last = 593.0, ask = 593.975439),
                source = "tinkoff",
            ),
            fallbackTatnLast = 617.6,
            fallbackTatnpLast = 593.0,
            depositRub = 9_990.0,
            cashRub = 8_525.0,
            entryTimeMsk = "2026-09-12 15:55",
            nowMillis = java.time.LocalDateTime.of(2026, 9, 12, 16, 7)
                .atZone(java.time.ZoneId.of("Europe/Moscow"))
                .toInstant()
                .toEpochMilli(),
        )!!
        assertEquals("book", pnl.quotesMode)
        assertEquals(617.570175, pnl.closeTatnRub!!, 1e-6)
        assertEquals(593.975439, pnl.closeTatnpRub!!, 1e-6)
        assertEquals(-157.45, pnl.netRub, 1.0)
    }

    @Test
    fun parseTinkoffOrderBookQuote_unwrapsDealerEnvelope() {
        val json = JSONObject(
            """
            {
              "getOrderBookResponse": {
                "last_price": {"units": "617", "nano": 600000000},
                "Bids": [
                  {"Price": {"units": "617", "nano": 600000000}, "Quantity": 40},
                  {"price": {"units": "617", "nano": 500000000}, "quantity": "17"}
                ],
                "Asks": [
                  {"price": {"units": "617", "nano": 700000000}, "quantity": 8}
                ]
              }
            }
            """.trimIndent(),
        )
        val q = parseTinkoffOrderBookQuote(json, 57.0)!!
        assertEquals(617.570175, q.bid!!, 1e-4)
        assertEquals(617.7, q.ask!!, 1e-9)
        assertEquals(617.6, q.last!!, 1e-9)
    }

    @Test
    fun parseIssOrderbookQuote_vwapOnLots() {
        val json = """
            {
              "orderbook": {
                "columns": ["SECID", "BUYSELL", "PRICE", "QUANTITY"],
                "data": [
                  ["TATN", "B", 617.6, 40],
                  ["TATN", "B", 617.5, 17],
                  ["TATN", "S", 617.7, 8]
                ]
              }
            }
        """.trimIndent()
        val q = parseIssOrderbookQuote(json, 57.0)!!
        assertEquals(617.570175, q.bid!!, 1e-4)
        assertEquals(617.7, q.ask!!, 1e-9)
    }

    @Test
    fun canonicalCloseNowFigi_replacesTickerTqbr() {
        assertEquals(TINKOFF_MOEX_TATN_FIGI, canonicalCloseNowFigi("TATN_TQBR", tatnp = false))
        assertEquals(TINKOFF_MOEX_TATNP_FIGI, canonicalCloseNowFigi("TATNP_TQBR", tatnp = true))
        assertEquals(TINKOFF_MOEX_TATN_FIGI, canonicalCloseNowFigi(TINKOFF_MOEX_TATN_FIGI, tatnp = false))
    }

    @Test
    fun marketOrderHalfSpreadRub_matchesSept12Walk() {
        assertEquals(618.4 * 0.0014, marketOrderHalfSpreadRub(618.4), 1e-9)
        assertEquals(0.40, marketOrderHalfSpreadRub(100.0), 1e-9)
    }

    @Test
    fun parseIssMarketdataQuote_readsBidOfferLast() {
        val json = """
            {
              "marketdata": {
                "columns": ["SECID", "LAST", "BID", "OFFER"],
                "data": [["TATN", 617.5, 617.4, 617.6]]
              }
            }
        """.trimIndent()
        val q = parseIssMarketdataQuote(json)!!
        assertEquals(617.5, q.last!!, 1e-9)
        assertEquals(617.4, q.bid!!, 1e-9)
        assertEquals(617.6, q.ask!!, 1e-9)
    }

    @Test
    fun tradeScreenPoll_isFiveSecondsAndDoesNotChangeBackgroundPoll() {
        assertEquals(5_000L, TRADE_SCREEN_POLL_MS)
        assertEquals(15_000L, BROKER_ACCOUNT_POLL_MS)
        assertTrue(TRADE_SCREEN_POLL_MS < BROKER_ACCOUNT_POLL_MS)
    }

    @Test
    fun formatCloseNowHeroRub_usesSignedSpaces() {
        assertTrue(formatCloseNowHeroRub(1234.4).contains("1 234"))
        assertTrue(formatCloseNowHeroRub(1234.4).startsWith("+"))
        assertTrue(formatCloseNowHeroRub(-50.2).startsWith("-"))
    }
}
