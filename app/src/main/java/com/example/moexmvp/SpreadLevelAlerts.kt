package com.example.moexmvp

import android.content.Context
import java.util.Locale
import kotlin.math.roundToInt

/** Push при пересечении спреда % этих уровней (вверх и вниз). */
internal val SPREAD_LEVEL_ALERT_THRESHOLDS_PCT = listOf(0.5, 1.0, 2.0, 2.5)

private const val SPREAD_LEVEL_ALERT_PREFS = "moex_spread_level_alerts"
private const val KEY_LAST_SPREAD = "last_spread_pct"
private const val KEY_MASTER_ENABLED = "master_enabled"
private const val KEY_DISABLED_LEVELS = "disabled_levels_csv"
internal const val SPREAD_LEVEL_ALERT_PUSH_BASE_ID = 43_000
internal const val SPREAD_LEVEL_ALERT_CORRELATION_PREFIX = "spread_lvl_"

internal enum class SpreadLevelCrossDirection { Up, Down }

internal data class SpreadLevelCross(
    val levelPct: Double,
    val direction: SpreadLevelCrossDirection,
)

internal fun spreadLevelAlertPrefKey(levelPct: Double): String =
    String.format(Locale.US, "%.1f", levelPct)

internal fun parseDisabledSpreadLevelKeysCsv(csv: String?): Set<String> {
    if (csv.isNullOrBlank()) return emptySet()
    return csv.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}

internal fun formatDisabledSpreadLevelKeysCsv(keys: Set<String>): String =
    keys.sorted().joinToString(",")

/** Настройки алертов спреда: общий выключатель и отключение отдельных уровней. */
internal object SpreadLevelAlertSettings {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SPREAD_LEVEL_ALERT_PREFS, Context.MODE_PRIVATE)

    fun isMasterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MASTER_ENABLED, true)

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    fun disabledLevelKeys(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_DISABLED_LEVELS, "").orEmpty()
        return parseDisabledSpreadLevelKeysCsv(raw)
    }

    fun isLevelEnabled(context: Context, levelPct: Double): Boolean {
        if (!isMasterEnabled(context)) return false
        return spreadLevelAlertPrefKey(levelPct) !in disabledLevelKeys(context)
    }

    fun setLevelEnabled(context: Context, levelPct: Double, enabled: Boolean) {
        val key = spreadLevelAlertPrefKey(levelPct)
        val next = disabledLevelKeys(context).toMutableSet()
        if (enabled) next.remove(key) else next.add(key)
        prefs(context).edit()
            .putString(KEY_DISABLED_LEVELS, formatDisabledSpreadLevelKeysCsv(next))
            .apply()
    }

    fun setAllLevelsEnabled(context: Context, enabled: Boolean) {
        val ed = prefs(context).edit()
        if (enabled) {
            ed.putString(KEY_DISABLED_LEVELS, "")
        } else {
            ed.putString(
                KEY_DISABLED_LEVELS,
                formatDisabledSpreadLevelKeysCsv(
                    SPREAD_LEVEL_ALERT_THRESHOLDS_PCT.map(::spreadLevelAlertPrefKey).toSet(),
                ),
            )
        }
        ed.apply()
    }

    fun levelStates(context: Context): List<Pair<Double, Boolean>> =
        SPREAD_LEVEL_ALERT_THRESHOLDS_PCT.map { level ->
            level to isLevelEnabled(context, level)
        }

    fun disableLevelFromCorrelationTag(context: Context, tag: String): Double? {
        val level = parseSpreadLevelAlertCorrelationTag(tag) ?: return null
        setLevelEnabled(context, level, enabled = false)
        return level
    }

    fun lastSpread(context: Context): Double? {
        val raw = prefs(context).getFloat(KEY_LAST_SPREAD, Float.NaN)
        return raw.takeUnless { it.isNaN() }?.toDouble()
    }

    fun setLastSpread(context: Context, spreadPercent: Double) {
        prefs(context).edit()
            .putFloat(KEY_LAST_SPREAD, spreadPercent.toFloat())
            .apply()
    }
}

internal fun parseSpreadLevelAlertCorrelationTag(tag: String?): Double? {
    val t = tag?.trim().orEmpty()
    if (!t.startsWith(SPREAD_LEVEL_ALERT_CORRELATION_PREFIX)) return null
    val rest = t.removePrefix(SPREAD_LEVEL_ALERT_CORRELATION_PREFIX)
    val levelPart = rest.substringBeforeLast('_')
    return levelPart.toDoubleOrNull()
}

