package com.example.moexmvp

import org.junit.Assert.assertTrue
import org.junit.Test

class MoexDiagnosticsExportTest {

    @Test
    fun eventLogExportFileName_containsVersionAndTxt() {
        val name = MoexDiagnostics.eventLogExportFileName()
        assertTrue(name.startsWith("moex-event-log-v"))
        assertTrue(name.endsWith(".txt"))
        assertTrue(name.contains(BuildConfig.VERSION_NAME))
    }
}
