package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexMessageReceivedTimeTest {

    @Test
    fun ensureMessageBodyHasReceivedTime_prependsOnce() {
        val ts = 1_700_000_000_000L
        val once = ensureMessageBodyHasReceivedTime("Z = 1.5", ts)
        assertTrue(messageBodyHasReceivedTimeLine(once))
        assertTrue(once.startsWith("Получено: "))
        assertTrue(once.contains("Z = 1.5"))
        val twice = ensureMessageBodyHasReceivedTime(once, ts + 60_000)
        assertEquals(once, twice)
    }

    @Test
    fun messageBodyHasReceivedTimeLine_detectsExisting() {
        assertFalse(messageBodyHasReceivedTimeLine("Spread crossed"))
        assertTrue(
            messageBodyHasReceivedTimeLine(
                "Получено: 2024-01-01 12:00:00 (МСК)\nSpread crossed"
            )
        )
    }
}
