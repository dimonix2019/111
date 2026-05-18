package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test

class StrategySignalDisplayTest {

    @Test
    fun strategySignalDisplayId_encodesTypeAndBar() {
        assertEquals(
            "EL-1715934300000",
            strategySignalDisplayId(1715934300000L, StrategySignalType.EnterLong)
        )
    }

    @Test
    fun entrySignalDisplayFields_usesJournalReceivedTime() {
        val barTs = 1715934300000L
        val events = listOf(
            StrategySignalEvent(
                timestampMillis = barTs,
                signalType = StrategySignalType.EnterLong,
                zScore = -1.1,
                receivedAtMillis = barTs + 12_000L
            )
        )
        val (id, bar, recv) = entrySignalDisplayFields(events, barTs, StrategySignalType.EnterLong)
        assertEquals("EL-$barTs", id)
        assertEquals(formatPortfolioExecutionTableMsk(barTs), bar)
        assertEquals(formatMessageReceivedAtMsk(barTs + 12_000L), recv)
    }
}
