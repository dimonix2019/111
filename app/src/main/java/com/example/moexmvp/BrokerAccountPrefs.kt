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
private const val KEY_LAST_TP_PCT = "last_take_profit_pct"
private const val KEY_OPEN_TP_PCT = "open_take_profit_pct"
private const val KEY_TP_FIRED_FP = "take_profit_fired_fp"

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

    fun lastTakeProfitPct(context: Context): Double {
        if (!prefs(context).contains(KEY_LAST_TP_PCT)) return DEFAULT_TAKE_PROFIT_PCT
        return coerceTakeProfitPct(prefs(context).getFloat(KEY_LAST_TP_PCT, DEFAULT_TAKE_PROFIT_PCT.toFloat()).toDouble())
    }

    fun takeProfitPctAtOpen(context: Context): Double? {
        if (!prefs(context).contains(KEY_OPEN_TP_PCT)) return null
        val v = prefs(context).getFloat(KEY_OPEN_TP_PCT, 0f).toDouble()
        return if (v > 0.0 && v.isFinite()) coerceTakeProfitPct(v) else null
    }

    fun takeProfitPctForOpenOrDefault(context: Context): Double =
        takeProfitPctAtOpen(context) ?: lastTakeProfitPct(context)

    fun takeProfitFiredFingerprint(context: Context): String =
        prefs(context).getString(KEY_TP_FIRED_FP, "").orEmpty()

    fun saveTakeProfitForOpen(context: Context, takeProfitPct: Double) {
        val tp = coerceTakeProfitPct(takeProfitPct)
        prefs(context).edit()
            .putFloat(KEY_LAST_TP_PCT, tp.toFloat())
            .putFloat(KEY_OPEN_TP_PCT, tp.toFloat())
            .putString(KEY_TP_FIRED_FP, "")
            .apply()
    }

    fun markTakeProfitFired(context: Context, fingerprint: String) {
        prefs(context).edit().putString(KEY_TP_FIRED_FP, fingerprint).apply()
    }

    fun clearTakeProfitAtOpen(context: Context) {
        prefs(context).edit()
            .putFloat(KEY_OPEN_TP_PCT, 0f)
            .putString(KEY_TP_FIRED_FP, "")
            .apply()
    }

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
            ed.putFloat(KEY_OPEN_TP_PCT, 0f)
            ed.putString(KEY_TP_FIRED_FP, "")
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
