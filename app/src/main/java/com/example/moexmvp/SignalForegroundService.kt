package com.example.moexmvp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.util.Locale

internal const val SIGNAL_MONITOR_CHANNEL_ID = "moex_signal_monitor_channel"
internal const val SIGNAL_MONITOR_NOTIFICATION_ID = 11001
/** Полный цикл сигналов (MOEX 15м + rolling Z + journal). */
internal const val SIGNAL_MONITOR_INTERVAL_MS = 15_000L
/** Быстрое обновление шторки (Z, возраст тика) — не ждёт тяжёлый signal-work. */
internal const val SIGNAL_MONITOR_PULSE_MS = 10_000L
/** Тяжёлая обработка сигналов реже pulse, чтобы шторка не «замирала». */
internal const val SIGNAL_MONITOR_SIGNAL_WORK_MS = 45_000L
internal const val SIGNAL_MONITOR_1M_FETCH_TIMEOUT_MS = 8_000L
internal const val SIGNAL_MONITOR_OPEN_TRADE_REFRESH_MS = 30_000L

private val bgSignalFallbackThresholds = DynamicThresholds(
    entry = DEFAULT_DYNAMIC_Z_ENTRY,
    exit = DEFAULT_DYNAMIC_Z_EXIT,
    calculatedDate = null
)

