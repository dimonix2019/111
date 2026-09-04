package com.example.moexmvp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexDiagnosticsEventLogWritingTest {

    @Test
    fun eventLogWriting_defaultIsOff() {
        assertFalse(EVENT_LOG_WRITING_DEFAULT)
    }

    @Test
    fun shouldWriteEventLogToFile_skipsWhenUserDisabled() {
        assertFalse(shouldWriteEventLogToFile(compileEnabled = true, userEnabled = false))
        assertTrue(shouldWriteEventLogToFile(compileEnabled = true, userEnabled = true))
        assertFalse(shouldWriteEventLogToFile(compileEnabled = false, userEnabled = true))
    }
}
