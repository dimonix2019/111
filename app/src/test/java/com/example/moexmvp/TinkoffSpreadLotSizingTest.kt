package com.example.moexmvp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TinkoffSpreadLotSizingTest {
    @Test
    fun computeSpreadQuantityLots_usesCashReserveAndGo() {
        val sizing = computeSpreadQuantityLots(
            SpreadLotSizingInput(
                cashRub = 10_000.0,
                priceTatN = 650.0,
                priceTatNp = 480.0,
            )
        )
        assertEquals(9, sizing.quantityLots)
        assertEquals(2_500.0, sizing.reserveRub, 0.01)
        assertEquals(7_500.0, sizing.availableRub, 0.01)
    }

    @Test
    fun computeSpreadQuantityLots_prodUsesLeverageWhenMostlyFlat() {
        val sizing = computeSpreadQuantityLots(
            SpreadLotSizingInput(
                cashRub = 10_000.0,
                priceTatN = 576.0,
                priceTatNp = 542.0,
                liquidPortfolioRub = 10_000.0,
                correctedMarginRub = 0.0,
                leverageForNotional = 7.0,
            )
        )
        assertEquals(62, sizing.lotsFromLeverage)
        assertEquals(62, sizing.quantityLots)
    }

    /** Prod ~100k ликвидного ×7 → ~626 лот / ~700k номинал (после снятия cap 80 в 1.7.260). */
    @Test
    fun computeSpreadQuantityLots_prod100kLeverage7_about626Lots() {
        val sizing = computeSpreadQuantityLots(
            SpreadLotSizingInput(
                cashRub = 100_000.0,
                priceTatN = 576.0,
                priceTatNp = 542.0,
                liquidPortfolioRub = 100_000.0,
                correctedMarginRub = 0.0,
                leverageForNotional = 7.0,
            )
        )
        assertEquals(626, sizing.lotsFromLeverage)
        assertEquals(626, sizing.quantityLots)
        assertTrue(sizing.executionNotionalRub in 695_000.0..705_000.0)
    }

    @Test
    fun computeSpreadQuantityLots_usesFullLeverageOnLargeAccount() {
        val sizing = computeSpreadQuantityLots(
            SpreadLotSizingInput(
                cashRub = 500_000.0,
                priceTatN = 576.0,
                priceTatNp = 542.0,
                liquidPortfolioRub = 500_000.0,
                correctedMarginRub = 0.0,
                leverageForNotional = 7.0,
            )
        )
        assertEquals(3130, sizing.lotsFromLeverage)
        assertEquals(sizing.lotsFromLeverage, sizing.quantityLots)
    }

    @Test
    fun computeSpreadQuantityLots_limitsByMarginHeadroomWhenLoaded() {
        val sizing = computeSpreadQuantityLots(
            SpreadLotSizingInput(
                cashRub = -50_000.0,
                priceTatN = 576.0,
                priceTatNp = 542.0,
                liquidPortfolioRub = 10_000.0,
                correctedMarginRub = 9_000.0,
                leverageForNotional = 7.0,
            )
        )
        assertTrue(sizing.quantityLots <= sizing.lotsFromMarginHeadroom)
        assertTrue(sizing.quantityLots < sizing.lotsFromLeverage)
    }

    @Test
    fun computeSpreadQuantityLots_returnsZeroWhenCashTooLow() {
        val sizing = computeSpreadQuantityLots(
            SpreadLotSizingInput(
                cashRub = 1_000.0,
                priceTatN = 650.0,
                priceTatNp = 480.0,
            )
        )
        assertEquals(0, sizing.quantityLots)
    }

    @Test
    fun spreadVolumeText_formatsPairLots() {
        assertEquals("3+3 лот", spreadVolumeText(3))
    }

    @Test
    fun parseMarginAttributesJson_readsLiquidAndCorrected() {
        val json = JSONObject(
            """
            {
              "liquidPortfolio": {"units": "10000", "nano": 0, "currency": "rub"},
              "correctedMargin": {"units": "1500", "nano": 0, "currency": "rub"},
              "startingMargin": {"units": "1200", "nano": 0, "currency": "rub"}
            }
            """.trimIndent()
        )
        val attrs = parseMarginAttributesJson(json)!!
        assertEquals(10_000.0, attrs.liquidPortfolioRub, 0.01)
        assertEquals(1_500.0, attrs.correctedMarginRub, 0.01)
    }
}
