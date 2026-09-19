package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexOpenTradeProfitNotificationTest {

    @Test
    fun planOpenTradeProfitNotifications_fires2And3Once() {
        val group = openGroup(pnlRub = 250.0)
        val invested = 10_000.0

        val (first, afterFirst) = planOpenTradeProfitNotifications(
            openGroups = listOf(group),
            investedRub = invested,
            previousStates = emptyMap(),
        )
        assertEquals(1, first.size)
        assertEquals(2.0, first[0].thresholdPercent, 0.001)

        val (none, afterStill) = planOpenTradeProfitNotifications(
            openGroups = listOf(group),
            investedRub = invested,
            previousStates = afterFirst,
        )
        assertTrue(none.isEmpty())

        val group3 = openGroup(pnlRub = 320.0)
        val (second, afterSecond) = planOpenTradeProfitNotifications(
            openGroups = listOf(group3),
            investedRub = invested,
            previousStates = afterStill,
        )
        assertEquals(1, second.size)
        assertEquals(3.0, second[0].thresholdPercent, 0.001)
        assertEquals(setOf(2.0, 3.0), afterSecond["t1"]?.notifiedThresholdsPercent)
    }

    @Test
    fun planOpenTradeProfitNotifications_jumpsTo3FiresBoth() {
        val group = openGroup(pnlRub = 350.0)
        val (actions, state) = planOpenTradeProfitNotifications(
            openGroups = listOf(group),
            investedRub = 10_000.0,
            previousStates = emptyMap(),
        )
        assertEquals(2, actions.size)
        assertEquals(2.0, actions[0].thresholdPercent, 0.001)
        assertEquals(3.0, actions[1].thresholdPercent, 0.001)
        assertEquals(setOf(2.0, 3.0), state["t1"]?.notifiedThresholdsPercent)
    }

    @Test
    fun planOpenTradeProfitNotifications_noPushBelow2() {
        val group = openGroup(pnlRub = 150.0)
        val (actions, _) = planOpenTradeProfitNotifications(
            openGroups = listOf(group),
            investedRub = 10_000.0,
            previousStates = emptyMap(),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun planOpenTradeProfitNotifications_closedTradeClearsState() {
        val previous = mapOf("t1" to OpenTradeProfitNotifyState(notifiedThresholdsPercent = setOf(2.0)))
        val (actions, next) = planOpenTradeProfitNotifications(
            openGroups = emptyList(),
            investedRub = 10_000.0,
            previousStates = previous,
        )
        assertTrue(actions.isEmpty())
        assertTrue(next.isEmpty())
    }

    @Test
    fun openTradeProfitStateJson_roundTrip() {
        val states = mapOf(
            "t1" to OpenTradeProfitNotifyState(notifiedThresholdsPercent = setOf(2.0, 3.0)),
            "t2" to OpenTradeProfitNotifyState(notifiedThresholdsPercent = setOf(2.0)),
        )
        val decoded = decodeOpenTradeProfitNotifyStates(encodeOpenTradeProfitNotifyStates(states))
        assertEquals(states, decoded)
    }

    @Test
    fun formatOpenTradeProfitThresholdLabel() {
        assertEquals("+2%", formatOpenTradeProfitThresholdLabel(2.0))
        assertEquals("+3%", formatOpenTradeProfitThresholdLabel(3.0))
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
