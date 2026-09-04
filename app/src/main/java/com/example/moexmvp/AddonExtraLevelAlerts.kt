package com.example.moexmvp

import android.content.Context
import java.util.Locale

/** Пороги добора (как на столе Prod: Long узкий 2.0, Short широкий 7.0). */
internal const val DEFAULT_ADDON_ENTER_NARROW = 2.0
internal const val DEFAULT_ADDON_ENTER_WIDE = 7.0

/** Пороги экстра (Prod: Long ≤1.0, Short ≥9.0). */
internal const val DEFAULT_EXTRA_ENTER_NARROW = 1.0
internal const val DEFAULT_EXTRA_ENTER_WIDE = 9.0

private const val ADDON_EXTRA_ALERT_PREFS = "moex_addon_extra_level_alerts"

internal const val ADDON_EXTRA_LEVEL_ALERT_PUSH_BASE_ID = 43_300
internal const val ADDON_EXTRA_LEVEL_ALERT_CORRELATION_PREFIX = "addon_extra_lvl_"

/** Нога: добор или экстра. */
internal enum class AddonExtraKind {
    Addon,
    Extra,
}

/** Слот алерта: нога × сторона. */
internal enum class AddonExtraAlertSlot {
    AddonLong,
    AddonShort,
    ExtraLong,
    ExtraShort,
}

internal fun AddonExtraAlertSlot.kind(): AddonExtraKind =
    when (this) {
        AddonExtraAlertSlot.AddonLong, AddonExtraAlertSlot.AddonShort -> AddonExtraKind.Addon
        AddonExtraAlertSlot.ExtraLong, AddonExtraAlertSlot.ExtraShort -> AddonExtraKind.Extra
    }

internal fun AddonExtraAlertSlot.side(): EntryAlertSide =
    when (this) {
        AddonExtraAlertSlot.AddonLong, AddonExtraAlertSlot.ExtraLong -> EntryAlertSide.Long
        AddonExtraAlertSlot.AddonShort, AddonExtraAlertSlot.ExtraShort -> EntryAlertSide.Short
    }

internal fun addonExtraAlertSlot(kind: AddonExtraKind, side: EntryAlertSide): AddonExtraAlertSlot =
    when (kind) {
        AddonExtraKind.Addon -> when (side) {
            EntryAlertSide.Long -> AddonExtraAlertSlot.AddonLong
            EntryAlertSide.Short -> AddonExtraAlertSlot.AddonShort
        }
        AddonExtraKind.Extra -> when (side) {
            EntryAlertSide.Long -> AddonExtraAlertSlot.ExtraLong
            EntryAlertSide.Short -> AddonExtraAlertSlot.ExtraShort
        }
    }

/** Настройки алертов группы «Добор и экстра». */
internal object AddonExtraLevelAlertSettings {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(ADDON_EXTRA_ALERT_PREFS, Context.MODE_PRIVATE)

    private fun enabledKey(slot: AddonExtraAlertSlot) = "${slot.name.lowercase(Locale.US)}_enabled"
    private fun tradeAtKey(slot: AddonExtraAlertSlot) = "${slot.name.lowercase(Locale.US)}_trade_at_ms"
    private fun cachedPctKey(slot: AddonExtraAlertSlot) = "${slot.name.lowercase(Locale.US)}_enter_pct"

    fun isEnabled(context: Context, slot: AddonExtraAlertSlot): Boolean =
        prefs(context).getBoolean(enabledKey(slot), true)

    fun setEnabled(context: Context, slot: AddonExtraAlertSlot, enabled: Boolean) {
        val ed = prefs(context).edit()
        ed.putBoolean(enabledKey(slot), enabled)
        if (enabled) ed.putLong(tradeAtKey(slot), 0L)
        ed.apply()
    }

    fun tradeOpenedAtMillis(context: Context, slot: AddonExtraAlertSlot): Long? {
        val raw = prefs(context).getLong(tradeAtKey(slot), 0L)
        return raw.takeIf { it > 0L }
    }

    fun markTradeOpened(
        context: Context,
        slot: AddonExtraAlertSlot,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        prefs(context).edit()
            .putBoolean(enabledKey(slot), false)
            .putLong(tradeAtKey(slot), atMillis)
            .apply()
    }

    fun defaultEnterPct(slot: AddonExtraAlertSlot): Double =
        when (slot) {
            AddonExtraAlertSlot.AddonLong -> DEFAULT_ADDON_ENTER_NARROW
            AddonExtraAlertSlot.AddonShort -> DEFAULT_ADDON_ENTER_WIDE
            AddonExtraAlertSlot.ExtraLong -> DEFAULT_EXTRA_ENTER_NARROW
            AddonExtraAlertSlot.ExtraShort -> DEFAULT_EXTRA_ENTER_WIDE
        }

    fun enterPct(context: Context, slot: AddonExtraAlertSlot): Double {
        val raw = prefs(context).getFloat(cachedPctKey(slot), Float.NaN)
        return raw.takeUnless { it.isNaN() }?.toDouble() ?: defaultEnterPct(slot)
    }

