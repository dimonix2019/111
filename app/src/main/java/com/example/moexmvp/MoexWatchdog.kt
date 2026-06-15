package com.example.moexmvp

import android.app.ActivityManager
import android.content.Context
import java.util.Locale

/** Интервал проверки watchdog из UI (приложение на экране). */
internal const val WATCHDOG_UI_POLL_MS = 30_000L

/** Интервал alarm-проверки, когда UI закрыт. */
internal const val WATCHDOG_ALARM_INTERVAL_MS = 5 * 60_000L

/** Сервис «мёртв», если нет тика дольше этого порога. */
internal fun watchdogServiceStaleThresholdMs(): Long =
    SIGNAL_MONITOR_INTERVAL_MS * 3 + 15_000L

/** UI «не на связи» для сервиса — только информативно (не перезапуск UI). */
internal const val WATCHDOG_UI_STALE_MS = 10 * 60_000L

internal data class MoexWatchdogStatus(
    val monitorEnabled: Boolean,
    val serviceRunning: Boolean,
    val serviceLastTickMs: Long,
    val serviceTickCount: Long,
    val serviceStale: Boolean,
    val serviceAgeSec: Long,
    val uiLastPingMs: Long,
    val uiAgeSec: Long,
    val serviceRestartCount: Int,
    val lastRestartMs: Long,
    val lastRestartReason: String,
    val lastAlarmCheckMs: Long,
) {
    val overallHealthy: Boolean
        get() = !monitorEnabled || (!serviceStale && serviceRunning)
}

