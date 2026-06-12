package com.example.moexmvp

import android.content.Context

private const val PREFS = "moex_sandbox_account_pnl"
private const val KEY_CUMULATIVE_NET_RUB = "cumulative_realized_net_rub"

/** Накопленный чистый PnL закрытых сделок на демо-счёте (в приложении). */
internal object SandboxAccountPnlLedger {

    fun loadCumulativeNetPnlRub(context: Context): Double {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_CUMULATIVE_NET_RUB, 0f).toDouble()
    }

    fun addClosedTradeNetPnlRub(context: Context, netPnlRub: Double): Double {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = loadCumulativeNetPnlRub(app) + netPnlRub
        prefs.edit().putFloat(KEY_CUMULATIVE_NET_RUB, next.toFloat()).apply()
        return next
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CUMULATIVE_NET_RUB)
            .apply()
    }
}