internal fun isSpreadLevelAlertCorrelationTag(tag: String?): Boolean =
    tag?.startsWith(SPREAD_LEVEL_ALERT_CORRELATION_PREFIX) == true

/** Пересечения уровней между [prevSpread] и [currSpread] (в т.ч. за один тик). */
internal fun detectSpreadLevelCrosses(
    prevSpread: Double,
    currSpread: Double,
    levels: List<Double> = SPREAD_LEVEL_ALERT_THRESHOLDS_PCT,
): List<SpreadLevelCross> {
    if (!prevSpread.isFinite() || !currSpread.isFinite()) return emptyList()
    if (prevSpread == currSpread) return emptyList()
    val out = ArrayList<SpreadLevelCross>(levels.size)
    for (level in levels) {
        if (!level.isFinite()) continue
        when {
            prevSpread < level && currSpread >= level ->
                out += SpreadLevelCross(level, SpreadLevelCrossDirection.Up)
            prevSpread >= level && currSpread < level ->
                out += SpreadLevelCross(level, SpreadLevelCrossDirection.Down)
        }
    }
    return out
}

internal fun spreadLevelAlertNotificationId(levelPct: Double, direction: SpreadLevelCrossDirection): Int {
    val levelPart = (levelPct * 10.0).roundToInt().coerceIn(0, 999)
    val dirPart = if (direction == SpreadLevelCrossDirection.Up) 0 else 1
    return SPREAD_LEVEL_ALERT_PUSH_BASE_ID + levelPart * 2 + dirPart
}

internal fun spreadLevelAlertTitle(cross: SpreadLevelCross): String {
    val levelText = formatRuSignedNumber(cross.levelPct)
    return when (cross.direction) {
        SpreadLevelCrossDirection.Up -> "Спред ≥ $levelText%"
        SpreadLevelCrossDirection.Down -> "Спред < $levelText%"
    }
}

internal fun spreadLevelAlertBody(cross: SpreadLevelCross, currSpread: Double): String {
    val levelText = formatRuSignedNumber(cross.levelPct)
    val currText = formatRuSignedNumber(currSpread)
    val dirRu = when (cross.direction) {
        SpreadLevelCrossDirection.Up -> "вверх"
        SpreadLevelCrossDirection.Down -> "вниз"
    }
    return String.format(
        Locale.US,
        "Пересёк %s%% %s · сейчас %s%%",
        levelText,
        dirRu,
        currText,
    )
}

internal fun showSpreadLevelAlertPushNotification(
    context: Context,
    cross: SpreadLevelCross,
    currSpread: Double,
): Boolean {
    if (!SpreadLevelAlertSettings.isLevelEnabled(context, cross.levelPct)) return false
    val levelKey = spreadLevelAlertPrefKey(cross.levelPct)
    val dirKey = if (cross.direction == SpreadLevelCrossDirection.Up) "up" else "down"
    return showPushNotification(
        context = context,
        title = spreadLevelAlertTitle(cross),
        body = spreadLevelAlertBody(cross, currSpread),
        notificationId = spreadLevelAlertNotificationId(cross.levelPct, cross.direction),
        skipDuplicateCheck = true,
        correlationTag = "${SPREAD_LEVEL_ALERT_CORRELATION_PREFIX}${levelKey}_$dirKey",
    )
}

/**
 * Сравнить с прошлым тиком и отправить push при пересечении 0,5 / 1 / 2 / 2,5%.
 * Первый вызов после установки — только запоминает спред (без спама).
 */
internal fun maybeNotifySpreadLevelAlerts(context: Context, spreadPercent: Double?) {
    val curr = spreadPercent?.takeIf { it.isFinite() } ?: return
    val app = context.applicationContext
    if (!SpreadLevelAlertSettings.isMasterEnabled(app)) return
    val prev = SpreadLevelAlertSettings.lastSpread(app)
    if (prev == null) {
        SpreadLevelAlertSettings.setLastSpread(app, curr)
        return
    }
    val crosses = detectSpreadLevelCrosses(prev, curr)
    SpreadLevelAlertSettings.setLastSpread(app, curr)
    for (cross in crosses) {
        showSpreadLevelAlertPushNotification(app, cross, curr)
    }
}
