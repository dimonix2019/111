package com.example.moexmvp

import android.content.Context
import java.util.Locale
import kotlin.math.roundToInt

/** Число настраиваемых алертов пересечения спреда %. */
internal const val SPREAD_LEVEL_ALERT_SLOT_COUNT = 4

/** Дефолты слотов 0…3 (как раньше: 0,5 / 1 / 2 / 2,5%). */
internal val SPREAD_LEVEL_ALERT_DEFAULT_THRESHOLDS_PCT =
    listOf(0.5, 1.0, 2.0, 2.5)

/** @deprecated Используйте [configuredSpreadLevelThresholds] / слоты. */
internal val SPREAD_LEVEL_ALERT_THRESHOLDS_PCT: List<Double>
    get() = SPREAD_LEVEL_ALERT_DEFAULT_THRESHOLDS_PCT

private const val SPREAD_LEVEL_ALERT_PREFS = "moex_spread_level_alerts"
private const val KEY_LAST_SPREAD = "last_spread_pct"
private const val KEY_MASTER_ENABLED = "master_enabled"
private const val KEY_DISABLED_LEVELS = "disabled_levels_csv"
private const val KEY_DISABLED_SLOTS = "disabled_slots_csv"
private const val KEY_LEVEL_PCT_PREFIX = "level_pct_"

internal const val SPREAD_LEVEL_ALERT_PUSH_BASE_ID = 43_000
internal const val SPREAD_LEVEL_ALERT_CORRELATION_PREFIX = "spread_lvl_"

internal enum class SpreadLevelCrossDirection { Up, Down }

internal data class SpreadLevelCross(
    val levelPct: Double,
    val direction: SpreadLevelCrossDirection,
)

internal data class SpreadLevelAlertSlotState(
    val slot: Int,
    val levelPct: Double,
    val enabled: Boolean,
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

internal fun parseDisabledSpreadLevelSlotsCsv(csv: String?): Set<Int> {
    if (csv.isNullOrBlank()) return emptySet()
    return csv.split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 0 until SPREAD_LEVEL_ALERT_SLOT_COUNT }
        .toSet()
}

internal fun formatDisabledSpreadLevelSlotsCsv(slots: Set<Int>): String =
    slots.filter { it in 0 until SPREAD_LEVEL_ALERT_SLOT_COUNT }.sorted().joinToString(",")

/** Разбор ввода порога: «2,5» / «2.5» → Double? */
internal fun parseSpreadLevelAlertPctInput(raw: String?): Double? {
    val s = raw?.trim()?.replace(',', '.').orEmpty()
    if (s.isEmpty()) return null
    val v = s.toDoubleOrNull() ?: return null
    if (!v.isFinite()) return null
    return v
}

/** Допустимый диапазон порога алерта спреда %. */
internal fun sanitizeSpreadLevelAlertPct(value: Double): Double? {
    if (!value.isFinite()) return null
    if (value < 0.1 || value > 30.0) return null
    return (value * 10.0).roundToInt() / 10.0
}

