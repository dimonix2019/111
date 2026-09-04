package com.example.moexmvp

import android.content.Context
import java.util.Locale

/** Пороги входа спреда (как на рабочем столе: Long узкий, Short широкий). */
internal const val DEFAULT_SPREAD_ENTER_NARROW = 3.2
internal const val DEFAULT_SPREAD_ENTER_WIDE = 6.1

private const val ENTRY_ALERT_PREFS = "moex_entry_level_alerts"
private const val KEY_LONG_ENABLED = "long_enabled"
private const val KEY_SHORT_ENABLED = "short_enabled"
private const val KEY_LONG_TRADE_AT_MS = "long_trade_at_ms"
private const val KEY_SHORT_TRADE_AT_MS = "short_trade_at_ms"
private const val KEY_CACHED_LONG_PCT = "cached_long_enter_pct"
private const val KEY_CACHED_SHORT_PCT = "cached_short_enter_pct"

internal const val ENTRY_LEVEL_ALERT_PUSH_BASE_ID = 43_200
internal const val ENTRY_LEVEL_ALERT_CORRELATION_PREFIX = "entry_lvl_"

internal enum class EntryAlertSide {
    Long,
    Short,
}

/** Настройки алертов группы «Вход»: Long/Short по текущим порогам стратегии. */
internal object EntryLevelAlertSettings {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(ENTRY_ALERT_PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, side: EntryAlertSide): Boolean =
        when (side) {
            EntryAlertSide.Long -> prefs(context).getBoolean(KEY_LONG_ENABLED, true)
            EntryAlertSide.Short -> prefs(context).getBoolean(KEY_SHORT_ENABLED, true)
        }

    fun setEnabled(context: Context, side: EntryAlertSide, enabled: Boolean) {
        val ed = prefs(context).edit()
        when (side) {
            EntryAlertSide.Long -> {
                ed.putBoolean(KEY_LONG_ENABLED, enabled)
                if (enabled) ed.putLong(KEY_LONG_TRADE_AT_MS, 0L)
            }
            EntryAlertSide.Short -> {
                ed.putBoolean(KEY_SHORT_ENABLED, enabled)
                if (enabled) ed.putLong(KEY_SHORT_TRADE_AT_MS, 0L)
            }
        }
        ed.apply()
    }

    fun tradeOpenedAtMillis(context: Context, side: EntryAlertSide): Long? {
        val raw = when (side) {
            EntryAlertSide.Long -> prefs(context).getLong(KEY_LONG_TRADE_AT_MS, 0L)
            EntryAlertSide.Short -> prefs(context).getLong(KEY_SHORT_TRADE_AT_MS, 0L)
        }
        return raw.takeIf { it > 0L }
    }

    /** Сделка открыта по стороне: выкл алерт и запомнить время. */
    fun markTradeOpened(context: Context, side: EntryAlertSide, atMillis: Long = System.currentTimeMillis()) {
        val ed = prefs(context).edit()
        when (side) {
            EntryAlertSide.Long -> {
                ed.putBoolean(KEY_LONG_ENABLED, false)
                ed.putLong(KEY_LONG_TRADE_AT_MS, atMillis)
            }
            EntryAlertSide.Short -> {
                ed.putBoolean(KEY_SHORT_ENABLED, false)
                ed.putLong(KEY_SHORT_TRADE_AT_MS, atMillis)
            }
        }
        ed.apply()
    }

    fun longEnterPct(context: Context): Double {
        val raw = prefs(context).getFloat(KEY_CACHED_LONG_PCT, Float.NaN)
        return raw.takeUnless { it.isNaN() }?.toDouble() ?: DEFAULT_SPREAD_ENTER_NARROW
    }

    fun shortEnterPct(context: Context): Double {
        val raw = prefs(context).getFloat(KEY_CACHED_SHORT_PCT, Float.NaN)
        return raw.takeUnless { it.isNaN() }?.toDouble() ?: DEFAULT_SPREAD_ENTER_WIDE
    }

    fun cacheEnterLevels(context: Context, longEnterPct: Double?, shortEnterPct: Double?) {
        val ed = prefs(context).edit()
        if (longEnterPct != null && longEnterPct.isFinite()) {
            ed.putFloat(KEY_CACHED_LONG_PCT, longEnterPct.toFloat())
        }
        if (shortEnterPct != null && shortEnterPct.isFinite()) {
            ed.putFloat(KEY_CACHED_SHORT_PCT, shortEnterPct.toFloat())
        }
        ed.apply()
    }

