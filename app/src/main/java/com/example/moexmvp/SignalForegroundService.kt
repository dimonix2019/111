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
import java.time.LocalDate
import java.util.Locale

internal const val SIGNAL_MONITOR_CHANNEL_ID = "moex_signal_monitor_channel"
internal const val SIGNAL_MONITOR_NOTIFICATION_ID = 11001
internal const val SIGNAL_MONITOR_INTERVAL_MS = 15_000L

private val bgSignalFallbackThresholds = DynamicThresholds(
    entry = DEFAULT_DYNAMIC_Z_ENTRY,
    exit = DEFAULT_DYNAMIC_Z_EXIT,
    calculatedDate = null
)

class SignalForegroundService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var workerStarted = false
    private var ticksSinceAppUpdateCheck = 0
    private var monitorTickCount = 0

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
        if (!workerStarted) {
            workerStarted = true
            scope.launch {
                while (isActive) {
                    runCatching { performSignalMonitorTick() }
                        .onFailure { e ->
                            MoexDiagnostics.logError(applicationContext, "monitor", e, "tick failed")
                        }
                    delay(SIGNAL_MONITOR_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        MoexDiagnostics.log(applicationContext, "monitor", "service_onDestroy")
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
        return NotificationCompat.Builder(this, SIGNAL_MONITOR_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MOEX signal monitor")
            .setContentText("Фоновый мониторинг сигналов активен")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private suspend fun performSignalMonitorTick() = withContext(Dispatchers.IO) {
        monitorTickCount++
        ticksSinceAppUpdateCheck++
        if (ticksSinceAppUpdateCheck * SIGNAL_MONITOR_INTERVAL_MS >= APP_UPDATE_CHECK_INTERVAL_MS) {
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
            if (monitorTickCount <= 3 || monitorTickCount % 20 == 0) {
                MoexDiagnostics.log(applicationContext, "monitor", "tick#$monitorTickCount points=${points.size} (wait data)")
            }
            return@withContext
        }

        val signalThresholds = loadRealTradeZThresholds(applicationContext, bgSignalFallbackThresholds)
        runCatching {
            processOpenTradeRedRiskNotifications(
                context = applicationContext,
                points = points,
                entryThreshold = signalThresholds.entry,
            )
        }.onFailure { e ->
            MoexDiagnostics.logError(applicationContext, "monitor", e, "open_trade_red_risk_notify")
        }
        var dayLimit = loadDailySignalLimit(applicationContext, LocalDate.now())
        val initialPosition = loadSavedStrategyPosition(applicationContext)
        val lastProcessedBarTs = resolveLastProcessed15mBarTimestampForReplay(applicationContext)
        val (signalEdges, replayPosition) = collectZStrategy15mSignalEdgesSinceProcessedBar(
            points = points,
            lastProcessedBarTimestampMillis = lastProcessedBarTs,
            initialPosition = initialPosition,
            thresholds = signalThresholds,
        )

        if (signalEdges.isEmpty()) {
            if (shouldAdvanceLastProcessed15mBar(points, lastProcessedBarTs)) {
                saveLastProcessed15mBarTimestamp(applicationContext, points.last().timestampMillis)
            }
            if (replayPosition != initialPosition) {
                saveStrategyPosition(applicationContext, replayPosition)
            }
            if (monitorTickCount <= 3 || monitorTickCount % 20 == 0) {
                val last = points.last()
                MoexDiagnostics.log(
                    applicationContext,
                    "monitor",
                    "tick#$monitorTickCount ok bars=${points.size} Z=${String.format(Locale.US, "%.2f", last.zScore)} " +
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

        if (shouldAdvanceLastProcessed15mBar(points, lastProcessedBarTs)) {
            saveLastProcessed15mBarTimestamp(applicationContext, points.last().timestampMillis)
        }
        saveDailySignalLimit(applicationContext, dayLimit)
        saveStrategyPosition(applicationContext, currentPosition)
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
        }

        fun stop(context: Context) {
            val intent = Intent(context, SignalForegroundService::class.java).apply {
                action = ACTION_STOP_SIGNAL_MONITOR
            }
            context.startService(intent)
        }

        fun isBackgroundMonitorEnabled(context: Context): Boolean {
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
