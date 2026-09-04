package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryLevelAlertsTest {

    @Test
    fun detectEntryLevelCrosses_longDownThroughNarrow() {
        val crosses = detectEntryLevelCrosses(3.4, 3.1, longEnterPct = 3.2, shortEnterPct = 6.1)
        assertEquals(listOf(EntryAlertSide.Long), crosses)
    }

    @Test
    fun detectEntryLevelCrosses_shortUpThroughWide() {
        val crosses = detectEntryLevelCrosses(5.9, 6.2, longEnterPct = 3.2, shortEnterPct = 6.1)
        assertEquals(listOf(EntryAlertSide.Short), crosses)
    }

    @Test
    fun detectEntryLevelCrosses_noCrossInsideBand() {
        assertTrue(detectEntryLevelCrosses(4.0, 5.0, 3.2, 6.1).isEmpty())
    }

    @Test
    fun entryAlertSideFromDirectionLabel_parsesLongShort() {
        assertEquals(EntryAlertSide.Long, entryAlertSideFromDirectionLabel("LONG"))
        assertEquals(EntryAlertSide.Short, entryAlertSideFromDirectionLabel("short"))
        assertEquals(null, entryAlertSideFromDirectionLabel("FLAT"))
    }

    @Test
    fun entryLevelAlertNotificationId_uniquePerSide() {
        assertTrue(
            entryLevelAlertNotificationId(EntryAlertSide.Long) !=
                entryLevelAlertNotificationId(EntryAlertSide.Short),
        )
    }
}