    fun enterPct(context: Context, side: EntryAlertSide): Double =
        when (side) {
            EntryAlertSide.Long -> longEnterPct(context)
            EntryAlertSide.Short -> shortEnterPct(context)
        }
}

internal fun entryAlertSideFromPosition(side: ZStrategyPosition): EntryAlertSide? =
    when (side) {
        ZStrategyPosition.Long -> EntryAlertSide.Long
        ZStrategyPosition.Short -> EntryAlertSide.Short
        ZStrategyPosition.Flat -> null
    }

internal fun entryAlertSideFromDirectionLabel(direction: String?): EntryAlertSide? {
    val d = direction?.trim()?.uppercase(Locale.US).orEmpty()
    return when {
        d.contains("LONG") -> EntryAlertSide.Long
        d.contains("SHORT") -> EntryAlertSide.Short
        else -> null
    }
}

/** Отключить алерт «Вход» после открытия пары (брокер / стол). */
internal fun markEntryAlertTradeOpened(
    context: Context,
    side: ZStrategyPosition,
    atMillis: Long = System.currentTimeMillis(),
) {
    val alertSide = entryAlertSideFromPosition(side) ?: return
    EntryLevelAlertSettings.markTradeOpened(context, alertSide, atMillis)
}

internal fun markEntryAlertTradeOpenedFromDirection(
    context: Context,
    direction: String?,
    atMillis: Long = System.currentTimeMillis(),
) {
    val alertSide = entryAlertSideFromDirectionLabel(direction) ?: return
    EntryLevelAlertSettings.markTradeOpened(context, alertSide, atMillis)
}

internal fun entryLevelAlertNotificationId(side: EntryAlertSide): Int =
    ENTRY_LEVEL_ALERT_PUSH_BASE_ID + when (side) {
        EntryAlertSide.Long -> 0
        EntryAlertSide.Short -> 1
    }

internal fun entryLevelAlertTitle(side: EntryAlertSide, levelPct: Double): String {
    val levelText = formatRuSignedNumber(levelPct)
    return when (side) {
        EntryAlertSide.Long -> "Вход Long ≤ $levelText%"
        EntryAlertSide.Short -> "Вход Short ≥ $levelText%"
    }
}

internal fun entryLevelAlertBody(side: EntryAlertSide, currSpread: Double, levelPct: Double): String {
    val levelText = formatRuSignedNumber(levelPct)
    val currText = formatRuSignedNumber(currSpread)
    val sideRu = when (side) {
        EntryAlertSide.Long -> "Long"
        EntryAlertSide.Short -> "Short"
    }
    return String.format(
        Locale.US,
        "%s · порог %s%% · сейчас %s%%",
        sideRu,
        levelText,
        currText,
    )
}

internal fun detectEntryLevelCrosses(
    prevSpread: Double,
    currSpread: Double,
    longEnterPct: Double,
    shortEnterPct: Double,
): List<EntryAlertSide> {
    if (!prevSpread.isFinite() || !currSpread.isFinite()) return emptyList()
    if (prevSpread == currSpread) return emptyList()
    val out = ArrayList<EntryAlertSide>(2)
    if (longEnterPct.isFinite() && prevSpread > longEnterPct && currSpread <= longEnterPct) {
        out += EntryAlertSide.Long
    }
    if (shortEnterPct.isFinite() && prevSpread < shortEnterPct && currSpread >= shortEnterPct) {
        out += EntryAlertSide.Short
    }
    return out
}

internal fun showEntryLevelAlertPushNotification(
    context: Context,
    side: EntryAlertSide,
    currSpread: Double,
): Boolean {
    if (!EntryLevelAlertSettings.isEnabled(context, side)) return false
    val levelPct = EntryLevelAlertSettings.enterPct(context, side)
    val sideKey = when (side) {
        EntryAlertSide.Long -> "long"
        EntryAlertSide.Short -> "short"
    }
    val levelKey = String.format(Locale.US, "%.1f", levelPct)
    return showPushNotification(
        context = context,
        title = entryLevelAlertTitle(side, levelPct),
        body = entryLevelAlertBody(side, currSpread, levelPct),
        notificationId = entryLevelAlertNotificationId(side),
        skipDuplicateCheck = true,
        correlationTag = "${ENTRY_LEVEL_ALERT_CORRELATION_PREFIX}${sideKey}_$levelKey",
    )
}

internal fun formatEntryAlertTradeLabel(atMillis: Long): String =
    "Сделка, ${formatPortfolioExecutionTableMsk(atMillis)}"
