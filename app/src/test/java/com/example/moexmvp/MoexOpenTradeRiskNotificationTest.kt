package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexOpenTradeRiskNotificationTest {

    private val zone = java.time.ZoneId.of("Europe/Moscow")

    @Test
    fun isOpenTradeRedRiskZone_highAndCriticalOnly() {
        assertFalse(
            isOpenTradeRedRiskZone(
                assessment(level = StrategyTestTradeRiskLevel.Elevated, score = 3),
            ),
        )
        assertTrue(
            isOpenTradeRedRiskZone(
                assessment(level = StrategyTestTradeRiskLevel.High, score = 4),
            ),
        )
        assertTrue(
            isOpenTradeRedRiskZone(
                assessment(level = StrategyTestTradeRiskLevel.Critical, score = 6),
            ),
        )
    }

    @Test
    fun planOpenTradeRedRiskNotifications_enterReminderExit() {
        val group = openGroup("t1", "2 short")
        val red = buildPortfolioTradeGroupRiskAssessment(
            group = group.copy(
                entryTimeMsk = "2026-05-17 10:00",
                overnightRubApprox = 120.0,
            ),
            entryThreshold = 0.8,
            zoneId = zone,
            nowMillis = ms("2026-05-22 10:00"),
        )
        assertTrue(isOpenTradeRedRiskZone(red))

        val t0 = ms("2026-05-22 10:00")
        val (enterActions, afterEnter) = planOpenTradeRedRiskNotifications(
            openGroups = listOf(group),
            assessments = listOf(red),
            previousStates = emptyMap(),
            nowMillis = t0,
        )
        assertEquals(1, enterActions.size)
        assertEquals(OpenTradeRedRiskNotifyKind.Entered, enterActions[0].kind)

        val t15 = t0 + OPEN_TRADE_RED_RISK_REMINDER_INTERVAL_MS
        val (remindActions, afterRemind) = planOpenTradeRedRiskNotifications(
            openGroups = listOf(group),
            assessments = listOf(red),
            previousStates = afterEnter,
            nowMillis = t15,
        )
        assertEquals(1, remindActions.size)
        assertEquals(OpenTradeRedRiskNotifyKind.Reminder, remindActions[0].kind)

        val safeGroup = group.copy(
            entryTimeMsk = "2026-05-22 08:00",
            overnightRubApprox = 0.0,
            entryZ = -1.2,
        )
        val safe = buildPortfolioTradeGroupRiskAssessment(
            group = safeGroup,
            entryThreshold = 0.8,
            zoneId = zone,
            nowMillis = t15 + 1,
        )
        assertFalse(isOpenTradeRedRiskZone(safe))
        val (exitActions, afterExit) = planOpenTradeRedRiskNotifications(
            openGroups = listOf(safeGroup),
            assessments = listOf(safe),
            previousStates = afterRemind,
            nowMillis = t15 + 1,
        )
        assertEquals(1, exitActions.size)
        assertEquals(OpenTradeRedRiskNotifyKind.Exited, exitActions[0].kind)
        assertFalse(afterExit.containsKey("t1"))
    }

    @Test
    fun planOpenTradeRedRiskNotifications_noReminderBeforeInterval() {
        val group = openGroup("t1", "2 short")
        val red = buildPortfolioTradeGroupRiskAssessment(
            group = group.copy(
                entryTimeMsk = "2026-05-17 10:00",
                overnightRubApprox = 120.0,
            ),
            entryThreshold = 0.8,
            zoneId = zone,
            nowMillis = ms("2026-05-22 10:00"),
        )
        val t0 = ms("2026-05-22 10:00")
        val (_, afterEnter) = planOpenTradeRedRiskNotifications(
            openGroups = listOf(group),
            assessments = listOf(red),
            previousStates = emptyMap(),
            nowMillis = t0,
        )
        val (actions, _) = planOpenTradeRedRiskNotifications(
            openGroups = listOf(group),
            assessments = listOf(red),
            previousStates = afterEnter,
            nowMillis = t0 + OPEN_TRADE_RED_RISK_REMINDER_INTERVAL_MS - 1,
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun planOpenTradeRedRiskNotifications_closedTradeTriggersExit() {
        val previous = mapOf("t1" to OpenTradeRedRiskNotifyState(inRedZone = true, lastReminderAtMillis = 1L))
        val (actions, next) = planOpenTradeRedRiskNotifications(
            openGroups = emptyList(),
            assessments = emptyList(),
            previousStates = previous,
            nowMillis = 2L,
        )
        assertEquals(1, actions.size)
        assertEquals(OpenTradeRedRiskNotifyKind.Exited, actions[0].kind)
        assertFalse(next.containsKey("t1"))
    }

    @Test
    fun openTradeRedRiskStateJson_roundTrip() {
        val states = mapOf(
            "t1" to OpenTradeRedRiskNotifyState(inRedZone = true, lastReminderAtMillis = 123L),
            "t2" to OpenTradeRedRiskNotifyState(inRedZone = false, lastReminderAtMillis = 0L),
        )
        val decoded = decodeOpenTradeRedRiskNotifyStates(encodeOpenTradeRedRiskNotifyStates(states))
        assertEquals(states, decoded)
    }

    private fun assessment(level: StrategyTestTradeRiskLevel, score: Int): StrategyTestTradeRiskAssessment =
        StrategyTestTradeRiskAssessment(
            flags = emptyList(),
            level = level,
            score = score,
            entryZ = -0.85,
            breakdown = TradeRiskScoreBreakdown(),
        )

    private fun openGroup(tradeId: String, displayId: String): PortfolioTradeGroupRow =
        PortfolioTradeGroupRow(
            tradeId = tradeId,
            tradeDisplayId = displayId,
            directionLabel = "Short",
            entryTimeMsk = "2026-05-17 10:00",
            exitTimeMsk = "—",
            volumeText = "1 лот",
            confirmLabel = "Да",
            entryZ = -0.85,
            exitZ = Double.NaN,
            notificationIdsText = "—",
            legLongPnlSplitRubApprox = 0.0,
            legShortPnlSplitRubApprox = 0.0,
            netPnlRubApprox = 0.0,
            overnightRubApprox = 120.0,
            orders = emptyList(),
            isOpen = true,
        )

    private fun ms(label: String): Long =
        java.time.LocalDateTime.parse(label, portfolio15mLabelFormatter)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
