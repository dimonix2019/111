package com.example.moexmvp

import org.junit.Assert.assertEquals
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
}
