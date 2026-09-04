package com.example.moexmvp

import android.content.Context

private const val BROKER_POLL_PREFS = "moex_broker_account_poll"
private const val KEY_LAST_SIDE = "last_side"
private const val KEY_LAST_FINGERPRINT = "last_fingerprint"
private const val KEY_SEEDED = "seeded"
private const val KEY_EQUITY_AT_OPEN = "equity_at_open"
private const val KEY_ENTRY_TIME_MSK = "entry_time_msk"
private const val KEY_PROFIT_2_FP = "profit_alert_2_fp"
private const val KEY_PROFIT_3_FP = "profit_alert_3_fp"
private const val KEY_LAST_YIELD = "last_yield_rub"

/** Состояние опроса T‑Invest (~15 с) для push open/close/2%/3%. */
internal object BrokerAccountPrefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(BROKER_POLL_PREFS, Context.MODE_PRIVATE)

    fun isSeeded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEEDED, false)

    fun setSeeded(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEEDED, value).apply()
    }

    fun lastSide(context: Context): ZStrategyPosition {
        val raw = prefs(context).getString(KEY_LAST_SIDE, ZStrategyPosition.Flat.name).orEmpty()
        return runCatching { ZStrategyPosition.valueOf(raw) }.getOrDefault(ZStrategyPosition.Flat)
    }

    fun lastFingerprint(context: Context): String =
        prefs(context).getString(KEY_LAST_FINGERPRINT, "FLAT").orEmpty().ifBlank { "FLAT" }

    fun lastYieldRub(context: Context): Double =
        prefs(context).getFloat(KEY_LAST_YIELD, 0f).toDouble()

    fun equityAtOpenRub(context: Context): Double =
        prefs(context).getFloat(KEY_EQUITY_AT_OPEN, 0f).toDouble()

    fun entryTimeMskAtOpen(context: Context): String? =
        prefs(context).getString(KEY_ENTRY_TIME_MSK, null)?.takeIf { it.isNotBlank() }

    fun saveEntryTimeAtOpen(context: Context, entryTimeMsk: String) {
        if (entryTimeMsk.isBlank()) return
        prefs(context).edit().putString(KEY_ENTRY_TIME_MSK, entryTimeMsk.trim()).apply()
    }

    fun profit2Fingerprint(context: Context): String =
        prefs(context).getString(KEY_PROFIT_2_FP, "").orEmpty()

    fun profit3Fingerprint(context: Context): String =
        prefs(context).getString(KEY_PROFIT_3_FP, "").orEmpty()

    fun saveSnap(
        context: Context,
        side: ZStrategyPosition,
        fingerprint: String,
        yieldRub: Double?,
        equityAtOpen: Double? = null,
        clearProfitFlags: Boolean = false,
    ) {
        val ed = prefs(context).edit()
            .putBoolean(KEY_SEEDED, true)
            .putString(KEY_LAST_SIDE, side.name)
            .putString(KEY_LAST_FINGERPRINT, fingerprint)
        if (yieldRub != null) {
            ed.putFloat(KEY_LAST_YIELD, yieldRub.toFloat())
        }
        if (equityAtOpen != null && equityAtOpen > 0) {
            ed.putFloat(KEY_EQUITY_AT_OPEN, equityAtOpen.toFloat())
        }
        if (clearProfitFlags) {
            ed.putString(KEY_PROFIT_2_FP, "")
            ed.putString(KEY_PROFIT_3_FP, "")
        }
        if (side == ZStrategyPosition.Flat) {
            ed.putFloat(KEY_EQUITY_AT_OPEN, 0f)
            ed.putString(KEY_ENTRY_TIME_MSK, "")
            ed.putString(KEY_PROFIT_2_FP, "")
            ed.putString(KEY_PROFIT_3_FP, "")
        }
        ed.apply()
    }

    fun markProfitAlert(context: Context, pct: Int, fingerprint: String) {
        val key = when (pct) {
            2 -> KEY_PROFIT_2_FP
            3 -> KEY_PROFIT_3_FP
            else -> return
        }
        prefs(context).edit().putString(key, fingerprint).apply()
    }
}