class SignalForegroundService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var pulseJob: kotlinx.coroutines.Job? = null
    private var signalWorkJob: kotlinx.coroutines.Job? = null
    private var ticksSinceAppUpdateCheck = 0
    private var signalWorkTickCount = 0
    private var lastForegroundZScore: Double? = null
    private var lastForegroundOpenTrade: SignalMonitorOpenTradeSnapshot? = null
    private var cachedSignalPoints: List<DataPoint>? = null
    private var lastOpenTradeRefreshMs = 0L

    override fun onCreate() {
        super.onCreate()
        createSignalMonitorChannel(this)
        MoexDiagnostics.log(applicationContext, "monitor", "service_onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SIGNAL_MONITOR) {
            MoexDiagnostics.log(applicationContext, "monitor", "service_stop")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            saveSignalMonitorEnabled(this, false)
            return START_NOT_STICKY
        }
        saveSignalMonitorEnabled(this, true)
        MoexDiagnostics.log(applicationContext, "monitor", "service_start foreground")
        startForeground(SIGNAL_MONITOR_NOTIFICATION_ID, buildForegroundNotification())
        scheduleMonitorWatchdog(applicationContext)
        ensureMonitorWorkerRunning()
        return START_STICKY
    }

    private fun ensureMonitorWorkerRunning() {
        if (pulseJob?.isActive == true && signalWorkJob?.isActive == true) return
        pulseJob?.cancel()
        signalWorkJob?.cancel()
        pulseJob = scope.launch {
            while (isActive) {
                runCatching { performNotificationPulse() }
                    .onFailure { e ->
                        MoexDiagnostics.logError(applicationContext, "monitor", e, "pulse failed")
                    }
                delay(SIGNAL_MONITOR_PULSE_MS)
            }
        }
        signalWorkJob = scope.launch {
            while (isActive) {
                runCatching { performSignalWork() }
                    .onFailure { e ->
                        MoexDiagnostics.logError(applicationContext, "monitor", e, "signal_work failed")
                    }
                delay(SIGNAL_MONITOR_SIGNAL_WORK_MS)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        MoexDiagnostics.log(applicationContext, "monitor", "onTaskRemoved")
        if (isBackgroundMonitorEnabled(applicationContext)) {
            scheduleMonitorWatchdog(applicationContext)
            start(applicationContext)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        MoexDiagnostics.log(applicationContext, "monitor", "service_onDestroy")
        pulseJob?.cancel()
        pulseJob = null
        signalWorkJob?.cancel()
        signalWorkJob = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val status = MoexWatchdog.readStatus(this)
        val subtitle = formatSignalMonitorForegroundText(
            monitorEnabled = status.monitorEnabled,
            serviceLastTickMs = status.serviceLastTickMs,
            serviceAgeSec = status.serviceAgeSec,
            zScore = lastForegroundZScore,
            openTrade = lastForegroundOpenTrade,
        )
        val bigText = formatSignalMonitorForegroundBigText(
            monitorEnabled = status.monitorEnabled,
            serviceLastTickMs = status.serviceLastTickMs,
            serviceAgeSec = status.serviceAgeSec,
            zScore = lastForegroundZScore,
            openTrade = lastForegroundOpenTrade,
        )
        return NotificationCompat.Builder(this, SIGNAL_MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MOEX signal monitor")
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    /** Лёгкий тик: шторка и heartbeat не зависят от тяжёлого rolling-Z по 255д. */
    private suspend fun performNotificationPulse() = withContext(Dispatchers.IO) {
        MoexWatchdog.recordServiceTick(applicationContext)
        val points = runCatching {
            loadZStrategySignalSeries(
                applicationContext,
                mode = PortfolioM15LoadMode.CACHE_ONLY,
            )
        }.getOrNull()
        if (points != null && points.size >= 2) {
            val liveZ = if (isMoexNetworkAvailable(applicationContext)) {
                runCatching {
                    withTimeout(SIGNAL_MONITOR_1M_FETCH_TIMEOUT_MS) {
                        fetchMarketsIntraday1mLive()
                    }
                }.getOrNull()?.let { snap -> liveZScoreFromIntraday1m(points, snap) }
            } else {
                null
            }
            lastForegroundZScore = liveZ
                ?: cachedSignalPoints?.lastOrNull()?.zScore
                ?: points.last().zScore

            val now = System.currentTimeMillis()
            if (now - lastOpenTradeRefreshMs >= SIGNAL_MONITOR_OPEN_TRADE_REFRESH_MS) {
                val prep = cachedSignalPoints?.takeIf { it.size >= 2 } ?: points
                lastForegroundOpenTrade = resolveSignalMonitorOpenTrade(applicationContext, prep)
                lastOpenTradeRefreshMs = now
            }
        }
        withContext(Dispatchers.Main) {
            refreshForegroundNotification()
        }
    }

    private suspend fun performSignalWork() = withContext(Dispatchers.IO) {
        signalWorkTickCount++
        ticksSinceAppUpdateCheck++
        if (ticksSinceAppUpdateCheck * SIGNAL_MONITOR_SIGNAL_WORK_MS >= APP_UPDATE_CHECK_INTERVAL_MS) {
            ticksSinceAppUpdateCheck = 0
            runCatching { checkRemoteAppUpdateAndNotify(applicationContext) }
                .onFailure { e ->
                    MoexDiagnostics.logError(applicationContext, "monitor", e, "app_update_check")
                }
        }

        val points = runCatching {
            loadZStrategySignalSeries(
                applicationContext,
                mode = PortfolioM15LoadMode.INCREMENTAL,
            )
        }.recoverCatching { e ->
            if (!MoexDiagnostics.isTransientNetworkError(e)) throw e
            MoexDiagnostics.logNetworkErrorThrottled(
                applicationContext,
                "monitor",
                e,
                "load_15m_network_fallback_cache",
            )
            loadZStrategySignalSeries(
                applicationContext,
                mode = PortfolioM15LoadMode.CACHE_ONLY,
            )
        }.getOrElse { e ->
            if (MoexDiagnostics.isTransientNetworkError(e)) {
                MoexDiagnostics.logNetworkErrorThrottled(applicationContext, "monitor", e, "load_15m_failed")
            } else {
                MoexDiagnostics.logError(applicationContext, "monitor", e, "load_15m_failed")
            }
            return@withContext
        }
        if (points.size < 2) {
            if (signalWorkTickCount <= 3 || signalWorkTickCount % 10 == 0) {
                MoexDiagnostics.log(applicationContext, "monitor", "signal#$signalWorkTickCount points=${points.size} (wait data)")
            }
            return@withContext
        }

        val monitorPoints = if (isMoexNetworkAvailable(applicationContext)) {
            runCatching {
                withTimeout(SIGNAL_MONITOR_1M_FETCH_TIMEOUT_MS) {
                    fetchMarketsIntraday1mLive()
                }
            }.getOrNull()
                ?.let { snap -> m15PointsWithLiveFormingFromIntraday1m(points, snap) }
        } else {
            null
        } ?: points

        val signalPoints = prepareM15PointsForZStrategySignalDetection(monitorPoints)
        cachedSignalPoints = signalPoints
        lastForegroundZScore = signalPoints.last().zScore
        lastForegroundOpenTrade = resolveSignalMonitorOpenTrade(applicationContext, signalPoints)
        lastOpenTradeRefreshMs = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            refreshForegroundNotification()
        }

        val signalThresholds = loadRealTradeZThresholds(applicationContext, bgSignalFallbackThresholds)
        maybeBackfillMissedLiveZSignalsAfterStaleZFix(
            applicationContext,
            signalPoints,
            signalThresholds,
        )
        runCatching {
            processOpenTradeRedRiskNotifications(
                context = applicationContext,
                points = signalPoints,
                entryThreshold = signalThresholds.entry,
            )
        }.onFailure { e ->
            MoexDiagnostics.logError(applicationContext, "monitor", e, "open_trade_red_risk_notify")
        }
        var dayLimit = loadDailySignalLimit(applicationContext, LocalDate.now())
        val initialPosition = loadSavedStrategyPosition(applicationContext)
        val lastProcessedBarTs = resolveLastProcessed15mBarTimestampForReplay(applicationContext)
        val (signalEdges, replayPosition) = collectZStrategy15mSignalEdgesSinceProcessedBar(
            points = signalPoints,
            lastProcessedBarTimestampMillis = lastProcessedBarTs,
            initialPosition = initialPosition,
            thresholds = signalThresholds,
        )
        zStrategyReplayBarIndexRange(signalPoints, lastProcessedBarTs)?.let { range ->
            persistM15LiveBarSnapshots(applicationContext, range.map { signalPoints[it] })
        }

        if (signalEdges.isEmpty()) {
            if (shouldAdvanceLastProcessed15mBar(signalPoints, lastProcessedBarTs)) {
                saveLastProcessed15mBarTimestamp(applicationContext, signalPoints.last().timestampMillis)
            }
            if (replayPosition != initialPosition) {
                saveStrategyPosition(applicationContext, replayPosition)
            }
            if (signalWorkTickCount <= 3 || signalWorkTickCount % 10 == 0) {
                val last = signalPoints.last()
                MoexDiagnostics.log(
                    applicationContext,
                    "monitor",
                    "signal#$signalWorkTickCount ok bars=${signalPoints.size} Z=${String.format(Locale.US, "%.2f", last.zScore)} " +
                        "pos=$replayPosition thr=${signalThresholds.entry}/${signalThresholds.exit}",
                )
            }
            return@withContext
        }

        var currentPosition = initialPosition
        for (edge in signalEdges) {
            val edgeSignal = edge.signal
            val bar = edge.bar
            val latestZScore = bar.zScore
            val latestSpreadPercent = bar.spreadPercent
            val latestTimestampMillis = bar.timestampMillis

            if (is15mStrategySignalEdgeConsumed(applicationContext, latestTimestampMillis, edgeSignal)) {
                MoexDiagnostics.log(
                    applicationContext,
                    "monitor",
                    "signal_dedup $edgeSignal bar=$latestTimestampMillis Z=${String.format(Locale.US, "%.2f", latestZScore)}",
                )
                currentPosition = edge.positionAfter
                continue
            }

            MoexDiagnostics.log(
                applicationContext,
                "signal",
                "$edgeSignal bar=$latestTimestampMillis Z=${String.format(Locale.US, "%.2f", latestZScore)} " +
                    "spread=${String.format(Locale.US, "%.3f", latestSpreadPercent)}% pos=${edge.positionBefore}",
            )

            val signalType = zStrategySignalToStrategySignalType(edgeSignal)
            val recorded = recordStrategySignalEvent(
                context = applicationContext,
                signalType = signalType,
                zScore = latestZScore,
                timestampMillis = latestTimestampMillis,
            )
            mark15mStrategySignalEdgeConsumed(applicationContext, latestTimestampMillis, edgeSignal)
            currentPosition = edge.positionAfter

            if (!recorded) {
                MoexDiagnostics.log(
                    applicationContext,
                    "signal",
                    "journal_skip $edgeSignal bar=$latestTimestampMillis",
                )
                continue
            }

            when (edgeSignal) {
                ZStrategySignal.EnterLong -> {
                    if (dayLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                        val sent = showZStrategySignalPushNotification(
                            context = applicationContext,
                            title = "Вход: LONG TATN / SHORT TATNP",
                            body = String.format(
                                Locale.US,
                                "Z <= -%.1f (текущий Z=%.2f)",
                                signalThresholds.entry,
                                latestZScore
                            ),
                            virtualTradeTap = entryVirtualTradeTapIfManualAccept(
                                applicationContext,
                                StrategySignalType.EnterLong,
                                latestZScore,
                                latestTimestampMillis
                            )
                        )
                        if (sent) {
                            dayLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                        }
                    }
                    runSandboxAutoEntryIfNeeded(
                        applicationContext,
                        StrategySignalType.EnterLong,
                        latestZScore,
                        latestTimestampMillis,
                        latestSpreadPercent
                    )
                }

                ZStrategySignal.EnterShort -> {
                    if (dayLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                        val sent = showZStrategySignalPushNotification(
                            context = applicationContext,
                            title = "Вход: LONG TATNP / SHORT TATN",
                            body = String.format(
                                Locale.US,
                                "Z >= +%.1f (текущий Z=%.2f)",
                                signalThresholds.entry,
                                latestZScore
                            ),
                            virtualTradeTap = entryVirtualTradeTapIfManualAccept(
                                applicationContext,
                                StrategySignalType.EnterShort,
                                latestZScore,
                                latestTimestampMillis
                            )
                        )
                        if (sent) {
                            dayLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                        }
                    }
                    runSandboxAutoEntryIfNeeded(
                        applicationContext,
                        StrategySignalType.EnterShort,
                        latestZScore,
                        latestTimestampMillis,
                        latestSpreadPercent
                    )
                }

                ZStrategySignal.ExitLong -> {
                    clearPendingVirtualTradeProposal(applicationContext)
                    if (dayLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                        val sent = showZStrategySignalPushNotification(
                            context = applicationContext,
                            title = "Выход: закрыть LONG TATN / SHORT TATNP",
                            body = String.format(
                                Locale.US,
                                "Z >= -%.1f (текущий Z=%.2f)",
                                signalThresholds.exit,
                                latestZScore
                            )
                        )
                        if (sent) {
                            dayLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                        }
                    }
                    runSandboxAutoExitIfNeeded(
                        applicationContext,
                        StrategySignalType.ExitLong,
                        latestZScore,
                        latestTimestampMillis,
                    )
                }

                ZStrategySignal.ExitShort -> {
                    clearPendingVirtualTradeProposal(applicationContext)
                    if (dayLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                        val sent = showZStrategySignalPushNotification(
                            context = applicationContext,
                            title = "Выход: закрыть LONG TATNP / SHORT TATN",
                            body = String.format(
                                Locale.US,
                                "Z <= +%.1f (текущий Z=%.2f)",
                                signalThresholds.exit,
                                latestZScore
                            )
                        )
                        if (sent) {
                            dayLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                        }
                    }
                    runSandboxAutoExitIfNeeded(
                        applicationContext,
                        StrategySignalType.ExitShort,
                        latestZScore,
                        latestTimestampMillis,
                    )
                }

                ZStrategySignal.None -> Unit
            }
        }

        if (shouldAdvanceLastProcessed15mBar(signalPoints, lastProcessedBarTs)) {
            if (signalEdges.isEmpty()) {
                val last = signalPoints.last()
                MoexDiagnostics.log(
                    applicationContext,
                    "monitor",
                    "advance_no_edges bar=${last.tradeDate} Z=${String.format(Locale.US, "%.2f", last.zScore)} " +
                        "thr=${signalThresholds.entry}/${signalThresholds.exit} pos=$replayPosition",
                )
            }
            saveLastProcessed15mBarTimestamp(applicationContext, signalPoints.last().timestampMillis)
        }
        saveDailySignalLimit(applicationContext, dayLimit)
        saveStrategyPosition(applicationContext, currentPosition)
    }

    private fun refreshForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(SIGNAL_MONITOR_NOTIFICATION_ID, buildForegroundNotification())
    }

    companion object {
        private const val MONITOR_PREFS_NAME = "moex_alert_prefs"
        private const val PREF_BACKGROUND_SIGNAL_ENABLED = "background_signal_enabled"

        const val ACTION_START_SIGNAL_MONITOR = "com.example.moexmvp.action.START_SIGNAL_MONITOR"
        const val ACTION_STOP_SIGNAL_MONITOR = "com.example.moexmvp.action.STOP_SIGNAL_MONITOR"

        fun start(context: Context) {
            val intent = Intent(context, SignalForegroundService::class.java).apply {
                action = ACTION_START_SIGNAL_MONITOR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            scheduleMonitorWatchdog(context.applicationContext)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SignalForegroundService::class.java).apply {
                action = ACTION_STOP_SIGNAL_MONITOR
            }
            context.startService(intent)
        }

        internal fun isBackgroundMonitorEnabled(context: Context): Boolean {
            return context.getSharedPreferences(MONITOR_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_BACKGROUND_SIGNAL_ENABLED, true)
        }

        private fun saveSignalMonitorEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(MONITOR_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_BACKGROUND_SIGNAL_ENABLED, enabled)
                .apply()
        }

        private fun createSignalMonitorChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                SIGNAL_MONITOR_CHANNEL_ID,
                "MOEX Signal Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground monitoring for MOEX alerts"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
