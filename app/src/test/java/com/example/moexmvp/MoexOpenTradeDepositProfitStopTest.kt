package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexOpenTradeDepositProfitStopTest {

    @Test
    fun computeDepositProfitStopFloorRub_isTwoPercentOfDeposit() {
        assertEquals(200_000.0, computeDepositProfitStopFloorRub(10_000_000.0), 0.01)
        assertEquals(2_000.0, computeDepositProfitStopFloorRub(100_000.0), 0.01)
    }

    @Test
    fun depositStopStateJson_roundTrip() {
        val stops = mapOf(
            "t1" to OpenTradeDepositProfitStop(
                tradeId = "t1",
                floorPnlRub = 200_000.0,
                depositRub = 10_000_000.0,
                stopPercent = 2.0,
                armedAtMillis = 1_700_000_000_000L,
                pnlAtArmRub = 220_000.0,
            ),
        )
        val decoded = decodeOpenTradeDepositProfitStops(encodeOpenTradeDepositProfitStops(stops))
        assertEquals(stops, decoded)
    }

    @Test
    fun planOpenTradeProfitNotifications_fires22WithStopArmThreshold() {
        val group = openGroup(pnlRub = 225.0)
        val invested = 10_000.0
        val prev = mapOf("t1" to OpenTradeProfitNotifyState(notifiedThresholdsPercent = setOf(2.0)))
        val (actions, next) = planOpenTradeProfitNotifications(
            openGroups = listOf(group),
            investedRub = invested,
            previousStates = prev,
        )
        assertEquals(1, actions.size)
        assertEquals(OPEN_TRADE_PROFIT_STOP_ARM_PERCENT, actions[0].thresholdPercent, 0.001)
        assertTrue(next["t1"]?.notifiedThresholdsPercent?.contains(2.2) == true)
    }

    @Test
    fun formatOpenTradeProfitThresholdLabel_includes22() {
        assertEquals("+2.2%", formatOpenTradeProfitThresholdLabel(2.2))
    }

    private fun openGroup(pnlRub: Double): PortfolioTradeGroupRow =
        PortfolioTradeGroupRow(
            tradeId = "t1",
            tradeDisplayId = "3 long",
            directionLabel = "Long",
            entryTimeMsk = "2026-07-07 10:00",
            exitTimeMsk = "—",
            volumeText = "1 лот",
            confirmLabel = "Да",
            entryZ = -0.85,
            exitZ = Double.NaN,
            notificationIdsText = "—",
            legLongPnlSplitRubApprox = pnlRub / 2.0,
            legShortPnlSplitRubApprox = pnlRub / 2.0,
            netPnlRubApprox = pnlRub,
            overnightRubApprox = 0.0,
            orders = emptyList(),
            isOpen = true,
        )
}
