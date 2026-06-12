package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.sqrt

internal fun isBeforeDynamicZRecalcWallClock(now: LocalDateTime): Boolean {
    val trigger = LocalTime.of(DYNAMIC_Z_RECALC_HOUR, DYNAMIC_Z_RECALC_MINUTE)
    return now.toLocalTime().isBefore(trigger)
}

internal fun portfolioChartZThresholds(
    realTradeEntry: Double?,
    realTradeExit: Double?,
    fallback: DynamicThresholds = DynamicThresholds(
        entry = DEFAULT_DYNAMIC_Z_ENTRY,
        exit = DEFAULT_DYNAMIC_Z_EXIT,
        calculatedDate = null
    )
): DynamicThresholds {
    val entry = (realTradeEntry ?: fallback.entry)
        .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    val exit = (realTradeExit ?: fallback.exit)
        .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    return DynamicThresholds(entry = entry, exit = exit, calculatedDate = null)
}

internal fun ensureDynamicThresholds(context: Context): DynamicThresholdUpdate {
    val fallback = DynamicThresholds(
        entry = DEFAULT_DYNAMIC_Z_ENTRY,
        exit = DEFAULT_DYNAMIC_Z_EXIT,
        calculatedDate = null
    )
    if (!DYNAMIC_Z_DAILY_RECALC_ENABLED) {
        return DynamicThresholdUpdate(
            thresholds = loadRealTradeZThresholds(context, fallback),
            recalculated = false
        )
    }
    val saved = loadSavedDynamicThresholds(context)
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    val todayIso = today.toString()
    val savedOrFallback = saved ?: fallback
    if (saved?.calculatedDate == todayIso) {
        return DynamicThresholdUpdate(thresholds = saved, recalculated = false)
    }
    if (isBeforeDynamicZRecalcWallClock(now)) {
        return DynamicThresholdUpdate(thresholds = savedOrFallback, recalculated = false)
    }
    val calculated = runCatching {
        calculateBestDynamicThresholds(
            from = today.minusDays(30),
            till = today
        )
    }.getOrNull() ?: return DynamicThresholdUpdate(thresholds = savedOrFallback, recalculated = false)
    saveDynamicThresholds(context, calculated)
    return DynamicThresholdUpdate(thresholds = calculated, recalculated = true)
}

internal fun loadSavedDynamicThresholds(context: Context): DynamicThresholds? {
    val prefs = context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.contains(PREF_DYNAMIC_Z_ENTRY) || !prefs.contains(PREF_DYNAMIC_Z_EXIT)) {
        return null
    }
    val entry = prefs.getFloat(PREF_DYNAMIC_Z_ENTRY, DEFAULT_DYNAMIC_Z_ENTRY.toFloat()).toDouble()
    val exit = prefs.getFloat(PREF_DYNAMIC_Z_EXIT, DEFAULT_DYNAMIC_Z_EXIT.toFloat()).toDouble()
    val date = prefs.getString(PREF_DYNAMIC_Z_DATE, null)
    if (entry <= 0.0 || exit < 0.0 || exit >= entry) {
        return null
    }
    return DynamicThresholds(
        entry = entry,
        exit = exit,
        calculatedDate = date
    )
}

internal fun saveDynamicThresholds(context: Context, thresholds: DynamicThresholds) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_DYNAMIC_Z_ENTRY, thresholds.entry.toFloat())
        .putFloat(PREF_DYNAMIC_Z_EXIT, thresholds.exit.toFloat())
        .putString(PREF_DYNAMIC_Z_DATE, thresholds.calculatedDate)
        .apply()
}

internal fun loadSavedStrategyPosition(context: Context): ZStrategyPosition {
    val raw = context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PREF_Z_STRATEGY_POSITION, ZStrategyPosition.Flat.name)
    return runCatching { ZStrategyPosition.valueOf(raw ?: ZStrategyPosition.Flat.name) }
        .getOrDefault(ZStrategyPosition.Flat)
}

internal fun saveStrategyPosition(context: Context, position: ZStrategyPosition) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_Z_STRATEGY_POSITION, position.name)
        .apply()
}

