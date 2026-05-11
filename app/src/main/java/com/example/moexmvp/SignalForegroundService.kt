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
private const val DYNAMIC_Z_RECALC_HOUR_BG = 9
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

    private fun performSignalMonitorTick() {
        val snapshot = computeSignalSnapshot() ?: return
        val prefs = getSharedPreferences(MONITOR_PREFS_NAME, Context.MODE_PRIVATE)

        val thresholdUpdate = ensureDynamicThresholds()
        if (thresholdUpdate.recalculated) {
            showDynamicZThresholdsPushNotification(
                context = this,
                entry = thresholdUpdate.thresholds.entry,
                exit = thresholdUpdate.thresholds.exit,
                dateText = thresholdUpdate.thresholds.calculatedDate ?: LocalDate.now().toString()
            )
        }

        val dayLimit = loadDailySignalLimit(LocalDate.now())
        val prevZ = prefs.getString(PREF_BACKGROUND_LAST_Z, null)?.toDoubleOrNull()
        val currentPosition = runCatching {
            val sharedRaw = prefs.getString(PREF_SHARED_STRATEGY_POSITION, null)
            val legacyRaw = prefs.getString(PREF_BACKGROUND_POSITION_LEGACY, null)
            BgPosition.valueOf(sharedRaw ?: legacyRaw ?: BgPosition.Flat.name)
        }.getOrDefault(BgPosition.Flat)

        var nextPosition = currentPosition
        var nextLimit = dayLimit
        when (determineSignal(prevZ, snapshot.latestZ, currentPosition, thresholdUpdate.thresholds)) {
            BgSignal.EnterLong -> {
                nextPosition = BgPosition.Long
                if (dayLimit.sentCount < MAX_SIGNAL_NOTIFICATIONS_PER_DAY) {
                    val sent = showZStrategySignalPushNotification(
                        context = this,
                        title = "Вход: LONG TATN / SHORT TATNP",
                        body = String.format(
                            Locale.US,
                            "Z <= -%.1f (текущий Z=%.2f)",
                            thresholdUpdate.thresholds.entry,
                            snapshot.latestZ
                        )
                    )
                    if (sent) {
                        recordStrategySignalEvent(
                            context = this,
                            signalType = StrategySignalType.EnterLong,
                            zScore = snapshot.latestZ,
                            timestampMillis = snapshot.latestTimestampMillis
                        )
                        nextLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                    }
                }
            }

            BgSignal.EnterShort -> {
                nextPosition = BgPosition.Short
                if (dayLimit.sentCount < MAX_SIGNAL_NOTIFICATIONS_PER_DAY) {
                    val sent = showZStrategySignalPushNotification(
                        context = this,
                        title = "Вход: LONG TATNP / SHORT TATN",
                        body = String.format(
                            Locale.US,
                            "Z >= +%.1f (текущий Z=%.2f)",
                            thresholdUpdate.thresholds.entry,
                            snapshot.latestZ
                        )
                    )
                    if (sent) {
                        recordStrategySignalEvent(
                            context = this,
                            signalType = StrategySignalType.EnterShort,
                            zScore = snapshot.latestZ,
                            timestampMillis = snapshot.latestTimestampMillis
                        )
                        nextLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                    }
                }
            }

            BgSignal.ExitLong -> {
                nextPosition = BgPosition.Flat
                if (dayLimit.sentCount < MAX_SIGNAL_NOTIFICATIONS_PER_DAY) {
                    val sent = showZStrategySignalPushNotification(
                        context = this,
                        title = "Выход: закрыть LONG TATN / SHORT TATNP",
                        body = String.format(
                            Locale.US,
                            "Z >= -%.1f (текущий Z=%.2f)",
                            thresholdUpdate.thresholds.exit,
                            snapshot.latestZ
                        )
                    )
                    if (sent) {
                        recordStrategySignalEvent(
                            context = this,
                            signalType = StrategySignalType.ExitLong,
                            zScore = snapshot.latestZ,
                            timestampMillis = snapshot.latestTimestampMillis
                        )
                        nextLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                    }
                }
            }

            BgSignal.ExitShort -> {
                nextPosition = BgPosition.Flat
                if (dayLimit.sentCount < MAX_SIGNAL_NOTIFICATIONS_PER_DAY) {
                    val sent = showZStrategySignalPushNotification(
                        context = this,
                        title = "Выход: закрыть LONG TATNP / SHORT TATN",
                        body = String.format(
                            Locale.US,
                            "Z <= +%.1f (текущий Z=%.2f)",
                            thresholdUpdate.thresholds.exit,
                            snapshot.latestZ
                        )
                    )
                    if (sent) {
                        recordStrategySignalEvent(
                            context = this,
                            signalType = StrategySignalType.ExitShort,
                            zScore = snapshot.latestZ,
                            timestampMillis = snapshot.latestTimestampMillis
                        )
                        nextLimit = dayLimit.copy(sentCount = dayLimit.sentCount + 1)
                    }
                }
            }

            BgSignal.None -> Unit
        }

        saveDailySignalLimit(nextLimit)
        prefs.edit()
            .putString(PREF_BACKGROUND_LAST_Z, snapshot.latestZ.toString())
            // Keep strategy position in one shared key with MainActivity to avoid drift.
            .putString(PREF_SHARED_STRATEGY_POSITION, nextPosition.name)
            .remove(PREF_BACKGROUND_POSITION_LEGACY)
            .apply()
    }

    private fun computeSignalSnapshot(): BgSnapshot? {
        val till = LocalDate.now()
        val from = till.minusDays(1)
        val tatn = fetch15mCloseSeries("TATN", from, till)
        val tatnp = fetch15mCloseSeries("TATNP", from, till)
        val alignedTimes = tatn.keys.intersect(tatnp.keys).sorted()
        if (alignedTimes.isEmpty()) return null
        val spreadSeries = alignedTimes.mapNotNull { ts ->
            val t = tatn[ts] ?: return@mapNotNull null
            val p = tatnp[ts] ?: return@mapNotNull null
            if (p == 0.0) return@mapNotNull null
            ts to ((t / p - 1.0) * 100.0)
        }
        if (spreadSeries.isEmpty()) return null
        val spreads = spreadSeries.map { it.second }
        val mean = spreads.average()
        val variance = spreads.map { (it - mean) * (it - mean) }.average()
        val stdDev = kotlin.math.sqrt(variance).takeIf { it > 0.0 } ?: 1.0
        val latestSpread = spreads.last()
        val latestTimestampMillis = spreadSeries.last().first
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return BgSnapshot(
            latestZ = (latestSpread - mean) / stdDev,
            latestTimestampMillis = latestTimestampMillis
        )
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
        if (now.hour < DYNAMIC_Z_RECALC_HOUR_BG) {
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

    private fun determineSignal(
        previousZ: Double?,
        currentZ: Double,
        position: BgPosition,
        thresholds: BgThresholds
    ): BgSignal {
        val prev = previousZ ?: return BgSignal.None
        return when (position) {
            BgPosition.Flat -> when {
                prev > -thresholds.entry && currentZ <= -thresholds.entry -> BgSignal.EnterLong
                prev < thresholds.entry && currentZ >= thresholds.entry -> BgSignal.EnterShort
                else -> BgSignal.None
            }
            BgPosition.Long -> if (prev < -thresholds.exit && currentZ >= -thresholds.exit) {
                BgSignal.ExitLong
            } else {
                BgSignal.None
            }
            BgPosition.Short -> if (prev > thresholds.exit && currentZ <= thresholds.exit) {
                BgSignal.ExitShort
            } else {
                BgSignal.None
            }
        }
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

    private fun loadDailySignalLimit(day: LocalDate): BgDailyLimit {
        val prefs = getSharedPreferences(MONITOR_PREFS_NAME, Context.MODE_PRIVATE)
        val dayText = day.toString()
        val savedDay = prefs.getString(PREF_Z_DAILY_SIGNAL_DATE, null)
        if (savedDay != dayText) {
            return BgDailyLimit(dayText, sentCount = 0)
        }
        return BgDailyLimit(
            dayText,
            sentCount = prefs.getInt(PREF_Z_DAILY_SIGNAL_COUNT, 0)
        )
    }

    private fun saveDailySignalLimit(limit: BgDailyLimit) {
        getSharedPreferences(MONITOR_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_Z_DAILY_SIGNAL_DATE, limit.date)
            .putInt(PREF_Z_DAILY_SIGNAL_COUNT, limit.sentCount)
            .apply()
    }

    companion object {
        private const val MONITOR_PREFS_NAME = "moex_alert_prefs"
        private const val PREF_BACKGROUND_SIGNAL_ENABLED = "background_signal_enabled"
        private const val PREF_BACKGROUND_LAST_Z = "bg_last_z"
        private const val PREF_BACKGROUND_POSITION_LEGACY = "bg_position"
        private const val PREF_SHARED_STRATEGY_POSITION = "z_strategy_position"
        private const val PREF_DYNAMIC_Z_ENTRY = "dynamic_z_entry"
        private const val PREF_DYNAMIC_Z_EXIT = "dynamic_z_exit"
        private const val PREF_DYNAMIC_Z_DATE = "dynamic_z_date"
        private const val PREF_Z_DAILY_SIGNAL_DATE = "z_daily_signal_date"
        private const val PREF_Z_DAILY_SIGNAL_COUNT = "z_daily_signal_count"

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

private data class BgSnapshot(
    val latestZ: Double,
    val latestTimestampMillis: Long
)

private data class BgThresholds(
    val entry: Double,
    val exit: Double,
    val calculatedDate: String?
)

private data class BgThresholdUpdate(
    val thresholds: BgThresholds,
    val recalculated: Boolean
)

private data class BgDailyLimit(
    val date: String,
    val sentCount: Int
)

private enum class BgPosition {
    Flat,
    Long,
    Short
}

private enum class BgSignal {
    None,
    EnterLong,
    EnterShort,
    ExitLong,
    ExitShort
}
