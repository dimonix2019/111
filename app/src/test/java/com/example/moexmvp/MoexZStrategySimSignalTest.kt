package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Симуляция «Тест страт.» следует тем же правилам, что live-монитор:
 * [determineZStrategySignal], одно действие на бар.
 */
class MoexStrategyTestJournalParityTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)

    private fun mskMillis(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun bar(date: LocalDate, hour: Int, minute: Int, z: Double, spread: Double = 8.33): DataPoint {
        val ts = mskMillis(date, hour, minute)
        return DataPoint(
            timestampMillis = ts,
            tradeDate = formatPortfolioExecutionTableMsk(ts),
            tatnClose = 650.0,
            tatnpClose = 600.0,
            spreadPercent = spread,
            diff = 50.0,
            zScore = z,
        )
    }

    private fun simTrades(points: List<DataPoint>): List<PortfolioClosedTrade> =
        buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "test",
            compoundReturns = false,
            exitMode = ZStrategyExitMode.FixedThreshold,
        )?.closedTrades.orEmpty()

    private fun replayEntryExitLabels(points: List<DataPoint>): List<String> {
        val (edges, _) = collectZStrategy15mSignalEdgesFull(points, thresholds = thresholds)
        return edges.map { edge ->
            val label = edge.bar.tradeDate
            when (edge.signal) {
                ZStrategySignal.EnterLong, ZStrategySignal.EnterShort -> "E:$label"
                ZStrategySignal.ExitLong, ZStrategySignal.ExitShort -> "X:$label"
                ZStrategySignal.None -> error("unexpected")
            }
        }
    }

    /** 4 SHORT round-trip на 10–11.06: после 17:45 flat до 12:00 (Z=0.68 на 18:00 не пересекает 0.7). */
    @Test
    fun buildZStrategyPortfolioMetrics_fourShortTradesWhenFlatBetweenSessions() {
        val d10 = LocalDate.of(2026, 6, 10)
        val d11 = LocalDate.of(2026, 6, 11)
        val points = listOf(
            bar(d10, 6, 30, 0.40),
            bar(d10, 6, 45, 1.14),
            bar(d10, 10, 0, 0.28),
            bar(d10, 10, 15, 0.35),
            bar(d10, 11, 45, 0.84),
            bar(d10, 17, 45, 0.50),
            bar(d10, 18, 0, 0.68),
            bar(d11, 11, 45, 0.55),
            bar(d11, 12, 0, 0.87),
            bar(d11, 13, 15, 0.44),
            bar(d11, 13, 30, 0.60),
            bar(d11, 14, 0, 0.79),
            bar(d11, 23, 15, 0.49),
        )
        val trades = simTrades(points)
        assertEquals(4, trades.size)
        assertEquals(
            listOf(
                "2026-06-10 06:45",
                "2026-06-10 11:45",
                "2026-06-11 12:00",
                "2026-06-11 14:00",
            ),
            trades.map { it.entryDate },
        )
        assertEquals(
            listOf(
                "2026-06-10 10:00",
                "2026-06-10 17:45",
                "2026-06-11 13:15",
                "2026-06-11 23:15",
            ),
            trades.map { it.exitDate },
        )
        assertTrue(trades.all { it.direction == ZStrategyPosition.Short })
        assertFalse(replayEntryExitLabels(points).any { it == "E:2026-06-10 18:00" })
    }

    @Test
    fun buildZStrategyPortfolioMetrics_tradeCountMatchesFullSignalReplay() {
        val d10 = LocalDate.of(2026, 6, 10)
        val d11 = LocalDate.of(2026, 6, 11)
        val points = listOf(
            bar(d10, 6, 30, 0.40),
            bar(d10, 6, 45, 1.14),
            bar(d10, 10, 0, 0.28),
            bar(d10, 10, 15, 0.35),
            bar(d10, 11, 45, 0.84),
            bar(d10, 17, 45, 0.50),
            bar(d10, 18, 0, 0.68),
            bar(d11, 11, 45, 0.55),
            bar(d11, 12, 0, 0.87),
            bar(d11, 13, 15, 0.44),
            bar(d11, 13, 30, 0.60),
            bar(d11, 14, 0, 0.79),
            bar(d11, 23, 15, 0.49),
        )
        val (edges, _) = collectZStrategy15mSignalEdgesFull(points, thresholds = thresholds)
        val replayRoundTrips = edges.count {
            it.signal == ZStrategySignal.EnterLong || it.signal == ZStrategySignal.EnterShort
        }
        assertEquals(replayRoundTrips, simTrades(points).size)
    }

    @Test
    fun buildZStrategyPortfolioMetrics_doesNotEnterSameBarAfterStopLossExit() {
        val d = LocalDate.of(2026, 6, 10)
        val points = listOf(
            bar(d, 9, 45, 0.40, spread = 8.0),
            bar(d, 10, 0, 1.10, spread = 8.0),
            bar(d, 10, 15, 1.20, spread = 8.10),
            bar(d, 10, 30, 0.75, spread = 8.10),
            bar(d, 10, 45, 0.80, spread = 8.10),
        )
        val metrics = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "test",
            compoundReturns = false,
            exitMode = ZStrategyExitMode.FixedThreshold,
            simOptions = ZStrategySimOptions(maxLossSpreadPts = 0.05),
        ) ?: error("expected metrics")
        assertEquals(1, metrics.closedTrades.size)
        assertEquals("2026-06-10 10:00", metrics.closedTrades.single().entryDate)
        assertEquals("2026-06-10 10:15", metrics.closedTrades.single().exitDate)
        assertEquals(ZStrategyPosition.Flat, metrics.openPosition?.direction ?: ZStrategyPosition.Flat)
    }
}