/** Пороги для push/фона и песочницы: из розовых степперов «Портфель» или миграция из старых ключей/dynamic fallback. */
internal fun loadRealTradeZThresholds(context: Context, fallback: DynamicThresholds): DynamicThresholds {
    val prefs = context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
    fun readPair(): Pair<Float, Float>? {
        val eKey = when {
            prefs.contains(PREF_REAL_TRADE_Z_ENTRY) -> PREF_REAL_TRADE_Z_ENTRY
            prefs.contains(PREF_PORTFOLIO_Z_ENTRY_THRESHOLD) -> PREF_PORTFOLIO_Z_ENTRY_THRESHOLD
            else -> null
        }
        val xKey = when {
            prefs.contains(PREF_REAL_TRADE_Z_EXIT) -> PREF_REAL_TRADE_Z_EXIT
            prefs.contains(PREF_PORTFOLIO_Z_EXIT_THRESHOLD) -> PREF_PORTFOLIO_Z_EXIT_THRESHOLD
            else -> null
        }
        if (eKey == null || xKey == null) return null
        return prefs.getFloat(eKey, fallback.entry.toFloat()) to prefs.getFloat(xKey, fallback.exit.toFloat())
    }
    val pair = readPair() ?: return fallback
    val entry = pair.first.toDouble().coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    val exit = pair.second.toDouble().coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    if (entry <= 0.0 || exit < 0.0 || exit >= entry) return fallback
    return DynamicThresholds(
        entry = entry,
        exit = exit,
        calculatedDate = fallback.calculatedDate
    )
}

internal fun saveRealTradeZThresholds(context: Context, entry: Double, exit: Double) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_REAL_TRADE_Z_ENTRY, entry.toFloat())
        .putFloat(PREF_REAL_TRADE_Z_EXIT, exit.toFloat())
        .apply()
}

/** Независимые пороги симуляции «Тест страт.». Если ещё не заданы — те же значения, что у рыночных (миграция). */
internal fun loadStrategyTestZThresholds(context: Context, fallback: DynamicThresholds): DynamicThresholds {
    val prefs = context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
    if (!prefs.contains(PREF_STRATEGY_TEST_Z_ENTRY) ||
        !prefs.contains(PREF_STRATEGY_TEST_Z_EXIT)
    ) {
        return loadRealTradeZThresholds(context, fallback)
    }
    val entry = prefs.getFloat(PREF_STRATEGY_TEST_Z_ENTRY, fallback.entry.toFloat())
        .toDouble()
        .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    val exit = prefs.getFloat(PREF_STRATEGY_TEST_Z_EXIT, fallback.exit.toFloat())
        .toDouble()
        .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    if (entry <= 0.0 || exit < 0.0 || exit >= entry) return loadRealTradeZThresholds(context, fallback)
    return DynamicThresholds(
        entry = entry,
        exit = exit,
        calculatedDate = fallback.calculatedDate
    )
}

internal fun saveStrategyTestZThresholds(context: Context, entry: Double, exit: Double) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_STRATEGY_TEST_Z_ENTRY, entry.toFloat())
        .putFloat(PREF_STRATEGY_TEST_Z_EXIT, exit.toFloat())
        .apply()
}

internal fun loadStrategyTestExitMode(context: Context): ZStrategyExitMode {
    val prefs = context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
    return parseZStrategyExitMode(prefs.getString(PREF_STRATEGY_TEST_EXIT_MODE, null))
}

internal fun loadStrategyTestZPeakTrailZ(context: Context): Double {
    val prefs = context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getFloat(
        PREF_STRATEGY_TEST_Z_PEAK_TRAIL,
        DEFAULT_STRATEGY_TEST_Z_PEAK_TRAIL.toFloat()
    ).toDouble().coerceIn(STRATEGY_TEST_Z_PEAK_TRAIL_MIN, STRATEGY_TEST_Z_PEAK_TRAIL_MAX)
}

internal fun saveStrategyTestExitConfig(
    context: Context,
    exitMode: ZStrategyExitMode,
    zPeakTrailZ: Double
) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_STRATEGY_TEST_EXIT_MODE, exitMode.name)
        .putFloat(
            PREF_STRATEGY_TEST_Z_PEAK_TRAIL,
            zPeakTrailZ.coerceIn(
                STRATEGY_TEST_Z_PEAK_TRAIL_MIN,
                STRATEGY_TEST_Z_PEAK_TRAIL_MAX
            ).toFloat()
        )
        .apply()
}

/** Сигнал на последнем 15м баре (пересечение prev→last), те же правила что [buildZStrategyPortfolioMetrics]. */
internal fun zStrategySignalOnLast15mBar(
    points: List<DataPoint>,
    position: ZStrategyPosition,
    thresholds: DynamicThresholds
): ZStrategySignal {
    if (points.size < 2) return ZStrategySignal.None
    val prev = points[points.size - 2]
    val current = points.last()
    return determineZStrategySignalBetweenBars(prev, current, position, thresholds)
}

