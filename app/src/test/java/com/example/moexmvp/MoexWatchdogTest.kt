package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexWatchdogTest {
    @Test
    fun serviceStaleThreshold_coversFourNotificationPulses() {
        assertTrue(watchdogServiceStaleThresholdMs() >= SIGNAL_MONITOR_PULSE_MS * 4)
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

    @Test
    fun compactMonitorDateTimeMsk_shortensIsoDate() {
        assertEquals("15.06 18:45", compactMonitorDateTimeMsk("2026-06-15 18:45:12"))
    }

    @Test
    fun signalMonitorOpenTradeSnapshot_usesWeeklyTradeIdNotInternalD() {
        val exec = SandboxSpreadExecUi(
            tradeId = "D-003",
            tradeDisplayId = "2 short",
            signalType = StrategySignalType.EnterShort,
            zScore = 0.84,
            barTimestampMillis = 1L,
            executedAtMillis = 1L,
            entrySpreadPercent = 6.0,
            source = PortfolioExecSource.MANUAL,
            directionLabel = "short",
            entryTimeMsk = "2026-06-15 18:45",
            entrySignalBarTimeMsk = "2026-06-15 18:45",
            longLegTicker = "TATNP",
            shortLegTicker = "TATN",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "1+1",
            confirmLabel = "ручное",
            correlationTag = "t",
            notificationIdsText = "—",
            legs = emptyList(),
            netPnlRubApprox = 120.0,
        )
        val snap = signalMonitorOpenTradeSnapshot(exec)!!
        assertEquals("2S", snap.badge)
        assertFalse(snap.badge.contains("D-"))
    }

    @Test
    fun signalMonitorTradeDirectionBadge_formatsLongAndShort() {
        assertEquals("1S", signalMonitorTradeDirectionBadge("1 short", StrategySignalType.EnterShort))
        assertEquals("1L", signalMonitorTradeDirectionBadge("1 long", StrategySignalType.EnterLong))
    }

    @Test
    fun formatSignalMonitorForegroundText_includesOpenTradeCompact() {
        val trade = SignalMonitorOpenTradeSnapshot(
            badge = "2S",
            openedAt = "15.06 18:45",
            entryZ = 0.84,
            pnlRub = 120.0,
        )
        val text = formatSignalMonitorForegroundText(
            monitorEnabled = true,
            serviceLastTickMs = 1L,
            serviceAgeSec = 5L,
            zScore = 0.52,
            openTrade = trade,
        )
        assertTrue(text.contains("Z=0.52"))
        assertTrue(text.contains("2S 15.06 18:45"))
        assertTrue(text.contains("Z₀0.84"))
        assertTrue(text.contains("+120₽"))
    }

    @Test
    fun formatSignalMonitorForegroundBigText_splitsZAndTrade() {
        val trade = SignalMonitorOpenTradeSnapshot("3L", "15.06 18:45", 0.84, -50.0)
        val text = formatSignalMonitorForegroundBigText(true, 1L, 12L, 0.52, trade)
        assertTrue(text.contains('\n'))
        assertTrue(text.contains("3L"))
        assertTrue(text.contains("-50₽"))
    }
}
