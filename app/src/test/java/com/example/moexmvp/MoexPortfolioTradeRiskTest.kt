package com.example.moexmvp

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexPortfolioTradeRiskTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun buildPortfolioTradeGroupRiskAssessment_closedLongHoldIsFlagged() {
        val group = closedGroup(
            entry = "2026-05-19 10:00",
            exit = "2026-05-22 10:00",
            entryZ = -0.85,
            overnight = 120.0,
        )
        val assessment = buildPortfolioTradeGroupRiskAssessment(group, entryThreshold = 0.8, zoneId = zone)

        assertTrue(strategyTestTradeRiskIsFlagged(assessment))
        assertTrue(StrategyTestTradeRiskFlag.LongHold in assessment.flags)
        assertTrue(StrategyTestTradeRiskFlag.VeryHighOvernight in assessment.flags)
    }

    @Test
    fun buildPortfolioTradeGroupRiskAssessment_openShortHoldIsNotFlagged() {
        val entryMs = LocalDateTime.parse("2026-05-19 10:00", portfolio15mLabelFormatter)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val nowMs = LocalDateTime.parse("2026-05-19 12:00", portfolio15mLabelFormatter)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val group = openGroup(
            entry = "2026-05-19 10:00",
            entryZ = -0.85,
            overnight = 0.0,
        )
        val assessment = buildPortfolioTradeGroupRiskAssessment(
            group = group,
            entryThreshold = 0.8,
            zoneId = zone,
            nowMillis = nowMs,
        )

        assertFalse(strategyTestTradeRiskIsFlagged(assessment))
        assertFalse(StrategyTestTradeRiskFlag.WeakEntryZ in assessment.flags)
        assertTrue(entryMs < nowMs)
    }

    @Test
    fun buildPortfolioTradeGroupRiskAssessments_matchesGroupOrder() {
        val groups = listOf(
            closedGroup("2026-05-17 10:00", "2026-05-22 10:00", -0.85, 120.0),
            closedGroup("2026-05-01 10:00", "2026-05-01 12:00", -1.2, 0.0),
        )
        val assessments = buildPortfolioTradeGroupRiskAssessments(groups, entryThreshold = 0.8, zoneId = zone)

        assertTrue(strategyTestTradeRiskIsFlagged(assessments[0]))
        assertFalse(strategyTestTradeRiskIsFlagged(assessments[1]))
    }

    private fun closedGroup(
        entry: String,
        exit: String,
        entryZ: Double,
        overnight: Double,
    ): PortfolioTradeGroupRow = PortfolioTradeGroupRow(
        tradeId = "t1",
        directionLabel = "Long",
        entryTimeMsk = entry,
        exitTimeMsk = exit,
        volumeText = "1 лот",
        confirmLabel = "Да",
        entryZ = entryZ,
        exitZ = -0.2,
        notificationIdsText = "—",
        legLongPnlSplitRubApprox = -100.0,
        legShortPnlSplitRubApprox = 50.0,
        netPnlRubApprox = -200.0,
        overnightRubApprox = overnight,
        orders = emptyList(),
        isOpen = false,
    )

    private fun openGroup(
        entry: String,
        entryZ: Double,
        overnight: Double,
    ): PortfolioTradeGroupRow = closedGroup(entry, "—", entryZ, overnight).copy(isOpen = true)
}
