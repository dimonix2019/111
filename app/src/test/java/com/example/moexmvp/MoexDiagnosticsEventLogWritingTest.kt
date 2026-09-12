package com.example.moexmvp

import org.junit.Assert.assertEquals
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

    @Test
    fun settingsLogUiShowsOnlyLast20() {
        assertEquals(20, SETTINGS_LOG_UI_TAIL)
        val lines = (1..25).map { "line-$it" }
        val tail = takeLogUiTail(lines)
        assertEquals(20, tail.size)
        assertEquals("line-6", tail.first())
        assertEquals("line-25", tail.last())
    }

    @Test
    fun exchangeLogWriting_defaultIsOn() {
        assertTrue(EXCHANGE_LOG_WRITING_DEFAULT)
    }

    @Test
    fun settingsSections_thresholdsAndTwoLogs() {
        assertEquals(
            listOf("Пороги", "Лог приложения", "Лог биржи"),
            SettingsSection.values().map { it.label },
        )
    }
}
