package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexFourThresholdSignalTest {

    @Test
    fun fourThresholds_enterLongUsesEntryLongOnly() {
        val four = ZStrategyFourThresholds(entryLong = 0.8, exitLong = 0.5, entryShort = 1.5, exitShort = 1.0)
        val sig = determineZStrategySignal(-0.7, -0.85, ZStrategyPosition.Flat, four)
        assertEquals(ZStrategySignal.EnterLong, sig)
        val noShort = determineZStrategySignal(1.2, 1.4, ZStrategyPosition.Flat, four)
        assertEquals(ZStrategySignal.None, noShort)
    }

    @Test
    fun fourThresholds_enterShortUsesEntryShortOnly() {
        val four = ZStrategyFourThresholds(entryLong = 1.5, exitLong = 1.0, entryShort = 0.7, exitShort = 0.4)
        val sig = determineZStrategySignal(0.65, 0.75, ZStrategyPosition.Flat, four)
        assertEquals(ZStrategySignal.EnterShort, sig)
    }
}
