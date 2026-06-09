package com.example.moexmvp

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexStrategyTestTradeRiskTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun buildStrategyTestTradeRiskAssessment_longHoldAndOvernightAreHighRisk() {
        val trade = trade(
            entry = "2026-05-19 10:00",
            exit = "2026-05-22 10:00",
            overnight = 120.0,
            pnl = -500.0,
        )
        val points = listOf(point("2026-05-19 10:00", z = -0.85))
        val assessment = buildStrategyTestTradeRiskAssessment(trade, points, entryThreshold = 0.8, zoneId = zone)

        assertTrue(StrategyTestTradeRiskFlag.LongHold in assessment.flags)
        assertTrue(StrategyTestTradeRiskFlag.VeryHighOvernight in assessment.flags)
        assertTrue(assessment.level >= StrategyTestTradeRiskLevel.High)
    }

    @Test
    fun buildStrategyTestTradeRiskAssessment_shortWinWithWeakZIsNotFlagged() {
        val trade = trade(
            entry = "2026-05-19 10:00",
            exit = "2026-05-19 12:00",
            overnight = 0.0,
            pnl = 100.0,
        )
        val points = listOf(point("2026-05-19 10:00", z = -0.85))
        val assessment = buildStrategyTestTradeRiskAssessment(trade, points, zoneId = zone)

        assertFalse(strategyTestTradeRiskIsFlagged(assessment))
        assertFalse(StrategyTestTradeRiskFlag.WeakEntryZ in assessment.flags)
    }

    @Test
    fun buildStrategyTestTradeRiskAssessmentsForItems_matchesSortedTradeListOrder() {
        val older = trade("2026-05-01 10:00", "2026-05-01 12:00", 0.0, 50.0)
        val newer = trade("2026-05-17 10:00", "2026-05-22 10:00", 120.0, -200.0)
        val items = buildStrategyTestTradeListFromSimulation(listOf(older, newer))
        val points = listOf(
            point("2026-05-01 10:00", z = -1.2),
            point("2026-05-17 10:00", z = -0.85),
        )
        val assessments = buildStrategyTestTradeRiskAssessmentsForItems(items, points, zoneId = zone)

        assertEquals(2, assessments.size)
        assertTrue(strategyTestTradeRiskIsFlagged(assessments[0]))
        assertFalse(strategyTestTradeRiskIsFlagged(assessments[1]))
        assertEquals(newer.exitDate, items[0].trade.exitDate)
    }

    @Test
    fun buildStrategyTestTradeRiskSummary_countsFlaggedLosses() {
        val trades = listOf(
            trade("2026-05-19 10:00", "2026-05-19 12:00", 0.0, 100.0),
            trade("2026-05-19 10:00", "2026-05-22 10:00", 80.0, -200.0),
            trade("2026-05-20 10:00", "2026-05-20 12:00", 0.0, -50.0),
        )
        val items = buildStrategyTestTradeListFromSimulation(trades)
        val assessments = buildStrategyTestTradeRiskAssessmentsForItems(items, emptyList(), zoneId = zone)
        val summary = buildStrategyTestTradeRiskSummary(
            trades = items.map { it.trade },
            assessments = assessments,
        )!!

        assertEquals(3, summary.assessedCount)
        assertEquals(1, summary.flaggedCount)
        assertEquals(1, summary.flaggedLossCount)
        assertTrue(summary.flaggedLossRate > summary.baselineLossRate)
    }

    private fun trade(
        entry: String,
        exit: String,
        overnight: Double,
        pnl: Double,
    ): PortfolioClosedTrade = PortfolioClosedTrade(
        direction = ZStrategyPosition.Long,
        entryDate = entry,
        exitDate = exit,
        entrySpreadPercent = 5.0,
        exitSpreadPercent = 4.9,
        pnlSpreadPoints = -0.1,
        grossPnlRubApprox = pnl + 560.0,
        commissionRubApprox = 560.0,
        overnightRubApprox = overnight,
        pnlRubApprox = pnl,
    )

    private fun point(label: String, z: Double): DataPoint {
        val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
        return DataPoint(
            timestampMillis = ts.atZone(zone).toInstant().toEpochMilli(),
            tradeDate = label,
            tatnClose = 100.0,
            tatnpClose = 95.0,
            spreadPercent = 5.0,
            diff = 0.0,
            zScore = z,
        )
    }
}