    fun cacheEnterLevels(
        context: Context,
        addonLong: Double? = null,
        addonShort: Double? = null,
        extraLong: Double? = null,
        extraShort: Double? = null,
    ) {
        val ed = prefs(context).edit()
        fun put(slot: AddonExtraAlertSlot, value: Double?) {
            if (value != null && value.isFinite()) {
                ed.putFloat(cachedPctKey(slot), value.toFloat())
            }
        }
        put(AddonExtraAlertSlot.AddonLong, addonLong)
        put(AddonExtraAlertSlot.AddonShort, addonShort)
        put(AddonExtraAlertSlot.ExtraLong, extraLong)
        put(AddonExtraAlertSlot.ExtraShort, extraShort)
        ed.apply()
    }
}

internal fun addonExtraAlertNotificationId(slot: AddonExtraAlertSlot): Int =
    ADDON_EXTRA_LEVEL_ALERT_PUSH_BASE_ID + when (slot) {
        AddonExtraAlertSlot.AddonLong -> 0
        AddonExtraAlertSlot.AddonShort -> 1
        AddonExtraAlertSlot.ExtraLong -> 2
        AddonExtraAlertSlot.ExtraShort -> 3
    }

internal fun addonExtraKindLabelRu(kind: AddonExtraKind): String =
    when (kind) {
        AddonExtraKind.Addon -> "Добор"
        AddonExtraKind.Extra -> "Экстра"
    }

internal fun addonExtraLevelAlertTitle(slot: AddonExtraAlertSlot, levelPct: Double): String {
    val kindRu = addonExtraKindLabelRu(slot.kind())
    val levelText = formatRuSignedNumber(levelPct)
    return when (slot.side()) {
        EntryAlertSide.Long -> "$kindRu Long ≤ $levelText%"
        EntryAlertSide.Short -> "$kindRu Short ≥ $levelText%"
    }
}

internal fun addonExtraLevelAlertBody(
    slot: AddonExtraAlertSlot,
    currSpread: Double,
    levelPct: Double,
): String {
    val kindRu = addonExtraKindLabelRu(slot.kind())
    val levelText = formatRuSignedNumber(levelPct)
    val currText = formatRuSignedNumber(currSpread)
    val sideRu = when (slot.side()) {
        EntryAlertSide.Long -> "Long"
        EntryAlertSide.Short -> "Short"
    }
    return String.format(
        Locale.US,
        "%s %s · порог %s%% · сейчас %s%%",
        kindRu,
        sideRu,
        levelText,
        currText,
    )
}

/**
 * Касания порогов добора/экстра на том же тике, что и «Вход»
 * (Long вниз через узкий, Short вверх через широкий).
 */
internal fun detectAddonExtraLevelCrosses(
    prevSpread: Double,
    currSpread: Double,
    kind: AddonExtraKind,
    longEnterPct: Double,
    shortEnterPct: Double,
): List<AddonExtraAlertSlot> {
    val sides = detectEntryLevelCrosses(prevSpread, currSpread, longEnterPct, shortEnterPct)
    return sides.map { addonExtraAlertSlot(kind, it) }
}

internal fun showAddonExtraLevelAlertPushNotification(
    context: Context,
    slot: AddonExtraAlertSlot,
    currSpread: Double,
): Boolean {
    if (!AddonExtraLevelAlertSettings.isEnabled(context, slot)) return false
    val levelPct = AddonExtraLevelAlertSettings.enterPct(context, slot)
    val slotKey = slot.name.lowercase(Locale.US)
    val levelKey = String.format(Locale.US, "%.1f", levelPct)
    return showPushNotification(
        context = context,
        title = addonExtraLevelAlertTitle(slot, levelPct),
        body = addonExtraLevelAlertBody(slot, currSpread, levelPct),
        notificationId = addonExtraAlertNotificationId(slot),
        skipDuplicateCheck = true,
        correlationTag = "${ADDON_EXTRA_LEVEL_ALERT_CORRELATION_PREFIX}${slotKey}_$levelKey",
    )
}

internal fun formatAddonExtraAlertTradeLabel(atMillis: Long): String =
    formatEntryAlertTradeLabel(atMillis)

/**
 * Разбор события стола: фактическое открытие добора/экстра (не сигнал и не выход).
 * Примеры: `AUTO_ADDON добор LONG · …`, `AUTO добор LONG`, `AUTO_EXTRA экстра SHORT · …`.
 */
internal fun parseAddonExtraOpenFromDeskMessage(message: String?): AddonExtraAlertSlot? {
    val m = message?.trim().orEmpty()
    if (m.isEmpty()) return null
    val lower = m.lowercase(Locale.ROOT)
    if (lower.contains("fail") || lower.contains("выход") || lower.contains("сигнал")) return null
    val kind = when {
        lower.contains("auto_addon") || lower.contains("auto добор") -> AddonExtraKind.Addon
        lower.contains("auto_extra") || lower.contains("auto экстра") -> AddonExtraKind.Extra
        else -> return null
    }
    val side = when {
        m.contains("LONG", ignoreCase = true) -> EntryAlertSide.Long
        m.contains("SHORT", ignoreCase = true) -> EntryAlertSide.Short
        else -> return null
    }
    return addonExtraAlertSlot(kind, side)
}

internal fun markAddonExtraAlertTradeOpenedFromDeskMessage(
    context: Context,
    message: String?,
    atMillis: Long = System.currentTimeMillis(),
) {
    val slot = parseAddonExtraOpenFromDeskMessage(message) ?: return
    AddonExtraLevelAlertSettings.markTradeOpened(context, slot, atMillis)
}