internal object MoexWatchdog {
    private const val PREFS = ALERT_PREFS_NAME
    private const val PREF_SERVICE_LAST_TICK_MS = "watchdog_service_last_tick_ms"
    private const val PREF_SERVICE_TICK_COUNT = "watchdog_service_tick_count"
    private const val PREF_UI_LAST_PING_MS = "watchdog_ui_last_ping_ms"
    private const val PREF_SERVICE_RESTART_COUNT = "watchdog_service_restart_count"
    private const val PREF_LAST_RESTART_MS = "watchdog_last_restart_ms"
    private const val PREF_LAST_RESTART_REASON = "watchdog_last_restart_reason"
    private const val PREF_LAST_ALARM_CHECK_MS = "watchdog_last_alarm_check_ms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordServiceTick(context: Context) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(PREF_SERVICE_LAST_TICK_MS, now)
            .putLong(PREF_SERVICE_TICK_COUNT, readServiceTickCount(context) + 1)
            .apply()
    }

    fun recordUiPing(context: Context) {
        prefs(context).edit()
            .putLong(PREF_UI_LAST_PING_MS, System.currentTimeMillis())
            .apply()
    }

    fun recordAlarmCheck(context: Context) {
        prefs(context).edit()
            .putLong(PREF_LAST_ALARM_CHECK_MS, System.currentTimeMillis())
            .apply()
    }

    fun readStatus(context: Context): MoexWatchdogStatus {
        val app = context.applicationContext
        val monitorEnabled = SignalForegroundService.isBackgroundMonitorEnabled(app)
        val now = System.currentTimeMillis()
        val p = prefs(app)
        val serviceLastTick = p.getLong(PREF_SERVICE_LAST_TICK_MS, 0L)
        val uiLastPing = p.getLong(PREF_UI_LAST_PING_MS, 0L)
        val serviceAgeSec = if (serviceLastTick > 0L) ((now - serviceLastTick) / 1000L).coerceAtLeast(0L) else -1L
        val uiAgeSec = if (uiLastPing > 0L) ((now - uiLastPing) / 1000L).coerceAtLeast(0L) else -1L
        val serviceStale = monitorEnabled && isServiceHeartbeatStale(app, serviceLastTick, now)
        return MoexWatchdogStatus(
            monitorEnabled = monitorEnabled,
            serviceRunning = isMonitorServiceRunning(app),
            serviceLastTickMs = serviceLastTick,
            serviceTickCount = p.getLong(PREF_SERVICE_TICK_COUNT, 0L),
            serviceStale = serviceStale,
            serviceAgeSec = serviceAgeSec,
            uiLastPingMs = uiLastPing,
            uiAgeSec = uiAgeSec,
            serviceRestartCount = p.getInt(PREF_SERVICE_RESTART_COUNT, 0),
            lastRestartMs = p.getLong(PREF_LAST_RESTART_MS, 0L),
            lastRestartReason = p.getString(PREF_LAST_RESTART_REASON, "").orEmpty(),
            lastAlarmCheckMs = p.getLong(PREF_LAST_ALARM_CHECK_MS, 0L),
        )
    }

    fun isServiceHeartbeatStale(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val last = prefs(context).getLong(PREF_SERVICE_LAST_TICK_MS, 0L)
        return isServiceHeartbeatStale(context, last, now)
    }

    private fun isServiceHeartbeatStale(context: Context, lastTickMs: Long, nowMs: Long): Boolean {
        if (!SignalForegroundService.isBackgroundMonitorEnabled(context)) return false
        if (lastTickMs <= 0L) return true
        return nowMs - lastTickMs > watchdogServiceStaleThresholdMs()
    }

    fun isMonitorServiceRunning(context: Context): Boolean {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == SignalForegroundService::class.java.name }
    }

    private fun readServiceTickCount(context: Context): Long =
        prefs(context).getLong(PREF_SERVICE_TICK_COUNT, 0L)

    /** Перезапуск FGS, если монитор включён, но пульс устарел или сервис не в списке running. */
    fun ensureMonitorServiceRunning(context: Context, reason: String): Boolean {
        val app = context.applicationContext
        if (!SignalForegroundService.isBackgroundMonitorEnabled(app)) return false
        val status = readStatus(app)
        if (!status.serviceStale && status.serviceRunning) return false
        val count = prefs(app).getInt(PREF_SERVICE_RESTART_COUNT, 0) + 1
        prefs(app).edit()
            .putInt(PREF_SERVICE_RESTART_COUNT, count)
            .putLong(PREF_LAST_RESTART_MS, System.currentTimeMillis())
            .putString(PREF_LAST_RESTART_REASON, reason)
            .apply()
        MoexDiagnostics.log(
            app,
            "watchdog",
            "restart_service reason=$reason count=$count stale=${status.serviceStale} running=${status.serviceRunning}",
        )
        SignalForegroundService.start(app)
        scheduleMonitorWatchdog(app)
        return true
    }

    fun performMonitorWatchdogCheck(context: Context, reason: String) {
        val app = context.applicationContext
        if (!SignalForegroundService.isBackgroundMonitorEnabled(app)) return
        recordAlarmCheck(app)
        ensureMonitorServiceRunning(app, reason)
    }
}

internal fun scheduleMonitorWatchdog(context: Context) {
    MonitorWatchdogReceiver.scheduleNext(context.applicationContext)
}

internal fun formatWatchdogAgeSec(ageSec: Long): String = when {
    ageSec < 0 -> "—"
    ageSec < 60 -> "${ageSec}с"
    ageSec < 3600 -> "${ageSec / 60}м ${ageSec % 60}с"
    else -> "${ageSec / 3600}ч ${(ageSec % 3600) / 60}м"
}

/** Снимок открытой сделки для ongoing-уведомления монитора. */
internal data class SignalMonitorOpenTradeSnapshot(
    val badge: String,
    val openedAt: String,
    val entryZ: Double,
    val pnlRub: Double,
)

/** «1 short» + short → «1S». */
internal fun compactMonitorTradeBadge(tradeDisplayId: String, directionLabel: String): String {
    val num = tradeDisplayId.trim().substringBefore(' ').ifBlank { tradeDisplayId }
    val dir = directionLabel.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    return "$num$dir"
}

