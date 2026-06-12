package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Паритет «Тест страт.» ↔ «Портфель»: при одинаковых порогах сделки из журнала, не offline Z-sim.
 */
class MoexStrategyTestJournalParityTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val d10 = LocalDate.of(2026, 6, 10)
    private val d11 = LocalDate.of(2026, 6, 11)

    private fun mskMillis(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun bar(date: LocalDate, hour: Int, minute: Int, z: Double): DataPoint {
        val ts = mskMillis(date, hour, minute)
        return DataPoint(
            timestampMillis = ts,
            tradeDate = formatPortfolioExecutionTableMsk(ts),
            tatnClose = 650.0,
            tatnpClose = 600.0,
            spreadPercent = 8.33,
            diff = 50.0,
            zScore = z,
        )
    }

    /** 4 SHORT round-trip из журнала 10–11.06.2026 (как в портфеле). */
    private fun journalEventsJune1011(): List<StrategySignalEvent> = listOf(
        ev(d10, 6, 45, StrategySignalType.EnterShort, 1.14),
        ev(d10, 10, 0, StrategySignalType.ExitShort, 0.28),
        ev(d10, 11, 45, StrategySignalType.EnterShort, 0.84),
        ev(d10, 17, 45, StrategySignalType.ExitShort, 0.50),
        ev(d11, 12, 0, StrategySignalType.EnterShort, 0.87),
        ev(d11, 13, 15, StrategySignalType.ExitShort, 0.44),
        ev(d11, 14, 0, StrategySignalType.EnterShort, 0.79),
        ev(d11, 23, 15, StrategySignalType.ExitShort, 0.49),
    )

    private fun ev(
        date: LocalDate,
        hour: Int,
        minute: Int,
        type: StrategySignalType,
        z: Double,
    ): StrategySignalEvent {
        val ts = mskMillis(date, hour, minute)
        return StrategySignalEvent(
            timestampMillis = ts,
            signalType = type,
            zScore = z,
            receivedAtMillis = ts + 60_000L,
        )
    }

    private fun pointsJune1011(): List<DataPoint> {
        val bars = mutableListOf<DataPoint>()
        fun addDay(date: LocalDate, slots: List<Pair<Int, Double>>) {
            for ((minuteOfDay, z) in slots) {
                val hour = minuteOfDay / 60
                val minute = minuteOfDay % 60
                bars += bar(date, hour, minute, z)
            }
        }
        addDay(
            d10,
            listOf(
                6 * 60 + 45 to 1.14,
                10 * 60 to 0.28,
                11 * 60 + 45 to 0.84,
                17 * 60 + 45 to 0.50,
                18 * 60 to 0.72,
            ),
        )
        addDay(
            d11,
            listOf(
                12 * 60 to 0.87,
                13 * 60 + 15 to 0.44,
                14 * 60 to 0.79,
                23 * 60 + 15 to 0.49,
            ),
        )
        return bars
    }

    @Test
    fun buildStrategyTestMetricsFromJournalEvents_fourShortTradesOnJune1011() {
        val points = pointsJune1011()
        val events = journalEventsJune1011()
        val metrics = buildStrategyTestMetricsFromJournalEvents(
            points = points,
            events = events,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
        ) ?: error("expected metrics")

        assertEquals(4, metrics.closedTrades.size)
        val entries = metrics.closedTrades.map { it.entryDate }.sorted()
        assertEquals(
            listOf(
                "2026-06-10 06:45",
                "2026-06-10 11:45",
                "2026-06-11 12:00",
                "2026-06-11 14:00",
            ),
            entries,
        )
        val exits = metrics.closedTrades.map { it.exitDate }.sorted()
        assertEquals(
            listOf(
                "2026-06-10 10:00",
                "2026-06-10 17:45",
                "2026-06-11 13:15",
                "2026-06-11 23:15",
            ),
            exits,
        )
        assertTrue(metrics.closedTrades.all { it.direction == ZStrategyPosition.Short })
    }

    @Test
    fun journalReplay_matchesPortfolioExecutedBuilder() {
        val points = pointsJune1011()
        val events = journalEventsJune1011()
        val journal = buildStrategyTestMetricsFromJournalEvents(
            points = points,
            events = events,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
        )
        val portfolio = buildExecutedPortfolioMetrics(
            points = points,
            events = journalEventsForExecutionPortfolioTab(events, emptyList(), portfolioLedgerIncludeAuto = true),
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "portfolio",
        )
        assertEquals(portfolio?.closedTrades?.size, journal?.closedTrades?.size)
        assertEquals(portfolio?.closedTrades?.map { it.entryDate }, journal?.closedTrades?.map { it.entryDate })
    }

    @Test
    fun offlineZSim_divergesFromJournal_onSpuriousReentryBar() {
        val points = pointsJune1011()
        val events = journalEventsJune1011()
        val journal = buildStrategyTestMetricsFromJournalEvents(
            points = points,
            events = events,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
        )
        val zSim = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null),
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "zsim",
            compoundReturns = false,
            exitMode = ZStrategyExitMode.FixedThreshold,
        )
        assertNotEquals(journal?.closedTrades?.size, zSim?.closedTrades?.size)
        val journalEntriesOn11 = journal?.closedTrades?.count {
            it.entryDate.startsWith("2026-06-11")
        }
        val zSimEntriesOn11 = zSim?.closedTrades?.count {
            it.entryDate.startsWith("2026-06-11")
        }
        assertEquals(2, journalEntriesOn11)
        assertNotEquals(2, zSimEntriesOn11)
    }
}
