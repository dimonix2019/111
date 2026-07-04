package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MoexStrategyTestSpreadDeltaTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun buildStrategyTestSpreadDeltaChartData_fromDayOpen_whenNoOpenPosition() {
        val points = listOf(
            point("2026-06-18 07:30", spread = 6.0),
            point("2026-06-18 10:00", spread = 6.2),
            point("2026-06-18 12:00", spread = 5.9),
        )
        val data = buildStrategyTestSpreadDeltaChartData(
            points = points,
            openPosition = null,
            leverage = 7.0,
            accountSizeRub = 10_000.0,
        )!!
        assertTrue(data.title.contains("открытия дня"))
        assertEquals(3, data.deltasPp.size)
        assertEquals(0.0, data.deltasPp.first(), 0.001)
        assertEquals(0.2, data.deltasPp[1], 0.001)
    }

    @Test
    fun buildStrategyTestSpreadDeltaChartData_fromEntry_whenOpenPosition() {
        val entryMs = mskMillis(2026, 6, 18, 10, 0)
        val points = listOf(
            point("2026-06-18 07:30", spread = 6.0, ts = mskMillis(2026, 6, 18, 7, 30)),
            point("2026-06-18 10:00", spread = 6.2, ts = entryMs),
            point("2026-06-18 12:00", spread = 6.5, ts = mskMillis(2026, 6, 18, 12, 0)),
        )
        val open = PortfolioOpenPosition(
            direction = ZStrategyPosition.Long,
            entryDate = "2026-06-18 10:00",
            entrySpreadPercent = 6.2,
            lastSpreadPercent = 6.5,
            unrealizedPnlSpread = 0.3,
            unrealizedRubApprox = 210.0,
        )
        val data = buildStrategyTestSpreadDeltaChartData(
            points = points,
            openPosition = open,
            leverage = 7.0,
            accountSizeRub = 10_000.0,
        )!!
        assertTrue(data.title.contains("от входа"))
        assertEquals(0.0, data.deltasPp[0], 0.001)
        assertEquals(0.0, data.deltasPp[1], 0.001)
        assertEquals(0.3, data.deltasPp[2], 0.001)
    }

    @Test
    fun buildSpreadDeltaMarkersFromStrategyTestTrades_usesDeltaAtTradeBars() {
        val points = listOf(
            point("2026-06-18 07:30", spread = 6.0),
            point("2026-06-18 10:00", spread = 6.2),
            point("2026-06-18 12:00", spread = 5.9),
        )
        val deltas = listOf(0.0, 0.2, -0.1)
        val trade = PortfolioClosedTrade(
            direction = ZStrategyPosition.Long,
            entryDate = "2026-06-18 10:00",
            exitDate = "2026-06-18 12:00",
            entrySpreadPercent = 6.2,
            exitSpreadPercent = 5.9,
            pnlSpreadPoints = -0.3,
            grossPnlRubApprox = -210.0,
            pnlRubApprox = -220.0,
        )
        val markers = buildSpreadDeltaMarkersFromStrategyTestTrades(
            points = points,
            deltasPp = deltas,
            tradeItems = listOf(StrategyTestTradeItem(trade, sourceLabel = "T-001")),
        )
        assertEquals(2, markers.size)
        assertEquals(0.2, markers.first { it.label.startsWith("Вх") }.value, 0.001)
        assertEquals(-0.1, markers.first { it.label.startsWith("Вых") }.value, 0.001)
    }

    @Test
    fun strategyTestLiveChartHeightsDp_includesSpreadDeltaBand() {
        val (z, delta, equity) = strategyTestLiveChartHeightsDp(873)
        assertTrue(z in 140..190)
        assertTrue(delta in 110..160)
        assertTrue(equity in 170..260)
        assertTrue(z + delta + equity <= 540)
    }

    @Test
    fun buildStrategyTestSpreadDeltaAxisRange_focusesRecentTail_notFullHistory() {
        val flatHistory = List(200) { 0.0 }
        val recentTail = listOf(0.01, 0.02, 0.03, 0.04)
        val deltas = flatHistory + recentTail
        val (min, max) = buildStrategyTestSpreadDeltaAxisRange(deltas, emptyList())
        assertTrue(max - min < 0.12)
        assertTrue(min <= 0.0)
        assertTrue(max >= 0.04)
    }

    private fun point(
        label: String,
        spread: Double,
        ts: Long = mskMillis(2026, 6, 18, 7, 30),
    ) = DataPoint(
        timestampMillis = ts,
        tradeDate = label,
        tatnClose = 100.0,
        tatnpClose = 95.0,
        spreadPercent = spread,
        diff = 5.0,
        zScore = 0.5,
    )

    private fun mskMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.time.LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