/** «2026-06-15 18:45» → «15.06 18:45». */
internal fun compactMonitorDateTimeMsk(text: String): String {
    if (text.isBlank() || text == "—") return "—"
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})\s+(\d{2}:\d{2})""").find(text.trim()) ?: return text.take(14)
    val (_, month, day, time) = m.destructured
    return "$day.$month $time"
}

internal fun formatCompactSignedPnlRub(rub: Double): String {
    if (rub.isNaN()) return "—"
    val rounded = kotlin.math.round(rub).toInt()
    return if (rounded >= 0) "+${rounded}₽" else "${rounded}₽"
}

internal fun signalMonitorOpenTradeSnapshot(exec: SandboxSpreadExecUi): SignalMonitorOpenTradeSnapshot? {
    if (exec.signalType != StrategySignalType.EnterLong &&
        exec.signalType != StrategySignalType.EnterShort
    ) {
        return null
    }
    val openedRaw = exec.entrySignalBarTimeMsk.takeIf { it.isNotBlank() && it != "—" }
        ?: exec.entryTimeMsk
    return SignalMonitorOpenTradeSnapshot(
        badge = compactMonitorTradeBadge(exec.tradeDisplayId, exec.directionLabel),
        openedAt = compactMonitorDateTimeMsk(openedRaw),
        entryZ = exec.zScore,
        pnlRub = exec.netPnlRubApprox,
    )
}

internal fun formatSignalMonitorOpenTradeLine(trade: SignalMonitorOpenTradeSnapshot): String =
    "${trade.badge} ${trade.openedAt} Z₀${"%.2f".format(Locale.US, trade.entryZ)} " +
        formatCompactSignedPnlRub(trade.pnlRub)

/** Последняя открытая демо-сделка с актуальным MTM для шторки. */
internal fun resolveSignalMonitorOpenTrade(
    context: Context,
    points: List<DataPoint>,
): SignalMonitorOpenTradeSnapshot? {
    if (points.isEmpty()) return null
    val opens = TinkoffSandboxSpreadExecLog.loadRecent(context)
        .filter {
            it.signalType == StrategySignalType.EnterLong ||
                it.signalType == StrategySignalType.EnterShort
        }
    val latest = opens.maxByOrNull { it.barTimestampMillis } ?: return null
    val leverage = TinkoffSandboxStorage.getSandboxNotifyLeverage(context)
    val enriched = enrichOpenSandboxExecutions(
        executions = listOf(latest),
        points = points,
        notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
        leverage = leverage,
    ).firstOrNull() ?: return null
    return signalMonitorOpenTradeSnapshot(enriched)
}

/** Текст ongoing-уведомления фонового монитора в шторке. */
internal fun formatSignalMonitorForegroundText(
    monitorEnabled: Boolean,
    serviceLastTickMs: Long,
    serviceAgeSec: Long,
    zScore: Double?,
    openTrade: SignalMonitorOpenTradeSnapshot? = null,
): String = when {
    !monitorEnabled -> "Монитор выключен"
    serviceLastTickMs <= 0L -> "Ожидание данных…"
    openTrade != null && zScore != null -> {
        val zPart = "Z=${"%.2f".format(Locale.US, zScore)}"
        "$zPart | ${formatSignalMonitorOpenTradeLine(openTrade)}"
    }
    openTrade != null -> formatSignalMonitorOpenTradeLine(openTrade)
    zScore != null ->
        "Z = ${"%.2f".format(Locale.US, zScore)} · ${formatWatchdogAgeSec(serviceAgeSec)} назад"
    else -> "Обновлено ${formatWatchdogAgeSec(serviceAgeSec)} назад"
}

/** Развёрнутый текст в шторке (две строки при открытой сделке). */
internal fun formatSignalMonitorForegroundBigText(
    monitorEnabled: Boolean,
    serviceLastTickMs: Long,
    serviceAgeSec: Long,
    zScore: Double?,
    openTrade: SignalMonitorOpenTradeSnapshot?,
): String {
    val collapsed = formatSignalMonitorForegroundText(
        monitorEnabled,
        serviceLastTickMs,
        serviceAgeSec,
        zScore,
        openTrade,
    )
    if (openTrade == null || zScore == null) return collapsed
    return buildString {
        append("Z=${"%.2f".format(Locale.US, zScore)} · ${formatWatchdogAgeSec(serviceAgeSec)} назад")
        append('\n')
        append(formatSignalMonitorOpenTradeLine(openTrade))
    }
}
