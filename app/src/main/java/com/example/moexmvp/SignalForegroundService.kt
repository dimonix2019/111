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

    override fun onCreate() {
        super.onCreate()
        createSignalMonitorChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SIGNAL_MONITOR) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            saveSignalMonitorEnabled(this, false)
            return START_NOT_STICKY
        }
        saveSignalMonitorEnabled(this, true)
        startForeground(SIGNAL_MONITOR_NOTIFICATION_ID, buildForegroundNotification())
        if (!workerStarted) {
            workerStarted = true
            scope.launch {
                while (isActive) {
                    runCatching { performSignalMonitorTick() }
                    delay(SIGNAL_MONITOR_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
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
        ticksSinceAppUpdateCheck++
        if (ticksSinceAppUpdateCheck * SIGNAL_MONITOR_INTERVAL_MS >= APP_UPDATE_CHECK_INTERVAL_MS) {
            ticksSinceAppUpdateCheck = 0
            runCatching { checkRemoteAppUpdateAndNotify(applicationContext) }
        }

        val points = loadPortfolio15mPointsForSignalMonitor(
            applicationContext,
            lookbackDays = marketsM15LookbackDays(Period.OneMonth),
        )
        if (points.size < 2) return@withContext

        val signalThresholds = loadRealTradeZThresholds(applicationContext, bgSignalFallbackThresholds)
        var dayLimit = loadDailySignalLimit(applicationContext, LocalDate.now())
        var currentPosition = loadSavedStrategyPosition(applicationContext)

        val edgeSignal = zStrategySignalOnLast15mBar(
            points = points,
            position = currentPosition,
            thresholds = signalThresholds
        )
        if (edgeSignal == ZStrategySignal.None) return@withContext

        val lastPt = points.last()
        val latestZScore = lastPt.zScore
        val latestSpreadPercent = lastPt.spreadPercent
        val latestTimestampMillis = lastPt.timestampMillis

        if (!tryConsume15mStrategySignalEdge(applicationContext, latestTimestampMillis, edgeSignal)) {
            return@withContext
        }

        when (edgeSignal) {
            ZStrategySignal.EnterLong -> {
                currentPosition = ZStrategyPosition.Long
                recordStrategySignalEvent(
                    context = applicationContext,
                    signalType = StrategySignalType.EnterLong,
                    zScore = latestZScore,
                    timestampMillis = latestTimestampMillis
                )
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
                currentPosition = ZStrategyPosition.Short
                recordStrategySignalEvent(
                    context = applicationContext,
                    signalType = StrategySignalType.EnterShort,
                    zScore = latestZScore,
                    timestampMillis = latestTimestampMillis
                )
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
                currentPosition = ZStrategyPosition.Flat
                recordStrategySignalEvent(
                    context = applicationContext,
                    signalType = StrategySignalType.ExitLong,
                    zScore = latestZScore,
                    timestampMillis = latestTimestampMillis
                )
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
                currentPosition = ZStrategyPosition.Flat
                recordStrategySignalEvent(
                    context = applicationContext,
                    signalType = StrategySignalType.ExitShort,
                    zScore = latestZScore,
                    timestampMillis = latestTimestampMillis
                )
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
