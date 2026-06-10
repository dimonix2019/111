package com.example.moexmvp

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
    fun buildPortfolioTradeGroupRiskAssessment_openShortHoldBreakdownIsZero() {
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
        assertEquals(0, assessment.score)
        assertEquals(0, assessment.breakdown.totalScore)
        assertEquals(listOf(0, 0, 0, 0, 0, 0), portfolioTradeRiskBreakdownPointValues(assessment))
        assertEquals("0", formatPortfolioTradeRiskTotalScore(assessment))
        assertTrue(entryMs < nowMs)
    }

    @Test
    fun buildPortfolioTradeGroupRiskAssessment_breakdownMatchesTotalScore() {
        val group = closedGroup(
            entry = "2026-05-19 10:00",
            exit = "2026-05-22 10:00",
            entryZ = -0.85,
            overnight = 120.0,
        )
        val assessment = buildPortfolioTradeGroupRiskAssessment(group, entryThreshold = 0.8, zoneId = zone)

        assertEquals(assessment.score, assessment.breakdown.totalScore)
        assertEquals(3, assessment.breakdown.holdPoints)
        assertEquals(2, assessment.breakdown.overnightPoints)
        assertEquals(1, assessment.breakdown.weakEntryZPoints)
        assertTrue(assessment.score >= 5)
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

    @Test
    fun shouldShowOpenTradeRiskIcon_onlyForFlaggedOpenGroups() {
        val open = closedGroup("2026-05-17 10:00", "—", -0.85, 120.0).copy(isOpen = true)
        val flagged = buildPortfolioTradeGroupRiskAssessment(open, entryThreshold = 0.8, zoneId = zone)
        val safe = StrategyTestTradeRiskAssessment(
            flags = emptyList(),
            level = StrategyTestTradeRiskLevel.None,
            score = 0,
            entryZ = -1.5,
            breakdown = TradeRiskScoreBreakdown(),
        )
        assertTrue(shouldShowOpenTradeRiskIcon(open, flagged))
        assertFalse(shouldShowOpenTradeRiskIcon(open, safe))
        assertFalse(shouldShowOpenTradeRiskIcon(open.copy(isOpen = false), flagged))
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
