package com.example.moexmvp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexWatchdogTest {
    @Test
    fun serviceStaleThreshold_coversThreeMonitorIntervals() {
        assertTrue(watchdogServiceStaleThresholdMs() >= SIGNAL_MONITOR_INTERVAL_MS * 3)
    }

    @Test
    fun status_notStale_whenMonitorDisabled() {
        val status = MoexWatchdogStatus(
            monitorEnabled = false,
            serviceRunning = false,
            serviceLastTickMs = 0L,
            serviceTickCount = 0L,
            serviceStale = false,
            serviceAgeSec = -1L,
            uiLastPingMs = 0L,
            uiAgeSec = -1L,
            serviceRestartCount = 0,
            lastRestartMs = 0L,
            lastRestartReason = "",
            lastAlarmCheckMs = 0L,
        )
        assertTrue(status.overallHealthy)
    }

    @Test
    fun status_unhealthy_whenStaleWhileEnabled() {
        val status = MoexWatchdogStatus(
            monitorEnabled = true,
            serviceRunning = true,
            serviceLastTickMs = System.currentTimeMillis() - watchdogServiceStaleThresholdMs() - 1_000L,
            serviceTickCount = 10L,
            serviceStale = true,
            serviceAgeSec = 120L,
            uiLastPingMs = System.currentTimeMillis(),
            uiAgeSec = 5L,
            serviceRestartCount = 1,
            lastRestartMs = 0L,
            lastRestartReason = "test",
            lastAlarmCheckMs = 0L,
        )
        assertFalse(status.overallHealthy)
    }

    @Test
    fun formatWatchdogAgeSec_formatsMinutes() {
        assertTrue(formatWatchdogAgeSec(90).contains("1м"))
    }

    @Test
    fun formatSignalMonitorForegroundText_showsZWithoutTickNumber() {
        val text = formatSignalMonitorForegroundText(
            monitorEnabled = true,
            serviceLastTickMs = 1L,
            serviceAgeSec = 12L,
            zScore = 0.52,
        )
        assertTrue(text.contains("Z = 0.52"))
        assertFalse(text.contains("#"))
    }

    @Test
    fun formatSignalMonitorForegroundText_waitingWhenNoZ() {
        val text = formatSignalMonitorForegroundText(
            monitorEnabled = true,
            serviceLastTickMs = 0L,
            serviceAgeSec = -1L,
            zScore = null,
        )
        assertTrue(text.contains("Ожидание"))
    }
}
