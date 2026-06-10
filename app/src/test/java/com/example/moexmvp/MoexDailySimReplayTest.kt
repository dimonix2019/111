package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class MoexDailySimReplayTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun buildTodaySim_matchesFullSimClosedTradesOnDay() {
        val day = LocalDate.of(2026, 6, 9)
        val points = samplePointsCrossingOnDay(day)
        val thresholds = DynamicThresholds(0.7, 0.5, null)

        val full = checkNotNull(
            buildZStrategyPortfolioMetrics(
                points = points,
                thresholds = thresholds,
                notionalRub = 100_000.0,
                leverage = 1.0,
                commissionPercentPerSide = 0.04,
                periodDescription = "full",
            )
        )
        val today = checkNotNull(
            buildTodaySimPortfolioMetricsFromDayOpen(
                points = points,
                day = day,
                thresholds = thresholds,
                notionalRub = 100_000.0,
                leverage = 1.0,
                commissionPercentPerSide = 0.04,
            )
        )

        val fullClosedOnDay = full.closedTrades.filter { tradeClosedOnDayTest(it, day) }
        val todayClosedOnDay = today.closedTrades.filter { tradeClosedOnDayTest(it, day) }
        assertEquals(
            fullClosedOnDay.map { it.direction to it.exitDate },
            todayClosedOnDay.map { it.direction to it.exitDate },
        )
    }

    @Test
    fun buildTodaySim_differsFromFlatWhenCarryInPosition() {
        val day = LocalDate.of(2026, 6, 9)
        val points = samplePointsWithOvernightLong(day)
        val thresholds = DynamicThresholds(0.7, 0.5, null)
        val dayStart = firstBarIndexOnDay(points, day) ?: error("no day start")

        val fromFlat = checkNotNull(
            buildZStrategyPortfolioMetrics(
                points = points,
                thresholds = thresholds,
                notionalRub = 100_000.0,
                leverage = 1.0,
                commissionPercentPerSide = 0.04,
                periodDescription = "flat",
                simLoopStartIndex = dayStart,
                todaySliceOnly = true,
            )
        )
        val fromDayOpen = checkNotNull(
            buildTodaySimPortfolioMetricsFromDayOpen(
                points = points,
                day = day,
                thresholds = thresholds,
                notionalRub = 100_000.0,
                leverage = 1.0,
                commissionPercentPerSide = 0.04,
            )
        )

        assertEquals(
            ZStrategyPosition.Long,
            inferSimPositionAtDayOpen(points, day, thresholds, 100_000.0, 1.0, 0.04),
        )
        val longExitsOnDay = { metrics: PortfolioMetrics ->
            metrics.closedTrades.count {
                it.direction == ZStrategyPosition.Long && tradeClosedOnDayTest(it, day)
            }
        }
        assertEquals(1, longExitsOnDay(fromDayOpen))
        assertEquals(0, longExitsOnDay(fromFlat))
    }

    @Test
    fun inferJournalPositionBeforeDay_replaysEvents() {
        val day = LocalDate.of(2026, 6, 9)
        val events = listOf(
            event(day.minusDays(1), LocalTime.of(10, 0), StrategySignalType.EnterLong),
            event(day.minusDays(1), LocalTime.of(15, 0), StrategySignalType.ExitLong),
            event(day, LocalTime.of(10, 0), StrategySignalType.EnterShort),
        )
        assertEquals(ZStrategyPosition.Flat, inferJournalPositionBeforeDay(events, day))
    }

    @Test
    fun collectZStrategySignalEdgesOnCalendarDay_respectsInitialPosition() {
        val day = LocalDate.of(2026, 6, 9)
        val points = samplePointsCrossingOnDay(day)
        val thresholds = DynamicThresholds(0.7, 0.5, null)
        val posAtOpen = inferSimPositionAtDayOpen(points, day, thresholds, 100_000.0, 1.0, 0.04)
        val edges = collectZStrategySignalEdgesOnCalendarDay(points, day, posAtOpen, thresholds)
        assertTrue(edges.isNotEmpty())
    }

    private fun tradeClosedOnDayTest(t: PortfolioClosedTrade, day: LocalDate): Boolean =
        chartLabelToLocalDate(t.exitDate) == day

    private fun event(day: LocalDate, time: LocalTime, type: StrategySignalType): StrategySignalEvent {
        val millis = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
        return StrategySignalEvent(
            timestampMillis = millis,
            signalType = type,
            zScore = 0.8,
        )
    }

    /** Long entered previous day, still open at day start. */
    private fun samplePointsWithOvernightLong(day: LocalDate): List<DataPoint> {
        val prev = day.minusDays(1)
        return listOf(
            dp(prev, 9, 0, z = 0.0, spread = 1.0),
            dp(prev, 10, 15, z = -0.75, spread = 1.1),
            dp(prev, 10, 30, z = -0.72, spread = 1.12),
            dp(day, 10, 0, z = -0.72, spread = 1.12),
            dp(day, 10, 15, z = -0.40, spread = 1.05),
            dp(day, 11, 0, z = 0.75, spread = 0.95),
        )
    }

    private fun samplePointsCrossingOnDay(day: LocalDate): List<DataPoint> {
        val prev = day.minusDays(1)
        return listOf(
            dp(prev, 15, 0, z = 0.0, spread = 1.0),
            dp(prev, 15, 45, z = 0.3, spread = 1.0),
            dp(day, 15, 30, z = 0.35, spread = 1.0),
            dp(day, 15, 45, z = 0.75, spread = 0.98),
            dp(day, 16, 0, z = 0.45, spread = 0.99),
            dp(day, 16, 15, z = 0.2, spread = 1.0),
        )
    }

    private fun dp(day: LocalDate, hour: Int, minute: Int, z: Double, spread: Double): DataPoint {
        val zoned = day.atTime(hour, minute).atZone(zone)
        return DataPoint(
            timestampMillis = zoned.toInstant().toEpochMilli(),
            tradeDate = String.format("%04d-%02d-%02d %02d:%02d", day.year, day.monthValue, day.dayOfMonth, hour, minute),
            tatnClose = 100.0,
            tatnpClose = 100.0,
            spreadPercent = spread,
            diff = 0.0,
            zScore = z,
        )
    }
}
