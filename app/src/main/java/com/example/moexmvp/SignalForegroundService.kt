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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val SIGNAL_MONITOR_CHANNEL_ID = "moex_signal_monitor_channel"
internal const val SIGNAL_MONITOR_NOTIFICATION_ID = 11001
internal const val SIGNAL_MONITOR_INTERVAL_MS = 15_000L

private const val DEFAULT_DYNAMIC_Z_ENTRY_BG = 1.3
private const val DEFAULT_DYNAMIC_Z_EXIT_BG = 1.2
private const val Z_STRATEGY_MIN_TRADES_BG = 4
private const val Z_STRATEGY_ENTRY_MIN_TENTHS_BG = 8
private const val Z_STRATEGY_ENTRY_MAX_TENTHS_BG = 35
private const val Z_STRATEGY_EXIT_MIN_TENTHS_BG = 0
private const val Z_STRATEGY_EXIT_MAX_TENTHS_BG = 25
private const val MAX_SIGNAL_NOTIFICATIONS_PER_DAY = 20

class SignalForegroundService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var workerStarted = false

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
            scope.launch(Dispatchers.IO) {
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

    private suspend fun performSignalMonitorTick() {
        val app = applicationContext
        val till = LocalDate.now(moexZoneId)
        val from = till.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val points = loadPortfolio15mDataPoints(app, from, till, PortfolioM15LoadMode.INCREMENTAL)

        val portfolioTh = loadRealTradeZThresholds(
            app,
            DynamicThresholds(
                entry = DEFAULT_DYNAMIC_Z_ENTRY_BG,
                exit = DEFAULT_DYNAMIC_Z_EXIT_BG,
                calculatedDate = null
            )
        )
        val signalThresholds = BgThresholds(
            entry = portfolioTh.entry,
            exit = portfolioTh.exit,
            calculatedDate = portfolioTh.calculatedDate
        )
        val dynTh = DynamicThresholds(
            entry = signalThresholds.entry,
            exit = signalThresholds.exit,
            calculatedDate = signalThresholds.calculatedDate
        )

        var strategyPosition = loadSavedStrategyPosition(app)
        val completed = zStrategySignalOnCompleted15mBar(points, strategyPosition, dynTh) ?: return

        val dayLimit = loadDailySignalLimit(app, till)
        val edgeSignal = completed.edge
        val barTs = completed.barTimestampMillis
        val barZ = completed.zScore
        val barSpread = completed.spreadPercent

        if (!tryConsume15mStrategySignalEdge(app, barTs, edgeSignal)) return

        var nextLimit = dayLimit
        when (edgeSignal) {
            ZStrategySignal.EnterLong -> {
                strategyPosition = ZStrategyPosition.Long
                recordStrategySignalEvent(
                    context = app,
                    signalType = StrategySignalType.EnterLong,
                    zScore = barZ,
                    timestampMillis = barTs
                )
                if (dayLimit.sentCount < MAX_SIGNAL_NOTIFICATIONS_PER_DAY) {
                    val sent = showZStrategySignalPushNotification(
                        context = app,
                        title = "Вход: LONG TATN / SHORT TATNP",
                        body = String.format(
                            Locale.US,
                            "Z <= -%.1f (текущий Z=%.2f)",
                            signalThresholds.entry,
                            barZ
                        ),
                        virtualTradeTap = entryVirtualTradeTapIfManualAccept(
                            app,
                            StrategySignalType.EnterLong,
                            barZ,
                            barTs
                        )
                    )
                    if (sent) {
                        nextLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                    }
                }
                runSandboxAutoEntryIfNeeded(
                    app,
                    StrategySignalType.EnterLong,
                    barZ,
                    barTs,
                    barSpread
                )
            }

            ZStrategySignal.EnterShort -> {
                strategyPosition = ZStrategyPosition.Short
                recordStrategySignalEvent(
                    context = app,
                    signalType = StrategySignalType.EnterShort,
                    zScore = barZ,
                    timestampMillis = barTs
                )
                if (dayLimit.sentCount < MAX_SIGNAL_NOTIFICATIONS_PER_DAY) {
                    val sent = showZStrategySignalPushNotification(
                        context = app,
                        title = "Вход: LONG TATNP / SHORT TATN",
                        body = String.format(
                            Locale.US,
                            "Z >= +%.1f (текущий Z=%.2f)",
                            signalThresholds.entry,
                            barZ
                        ),
                        virtualTradeTap = entryVirtualTradeTapIfManualAccept(
                            app,
                            StrategySignalType.EnterShort,
                            barZ,
                            barTs
                        )
                    )
                    if (sent) {
                        nextLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                    }
                }
                runSandboxAutoEntryIfNeeded(
                    app,
                    StrategySignalType.EnterShort,
                    barZ,
                    barTs,
                    barSpread
                )
            }

            ZStrategySignal.ExitLong -> {
                strategyPosition = ZStrategyPosition.Flat
                recordStrategySignalEvent(
                    context = app,
                    signalType = StrategySignalType.ExitLong,
                    zScore = barZ,
                    timestampMillis = barTs
                )
                clearPendingVirtualTradeProposal(app)
                if (dayLimit.sentCount < MAX_SIGNAL_NOTIFICATIONS_PER_DAY) {
                    val sent = showZStrategySignalPushNotification(
                        context = app,
                        title = "Выход: закрыть LONG TATN / SHORT TATNP",
                        body = String.format(
                            Locale.US,
                            "Z >= -%.1f (текущий Z=%.2f)",
                            signalThresholds.exit,
                            barZ
                        )
                    )
                    if (sent) {
                        nextLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                    }
                }
            }

            ZStrategySignal.ExitShort -> {
                strategyPosition = ZStrategyPosition.Flat
                recordStrategySignalEvent(
                    context = app,
                    signalType = StrategySignalType.ExitShort,
                    zScore = barZ,
                    timestampMillis = barTs
                )
                clearPendingVirtualTradeProposal(app)
                if (dayLimit.sentCount < MAX_SIGNAL_NOTIFICATIONS_PER_DAY) {
                    val sent = showZStrategySignalPushNotification(
                        context = app,
                        title = "Выход: закрыть LONG TATNP / SHORT TATN",
                        body = String.format(
                            Locale.US,
                            "Z <= +%.1f (текущий Z=%.2f)",
                            signalThresholds.exit,
                            barZ
                        )
                    )
                    if (sent) {
                        nextLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                    }
                }
            }

            ZStrategySignal.None -> Unit
        }

        saveDailySignalLimit(app, nextLimit)
        saveStrategyPosition(app, strategyPosition)
    }

    private fun fetch15mCloseSeries(
        secId: String,
        from: LocalDate,
        till: LocalDate
    ): Map<LocalDateTime, Double> {
        val pageSize = 500
        var start = 0
        val all = mutableListOf<Pair<LocalDateTime, Double>>()
        while (true) {
            val url = buildString {
                append("https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/")
                append(secId)
                append("/candles.json?iss.meta=off&candles.columns=begin,close")
                append("&interval=1")
                append("&from=").append(from)
                append("&till=").append(till)
                append("&limit=").append(pageSize)
                append("&start=").append(start)
            }
            val response = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val rows = JSONObject(body)
                .optJSONObject("candles")
                ?.optJSONArray("data")
                ?: JSONArray()
            if (rows.length() == 0) break
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                val ts = runCatching {
                    LocalDateTime.parse(row.optString(0), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                }.getOrNull() ?: continue
                val close = when (val raw = row.opt(1)) {
                    is Number -> raw.toDouble()
                    is String -> raw.toDoubleOrNull()
                    else -> null
                } ?: continue
                all += ts to close
            }
            if (rows.length() < pageSize) break
            start += pageSize
        }
        if (all.isEmpty()) return emptyMap()
        val grouped = linkedMapOf<LocalDateTime, MutableList<Double>>()
        all.sortedBy { it.first }.forEach { (ts, close) ->
            val bucketMinute = (ts.minute / 15) * 15
            val bucket = ts.withMinute(bucketMinute).withSecond(0).withNano(0)
            grouped.getOrPut(bucket) { mutableListOf() }.add(close)
        }
        return grouped.mapValues { (_, closes) -> closes.last() }
    }

    private fun ensureDynamicThresholds(): BgThresholdUpdate {
        val saved = loadSavedThresholds()
        val now = LocalDateTime.now()
        val todayIso = now.toLocalDate().toString()
        val fallback = saved ?: BgThresholds(DEFAULT_DYNAMIC_Z_ENTRY_BG, DEFAULT_DYNAMIC_Z_EXIT_BG, null)
        if (saved?.calculatedDate == todayIso) {
            return BgThresholdUpdate(saved, recalculated = false)
        }
        if (isBeforeDynamicZRecalcWallClock(now)) {
            return BgThresholdUpdate(fallback, recalculated = false)
        }
        val best = runCatching {
            calculateBestThresholds(now.toLocalDate().minusDays(30), now.toLocalDate())
        }.getOrNull() ?: return BgThresholdUpdate(fallback, recalculated = false)
        saveThresholds(best)
        return BgThresholdUpdate(best, recalculated = true)
    }

    private fun calculateBestThresholds(from: LocalDate, till: LocalDate): BgThresholds? {
        val tatn = fetch15mCloseSeries("TATN", from, till)
        val tatnp = fetch15mCloseSeries("TATNP", from, till)
        val aligned = tatn.keys.intersect(tatnp.keys).sorted()
        if (aligned.size < 20) return null
        val spreads = aligned.mapNotNull { ts ->
            val t = tatn[ts] ?: return@mapNotNull null
            val p = tatnp[ts] ?: return@mapNotNull null
            if (p == 0.0) return@mapNotNull null
            (t / p - 1.0) * 100.0
        }
        if (spreads.size < 20) return null
        val mean = spreads.average()
        val variance = spreads.map { (it - mean) * (it - mean) }.average()
        val stdDev = kotlin.math.sqrt(variance).takeIf { it > 0.0 } ?: 1.0
        val zValues = spreads.map { (it - mean) / stdDev }

        var bestPnl = Double.NEGATIVE_INFINITY
        var bestEntry = DEFAULT_DYNAMIC_Z_ENTRY_BG
        var bestExit = DEFAULT_DYNAMIC_Z_EXIT_BG

        for (entryTenths in Z_STRATEGY_ENTRY_MIN_TENTHS_BG..Z_STRATEGY_ENTRY_MAX_TENTHS_BG) {
            val entry = entryTenths / 10.0
            for (exitTenths in Z_STRATEGY_EXIT_MIN_TENTHS_BG..Z_STRATEGY_EXIT_MAX_TENTHS_BG) {
                val exit = exitTenths / 10.0
                if (exit >= entry) continue
                var pos = BgPosition.Flat
                var entrySpread = 0.0
                var pnl = 0.0
                var trades = 0
                for (i in 1 until zValues.size) {
                    val prevZ = zValues[i - 1]
                    val curZ = zValues[i]
                    val curSpread = spreads[i]
                    when (pos) {
                        BgPosition.Long -> if (prevZ < -exit && curZ >= -exit) {
                            pnl += curSpread - entrySpread
                            pos = BgPosition.Flat
                            trades += 1
                        }
                        BgPosition.Short -> if (prevZ > exit && curZ <= exit) {
                            pnl += entrySpread - curSpread
                            pos = BgPosition.Flat
                            trades += 1
                        }
                        BgPosition.Flat -> Unit
                    }
                    if (pos == BgPosition.Flat) {
                        if (prevZ > -entry && curZ <= -entry) {
                            pos = BgPosition.Long
                            entrySpread = curSpread
                        } else if (prevZ < entry && curZ >= entry) {
                            pos = BgPosition.Short
                            entrySpread = curSpread
                        }
                    }
                }
                if (trades < Z_STRATEGY_MIN_TRADES_BG) continue
                if (pnl > bestPnl) {
                    bestPnl = pnl
                    bestEntry = entry
                    bestExit = exit
                }
            }
        }
        if (bestPnl == Double.NEGATIVE_INFINITY) return null
        return BgThresholds(bestEntry, bestExit, till.toString())
    }

    private fun loadSavedThresholds(): BgThresholds? {
        val prefs = getSharedPreferences(MONITOR_PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(PREF_DYNAMIC_Z_ENTRY) || !prefs.contains(PREF_DYNAMIC_Z_EXIT)) return null
        val entry = prefs.getFloat(PREF_DYNAMIC_Z_ENTRY, DEFAULT_DYNAMIC_Z_ENTRY_BG.toFloat()).toDouble()
        val exit = prefs.getFloat(PREF_DYNAMIC_Z_EXIT, DEFAULT_DYNAMIC_Z_EXIT_BG.toFloat()).toDouble()
        val date = prefs.getString(PREF_DYNAMIC_Z_DATE, null)
        if (entry <= 0.0 || exit < 0.0 || exit >= entry) return null
        return BgThresholds(entry, exit, date)
    }

    private fun saveThresholds(thresholds: BgThresholds) {
        getSharedPreferences(MONITOR_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(PREF_DYNAMIC_Z_ENTRY, thresholds.entry.toFloat())
            .putFloat(PREF_DYNAMIC_Z_EXIT, thresholds.exit.toFloat())
            .putString(PREF_DYNAMIC_Z_DATE, thresholds.calculatedDate)
            .apply()
    }

    companion object {
        private const val MONITOR_PREFS_NAME = "moex_alert_prefs"
        private const val PREF_BACKGROUND_SIGNAL_ENABLED = "background_signal_enabled"
        private const val PREF_DYNAMIC_Z_ENTRY = "dynamic_z_entry"
        private const val PREF_DYNAMIC_Z_EXIT = "dynamic_z_exit"
        private const val PREF_DYNAMIC_Z_DATE = "dynamic_z_date"

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

private data class BgThresholds(
    val entry: Double,
    val exit: Double,
    val calculatedDate: String?
)

private data class BgThresholdUpdate(
    val thresholds: BgThresholds,
    val recalculated: Boolean
)

private enum class BgPosition {
    Flat,
    Long,
    Short
}

