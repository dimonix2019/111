package com.example.moexmvp

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexStrategyTestLoadTest {

    @Test
    fun sufficientForStrategyTestSimulation_requiresSpanAndBars() {
        val zone = ZoneId.of("Europe/Moscow")
        val start = java.time.LocalDate.of(2025, 6, 1)
        val points = buildList {
            var day = 0
            while (day < 200) {
                val date = start.plusDays(day.toLong())
                val ts = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
                add(
                    DataPoint(
                        timestampMillis = ts,
                        tradeDate = date.toString(),
                        tatnClose = 100.0,
                        tatnpClose = 90.0,
                        spreadPercent = 10.0,
                        diff = 10.0,
                        zScore = 0.0,
                    )
                )
                day++
            }
        }
        assertTrue(points.sufficientForStrategyTestSimulation())
        assertFalse(points.take(40).sufficientForStrategyTestSimulation())
    }

    @Test
    fun strategyTestM15PointsForChart_prefersOneMonthTail() {
        val zone = ZoneId.of("Europe/Moscow")
        val start = java.time.LocalDate.of(2025, 1, 1)
        val full = buildList {
            var day = 0
            while (day < 90) {
                val date = start.plusDays(day.toLong())
                val ts = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
                add(
                    DataPoint(
                        timestampMillis = ts,
                        tradeDate = date.toString(),
                        tatnClose = 100.0,
                        tatnpClose = 90.0,
                        spreadPercent = 10.0,
                        diff = 10.0,
                        zScore = 0.0,
                    )
                )
                day++
            }
        }
        val chart = strategyTestM15PointsForChart(full)
        val month = filterM15PointsForMarketsPeriod(full, Period.OneMonth)
        assertTrue(chart.size <= full.size)
        assertEquals(month.size, chart.size)
    }

    @Test
    fun filterStrategyTestTradesForChart_keepsTradesInWindow() {
        val zone = ZoneId.of("Europe/Moscow")
        val points = listOf(
            DataPoint(
                timestampMillis = java.time.LocalDate.of(2026, 5, 1).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
                tradeDate = "2026-05-01 10:00",
                tatnClose = 100.0,
                tatnpClose = 90.0,
                spreadPercent = 10.0,
                diff = 10.0,
                zScore = 0.0,
            ),
            DataPoint(
                timestampMillis = java.time.LocalDate.of(2026, 5, 20).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
                tradeDate = "2026-05-20 10:00",
                tatnClose = 100.0,
                tatnpClose = 90.0,
                spreadPercent = 10.0,
                diff = 10.0,
                zScore = 0.0,
            ),
        )
        val inside = StrategyTestTradeItem(
            trade = PortfolioClosedTrade(
                direction = ZStrategyPosition.Long,
                entryDate = "2026-05-10 10:00",
                exitDate = "2026-05-15 10:00",
                entrySpreadPercent = 10.0,
                exitSpreadPercent = 10.4,
                pnlSpreadPoints = 0.4,
                grossPnlRubApprox = 100.0,
                pnlRubApprox = 90.0,
            )
        )
        val outside = inside.copy(
            trade = inside.trade.copy(
                entryDate = "2025-01-01 10:00",
                exitDate = "2025-01-02 10:00",
            )
        )
        val filtered = filterStrategyTestTradesForChart(points, listOf(inside, outside))
        assertEquals(1, filtered.size)
        assertEquals(inside, filtered.single())
    }

    @Test
    fun applyZScoresDefaultInPlace_mutatesWithoutSecondList() {
        val zone = ZoneId.of("Europe/Moscow")
        val points = ArrayList(
            (0 until 60).map { i ->
                val date = java.time.LocalDate.of(2026, 1, 1).plusDays(i.toLong())
                DataPoint(
                    timestampMillis = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
                    tradeDate = date.toString(),
                    tatnClose = 100.0,
                    tatnpClose = 90.0,
                    spreadPercent = 10.0 + i * 0.01,
                    diff = 10.0,
                    zScore = 0.0,
                )
            }
        )
        applyZScoresDefaultInPlace(points)
        val reference = applyZScoresDefault(points.map { it.copy(zScore = 0.0) })
        assertEquals(reference.size, points.size)
        assertEquals(reference.last().zScore, points.last().zScore, 1e-9)
    }
}
