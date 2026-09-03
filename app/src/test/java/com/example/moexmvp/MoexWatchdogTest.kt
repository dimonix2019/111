package com.example.moexmvp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

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
    fun formatSignalMonitorForegroundText_showsSpreadWithoutTickNumber() {
        val text = formatSignalMonitorForegroundText(
            monitorEnabled = true,
            serviceLastTickMs = 1L,
            serviceAgeSec = 12L,
            spreadPercent = 5.23,
        )
        assertTrue(text.contains("S 5,23%") || text.contains("S 5.23%"))
        assertFalse(text.contains("#"))
    }

    @Test
    fun formatSignalMonitorForegroundText_waitingWhenNoTick() {
        val text = formatSignalMonitorForegroundText(
            monitorEnabled = true,
            serviceLastTickMs = 0L,
            serviceAgeSec = -1L,
            spreadPercent = null,
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
            spreadPercent = 5.23,
            openTrade = trade,
        )
        assertTrue(text.contains("S 5,23%") || text.contains("S 5.23%"))
        assertTrue(text.contains("2S 15.06 18:45"))
        assertTrue(text.contains("Z₀0.84"))
        assertTrue(text.contains("+120₽"))
    }

    @Test
    fun formatSignalMonitorForegroundBigText_splitsSpreadAndTrade() {
        val trade = SignalMonitorOpenTradeSnapshot("3L", "15.06 18:45", 0.84, -50.0)
        val text = formatSignalMonitorForegroundBigText(
            true, 1L, 12L, 5.23, trade,
        )
        assertTrue(text.contains('\n'))
        assertTrue(text.contains("S 5,23%") || text.contains("S 5.23%"))
        assertTrue(text.contains("3L"))
        assertTrue(text.contains("-50₽"))
    }

    @Test
    fun formatSignalMonitorForegroundBigText_showsBriefAndChecklist() {
        val shade = WebDeskShadeSnapshot(
            icon = "↘",
            statusLine = "Подготовка к закрытию · ждём: edge",
            briefLine = "Long · S 2,83% · до L вых 4: 1,17",
            spreadPercent = 2.83,
            position = "LONG",
        )
        val text = formatSignalMonitorForegroundBigText(
            monitorEnabled = true,
            serviceLastTickMs = 1L,
            serviceAgeSec = 3L,
            spreadPercent = 2.83,
            openTrade = null,
            deskShade = shade,
        )
        assertTrue(text.startsWith("↘ Long · S 2,83% · до L вых 4: 1,17"))
        assertTrue(text.contains('\n'))
        assertTrue(text.contains("• Подготовка к закрытию · ждём: edge"))
        assertFalse(text.contains("Z "))
    }

    @Test
    fun buildWebDeskShadeSnapshot_prepCloseNearExitSpread() {
        val now = 1_700_000_000_000L
        val bars = JSONArray()
            .put(
                JSONObject()
                    .put("time", "2026-08-06 11:28:00")
                    .put("timestampMs", now - 140_000L)
                    .put("spread", 3.95)
                    .put("z", -1.7),
            )
            .put(
                JSONObject()
                    .put("time", "2026-08-06 11:29:00")
                    .put("timestampMs", now - 80_000L)
                    .put("spread", 3.90)
                    .put("z", -1.75),
            )
        val root = JSONObject()
            .put("position", "LONG")
            .put(
                "settings",
                JSONObject()
                    .put("auto_execute", true)
                    .put("signal_mode", "tip1m")
                    .put("spread_level_mode", true)
                    .put("spread_exit_narrow", 4.0)
                    .put("entry_z", 1.6)
                    .put("exit_z", 1.3),
            )
            .put("monitor", JSONObject().put("running", true))
            .put(
                "spread_levels",
                JSONObject()
                    .put("spread", 3.90)
                    .put(
                        "levels",
                        JSONObject()
                            .put("enter_wide", 6.2)
                            .put("exit_wide", 5.8)
                            .put("enter_narrow", 3.2)
                            .put("exit_narrow", 4.0),
                    ),
            )
            .put("bars", bars)
            .put("open", JSONObject().put("direction", "LONG"))
        val weekday = ZonedDateTime.of(2026, 8, 6, 11, 30, 0, 0, moexZoneId)
        val snap = buildWebDeskShadeSnapshot(root, nowMs = now, nowMsk = weekday)!!
        assertEquals("LONG", snap.position)
        assertEquals(3.90, snap.spreadPercent!!, 1e-6)
        assertTrue(snap.statusLine.contains("Подготовка к закрытию"))
        assertTrue(snap.statusLine.contains("ждём: edge"))
        assertEquals("↘", snap.icon)
        assertTrue(snap.briefLine.startsWith("Long · S 3,90%"))
        assertTrue(snap.briefLine.contains("до L вых 4:"))
        assertTrue(snap.briefLine.contains("0,10"))
    }

    @Test
    fun buildShadeBriefLine_flatShowsRegimeAndNearestEntry() {
        val line = buildShadeBriefLine(
            pos = "FLAT",
            curS = 2.70,
            spreadOn = true,
            enterW = 6.2,
            exitW = 5.8,
            enterN = 3.2,
            exitN = 4.0,
            needLong = 0.0,
            needShort = 3.5,
            needExitLong = null,
            needExitShort = null,
        )
        assertTrue(line.contains("S 2,70%"))
        assertTrue(line.contains("режим узкий"))
        assertTrue(line.contains("до L вх 3,2: +0,50"))
    }

    @Test
    fun buildShadeBriefLine_longShowsDistanceToExit() {
        val line = buildShadeBriefLine(
            pos = "LONG",
            curS = 2.83,
            spreadOn = true,
            enterW = 6.2,
            exitW = 5.8,
            enterN = 3.2,
            exitN = 4.0,
            needLong = null,
            needShort = null,
            needExitLong = 1.17,
            needExitShort = null,
        )
        assertEquals("Long · S 2,83% · до L вых 4: 1,17", line)
    }
}