/** Настройки алертов спреда: общий выключатель и 4 настраиваемых уровня. */
internal object SpreadLevelAlertSettings {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SPREAD_LEVEL_ALERT_PREFS, Context.MODE_PRIVATE)

    private fun levelPctKey(slot: Int) = "$KEY_LEVEL_PCT_PREFIX$slot"

    fun isMasterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MASTER_ENABLED, true)

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    fun defaultLevelPct(slot: Int): Double =
        SPREAD_LEVEL_ALERT_DEFAULT_THRESHOLDS_PCT.getOrElse(slot) { 1.0 }

    fun levelPct(context: Context, slot: Int): Double {
        require(slot in 0 until SPREAD_LEVEL_ALERT_SLOT_COUNT)
        val raw = prefs(context).getFloat(levelPctKey(slot), Float.NaN)
        val stored = raw.takeUnless { it.isNaN() }?.toDouble()
        return sanitizeSpreadLevelAlertPct(stored ?: defaultLevelPct(slot))
            ?: defaultLevelPct(slot)
    }

    fun setLevelPct(context: Context, slot: Int, levelPct: Double): Boolean {
        require(slot in 0 until SPREAD_LEVEL_ALERT_SLOT_COUNT)
        val v = sanitizeSpreadLevelAlertPct(levelPct) ?: return false
        prefs(context).edit().putFloat(levelPctKey(slot), v.toFloat()).apply()
        return true
    }

    fun configuredLevels(context: Context): List<Double> =
        (0 until SPREAD_LEVEL_ALERT_SLOT_COUNT).map { levelPct(context, it) }

    /** Слоты с выключенным алертом (после миграции со старых ключей по значению). */
    fun disabledSlots(context: Context): Set<Int> {
        val p = prefs(context)
        if (p.contains(KEY_DISABLED_SLOTS)) {
            return parseDisabledSpreadLevelSlotsCsv(p.getString(KEY_DISABLED_SLOTS, ""))
        }
        // Миграция: disabled_levels_csv хранил «0.5,2.0» по значению дефолтов.
        val legacy = parseDisabledSpreadLevelKeysCsv(p.getString(KEY_DISABLED_LEVELS, ""))
        if (legacy.isEmpty()) return emptySet()
        val migrated = legacy.mapNotNull { key ->
            SPREAD_LEVEL_ALERT_DEFAULT_THRESHOLDS_PCT.indexOfFirst {
                spreadLevelAlertPrefKey(it) == key
            }.takeIf { it >= 0 }
        }.toSet()
        p.edit()
            .putString(KEY_DISABLED_SLOTS, formatDisabledSpreadLevelSlotsCsv(migrated))
            .apply()
        return migrated
    }

    fun isSlotEnabled(context: Context, slot: Int): Boolean {
        if (slot !in 0 until SPREAD_LEVEL_ALERT_SLOT_COUNT) return false
        if (!isMasterEnabled(context)) return false
        return slot !in disabledSlots(context)
    }

    fun setSlotEnabled(context: Context, slot: Int, enabled: Boolean) {
        require(slot in 0 until SPREAD_LEVEL_ALERT_SLOT_COUNT)
        val next = disabledSlots(context).toMutableSet()
        if (enabled) next.remove(slot) else next.add(slot)
        prefs(context).edit()
            .putString(KEY_DISABLED_SLOTS, formatDisabledSpreadLevelSlotsCsv(next))
            .apply()
    }

    /** Совместимость: включить/выкл по текущему значению порога (первый совпавший слот). */
    fun isLevelEnabled(context: Context, levelPct: Double): Boolean {
        if (!isMasterEnabled(context)) return false
        val key = spreadLevelAlertPrefKey(levelPct)
        val slot = configuredLevels(context).indexOfFirst { spreadLevelAlertPrefKey(it) == key }
        if (slot < 0) return true
        return isSlotEnabled(context, slot)
    }

    fun setLevelEnabled(context: Context, levelPct: Double, enabled: Boolean) {
        val key = spreadLevelAlertPrefKey(levelPct)
        val slot = configuredLevels(context).indexOfFirst { spreadLevelAlertPrefKey(it) == key }
        if (slot >= 0) setSlotEnabled(context, slot, enabled)
    }

    fun setAllLevelsEnabled(context: Context, enabled: Boolean) {
        val ed = prefs(context).edit()
        if (enabled) {
            ed.putString(KEY_DISABLED_SLOTS, "")
        } else {
            ed.putString(
                KEY_DISABLED_SLOTS,
                formatDisabledSpreadLevelSlotsCsv((0 until SPREAD_LEVEL_ALERT_SLOT_COUNT).toSet()),
            )
        }
        ed.apply()
    }

    fun levelStates(context: Context): List<SpreadLevelAlertSlotState> =
        (0 until SPREAD_LEVEL_ALERT_SLOT_COUNT).map { slot ->
            SpreadLevelAlertSlotState(
                slot = slot,
                levelPct = levelPct(context, slot),
                enabled = isSlotEnabled(context, slot),
            )
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
    levels: List<Double> = SPREAD_LEVEL_ALERT_DEFAULT_THRESHOLDS_PCT,
): List<SpreadLevelCross> {
    if (!prevSpread.isFinite() || !currSpread.isFinite()) return emptyList()
    if (prevSpread == currSpread) return emptyList()
    val out = ArrayList<SpreadLevelCross>(levels.size)
    for (level in levels.distinct()) {
        if (!level.isFinite()) continue
        when {
            prevSpread < level && currSpread >= level ->
                out += SpreadLevelCross(level, SpreadLevelCrossDirection.Up)
            prevSpread >= level && currSpread < level ->
                out += SpreadLevelCross(level, SpreadLevelCrossDirection.Down)
        }
    }
    return out.sortedBy { it.levelPct }
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
 * Сравнить с прошлым тиком и отправить push при пересечении настроенных уровней.
 * Первый вызов после установки — только запоминает спред (без спама).
 */
internal fun maybeNotifySpreadLevelAlerts(context: Context, spreadPercent: Double?) {
    val curr = spreadPercent?.takeIf { it.isFinite() } ?: return
    val app = context.applicationContext
    val prev = SpreadLevelAlertSettings.lastSpread(app)
    if (prev == null) {
        SpreadLevelAlertSettings.setLastSpread(app, curr)
        return
    }
    // Алерты «Вход» / «Добор и экстра» до обновления lastSpread (тот же prev→curr тик).
    val entryCrosses = detectEntryLevelCrosses(
        prevSpread = prev,
        currSpread = curr,
        longEnterPct = EntryLevelAlertSettings.longEnterPct(app),
        shortEnterPct = EntryLevelAlertSettings.shortEnterPct(app),
    )
    for (side in entryCrosses) {
        showEntryLevelAlertPushNotification(app, side, curr)
    }
    val addonCrosses = detectAddonExtraLevelCrosses(
        prevSpread = prev,
        currSpread = curr,
        kind = AddonExtraKind.Addon,
        longEnterPct = AddonExtraLevelAlertSettings.enterPct(app, AddonExtraAlertSlot.AddonLong),
        shortEnterPct = AddonExtraLevelAlertSettings.enterPct(app, AddonExtraAlertSlot.AddonShort),
    )
    for (slot in addonCrosses) {
        showAddonExtraLevelAlertPushNotification(app, slot, curr)
    }
    val extraCrosses = detectAddonExtraLevelCrosses(
        prevSpread = prev,
        currSpread = curr,
        kind = AddonExtraKind.Extra,
        longEnterPct = AddonExtraLevelAlertSettings.enterPct(app, AddonExtraAlertSlot.ExtraLong),
        shortEnterPct = AddonExtraLevelAlertSettings.enterPct(app, AddonExtraAlertSlot.ExtraShort),
    )
    for (slot in extraCrosses) {
        showAddonExtraLevelAlertPushNotification(app, slot, curr)
    }
    SpreadLevelAlertSettings.setLastSpread(app, curr)
    if (!SpreadLevelAlertSettings.isMasterEnabled(app)) return
    val levels = SpreadLevelAlertSettings.configuredLevels(app)
    val crosses = detectSpreadLevelCrosses(prev, curr, levels)
    for (cross in crosses) {
        showSpreadLevelAlertPushNotification(app, cross, curr)
    }
}
