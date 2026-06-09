package com.example.moexmvp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategySignalJournalDedupTest {

    @Test
    fun strategySignalJournalHasBarEdge_matchesSameBarAndType() {
        val barTs = 1_748_000_000_000L
        val events = listOf(
            StrategySignalEvent(
                timestampMillis = barTs,
                signalType = StrategySignalType.ExitLong,
                zScore = -0.18,
                receivedAtMillis = barTs + 1_000L,
            ),
        )
        assertTrue(
            strategySignalJournalHasBarEdge(events, StrategySignalType.ExitLong, barTs)
        )
        assertFalse(
            strategySignalJournalHasBarEdge(events, StrategySignalType.ExitShort, barTs)
        )
        assertFalse(
            strategySignalJournalHasBarEdge(events, StrategySignalType.ExitLong, barTs + 900_000L)
        )
    }
}
