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
                brokerStopOrderIds = listOf("sl-tatn", "sl-tatnp"),
                brokerStopSummary = "TATN SELL stop 588.0 ₽ #sl-tatn; TATNP BUY stop 612.0 ₽ #sl-tatnp",
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

    @Test
    fun planBrokerLegStopLossOrders_placesSellForLongBuyForShort() {
        val exec = openExec(StrategySignalType.EnterLong)
        val broker = SpreadLegBrokerPnl(
            longLegYieldRub = 120.0,
            shortLegYieldRub = 80.0,
            longLegPriceRub = 600.0,
            shortLegPriceRub = 500.0,
            longLegQuantity = 10,
            shortLegQuantity = -10,
        )

        val plans = planBrokerLegStopLossOrders(exec, broker, stopPercent = 2.0)

        assertEquals(2, plans.size)
        assertEquals("TATN", plans[0].ticker)
        assertEquals("STOP_ORDER_DIRECTION_SELL", plans[0].closeDirection)
        assertEquals(588.0, plans[0].stopPriceRub, 0.001)
        assertEquals("TATNP", plans[1].ticker)
        assertEquals("STOP_ORDER_DIRECTION_BUY", plans[1].closeDirection)
        assertEquals(510.0, plans[1].stopPriceRub, 0.001)
    }

    @Test
    fun postStopOrderBodySnake_usesStopLossGoodTillCancel() {
        val plan = BrokerLegStopOrderPlan(
            ticker = "TATN",
            closeDirection = "STOP_ORDER_DIRECTION_SELL",
            stopPriceRub = 588.0,
            quantityLots = 10,
            isLongLeg = true,
        )

        val body = postStopOrderBodySnake("acc", "TATN_UID", plan)

        assertEquals("acc", body.getString("account_id"))
        assertEquals("TATN_UID", body.getString("instrument_id"))
        assertEquals("10", body.getString("quantity"))
        assertEquals("STOP_ORDER_DIRECTION_SELL", body.getString("direction"))
        assertEquals("STOP_ORDER_TYPE_STOP_LOSS", body.getString("stop_order_type"))
        assertEquals("STOP_ORDER_EXPIRATION_TYPE_GOOD_TILL_CANCEL", body.getString("expiration_type"))
        assertEquals(588L, body.getJSONObject("stop_price").getLong("units"))
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

    private fun openExec(signal: StrategySignalType): SandboxSpreadExecUi {
        val spread = legSpreadDisplayForEntry(signal)
        return SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = signal,
            zScore = -1.0,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 6.0,
            source = PortfolioExecSource.AUTO,
            directionLabel = if (signal == StrategySignalType.EnterShort) "short" else "long",
            entryTimeMsk = "2026-07-07 10:00",
            longLegTicker = spread.longTicker,
            shortLegTicker = spread.shortTicker,
            longLegSideRu = spread.longSideRu,
            shortLegSideRu = spread.shortSideRu,
            volumeText = "10 лот",
            confirmLabel = "авто",
            correlationTag = "tag",
            notificationIdsText = "—",
            legs = emptyList(),
            quantityLots = 10,
        )
    }
}
