package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StrategySignalDisplayTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private fun mskMillis(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun legacyStrategySignalDisplayId_encodesTypeAndBar() {
        assertEquals(
            "EL-1715934300000",
            legacyStrategySignalDisplayId(1715934300000L, StrategySignalType.EnterLong)
        )
    }

    @Test
    fun weeklyStrategySignalLabel_formatsNumberTypeAndTimeWithoutSeconds() {
        val monday = LocalDate.of(2026, 6, 1)
        val barTs = mskMillis(monday, 10, 15)
        val events = listOf(
            StrategySignalEvent(
                timestampMillis = barTs,
                signalType = StrategySignalType.EnterLong,
                zScore = -1.1,
                receivedAtMillis = mskMillis(monday, 10, 16),
            )
        )
        val display = strategySignalDisplay(events.first(), events)
        assertEquals("1 long 2026-06-01 10:15", display.signalId)
        assertEquals("1 long", display.tradeDisplayId)
        assertEquals("2026-06-01 10:16", display.receivedTimeMsk)
    }

    @Test
    fun buildWeeklySignalNumberIndex_resetsEachIsoWeek() {
        val week1 = LocalDate.of(2026, 6, 1)
        val week2 = LocalDate.of(2026, 6, 9)
        val e1 = StrategySignalEvent(
            timestampMillis = mskMillis(week1, 9, 0),
            signalType = StrategySignalType.EnterLong,
            zScore = -1.0,
            receivedAtMillis = mskMillis(week1, 9, 1),
        )
        val e2 = StrategySignalEvent(
            timestampMillis = mskMillis(week1, 11, 0),
            signalType = StrategySignalType.ExitLong,
            zScore = -0.5,
            receivedAtMillis = mskMillis(week1, 11, 1),
        )
        val e3 = StrategySignalEvent(
            timestampMillis = mskMillis(week2, 9, 0),
            signalType = StrategySignalType.EnterShort,
            zScore = 1.0,
            receivedAtMillis = mskMillis(week2, 9, 1),
        )
        val index = buildWeeklySignalNumberIndex(listOf(e1, e2, e3))
        assertEquals(1, index[StrategySignalKey(e1.timestampMillis, e1.signalType)])
        assertEquals(2, index[StrategySignalKey(e2.timestampMillis, e2.signalType)])
        assertEquals(1, index[StrategySignalKey(e3.timestampMillis, e3.signalType)])
    }

    @Test
    fun entrySignalDisplayFields_usesJournalReceivedTime() {
        val monday = LocalDate.of(2026, 6, 1)
        val barTs = mskMillis(monday, 10, 15)
        val events = listOf(
            StrategySignalEvent(
                timestampMillis = barTs,
                signalType = StrategySignalType.EnterLong,
                zScore = -1.1,
                receivedAtMillis = barTs + 12_000L
            )
        )
        val (id, bar, recv) = entrySignalDisplayFields(events, barTs, StrategySignalType.EnterLong)
        assertEquals("1 long 2026-06-01 10:15", id)
        assertEquals(formatPortfolioExecutionTableMsk(barTs), bar)
        assertEquals(formatMessageReceivedAtMsk(barTs + 12_000L), recv)
    }
}