private val consumed15mSignalEdgeLock = Any()

internal fun loadLastProcessed15mBarTimestamp(context: Context): Long? {
    val raw = context.applicationContext.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .getLong(PREF_LAST_PROCESSED_15M_BAR_UNIX, 0L)
    return raw.takeIf { it > 0L }
}

internal fun saveLastProcessed15mBarTimestamp(context: Context, barTimestampMillis: Long) {
    context.applicationContext.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putLong(PREF_LAST_PROCESSED_15M_BAR_UNIX, barTimestampMillis)
        .apply()
}

/**
 * При первом запуске после обновления — догон с последнего бара в журнале сигналов,
 * чтобы не пропустить пересечение внутри пакетной загрузки MOEX.
 */
internal fun resolveLastProcessed15mBarTimestampForReplay(context: Context): Long? {
    loadLastProcessed15mBarTimestamp(context)?.let { return it }
    return loadStrategySignalEvents(context)
        .maxOfOrNull { it.timestampMillis }
        ?.takeIf { it > 0L }
}

internal fun is15mStrategySignalEdgeConsumed(
    context: Context,
    barTimestampMillis: Long,
    signal: ZStrategySignal,
): Boolean {
    if (signal == ZStrategySignal.None) return true
    val edgeKey = "$barTimestampMillis|${signal.name}"
    synchronized(consumed15mSignalEdgeLock) {
        val prefs = context.applicationContext.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_LAST_CONSUMED_15M_SIGNAL_EDGE, null) == edgeKey
    }
}

internal fun mark15mStrategySignalEdgeConsumed(
    context: Context,
    barTimestampMillis: Long,
    signal: ZStrategySignal,
) {
    if (signal == ZStrategySignal.None) return
    val edgeKey = "$barTimestampMillis|${signal.name}"
    synchronized(consumed15mSignalEdgeLock) {
        context.applicationContext.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_CONSUMED_15M_SIGNAL_EDGE, edgeKey)
            .commit()
    }
}

/**
 * Не обрабатывать один и тот же пересечение на одном 15м баре повторно
 * (опрос каждые 5–15 с, UI + фон, сброс позиции из симуляции).
 */
internal fun tryConsume15mStrategySignalEdge(
    context: Context,
    barTimestampMillis: Long,
    signal: ZStrategySignal
): Boolean {
    if (signal == ZStrategySignal.None) return false
    if (is15mStrategySignalEdgeConsumed(context, barTimestampMillis, signal)) {
        return false
    }
    mark15mStrategySignalEdgeConsumed(context, barTimestampMillis, signal)
    return true
}

internal fun clearConsumed15mStrategySignalEdge(context: Context) {
    synchronized(consumed15mSignalEdgeLock) {
        context.applicationContext.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_LAST_CONSUMED_15M_SIGNAL_EDGE)
            .remove(PREF_LAST_PROCESSED_15M_BAR_UNIX)
            .apply()
    }
}

internal fun loadDailySignalLimit(context: Context, day: LocalDate): DailySignalLimit {
    val prefs = context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
    val savedDay = prefs.getString(PREF_Z_DAILY_SIGNAL_DATE, null)
    val dayText = day.toString()
    if (savedDay != dayText) {
        return DailySignalLimit(date = dayText, sentCount = 0)
    }
    val legacyCount = listOf(
        prefs.getBoolean(PREF_Z_DAILY_SIGNAL_ENTRY, false),
        prefs.getBoolean(PREF_Z_DAILY_SIGNAL_EXIT, false)
    ).count { it }
    return DailySignalLimit(
        date = dayText,
        sentCount = prefs.getInt(PREF_Z_DAILY_SIGNAL_COUNT, legacyCount)
    )
}

internal fun saveDailySignalLimit(context: Context, limit: DailySignalLimit) {
    context.getSharedPreferences(ALERT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_Z_DAILY_SIGNAL_DATE, limit.date)
        .putInt(PREF_Z_DAILY_SIGNAL_COUNT, limit.sentCount)
        .remove(PREF_Z_DAILY_SIGNAL_ENTRY)
        .remove(PREF_Z_DAILY_SIGNAL_EXIT)
        .apply()
}

