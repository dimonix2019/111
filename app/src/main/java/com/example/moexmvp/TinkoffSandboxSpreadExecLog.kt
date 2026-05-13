package com.example.moexmvp

import android.content.Context
import org.json.JSONObject

private const val PREFS = "moex_sandbox_spread_exec"
private const val KEY_JSON = "last_spread_exec_json"

/** Последнее успешное «Принять» с двумя ногами спрэда (для вкладки «Портфель»). */
internal data class SandboxSpreadExecUi(
    val signalType: StrategySignalType,
    val zScore: Double,
    val timestampMillis: Long,
    val legsRu: String
)

internal object TinkoffSandboxSpreadExecLog {

    fun record(context: Context, signalType: StrategySignalType, zScore: Double, timestampMillis: Long) {
        if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return
        val legs = when (signalType) {
            StrategySignalType.EnterLong ->
                "1) TATN — покупка 1 лот\n2) TATNP — продажа 1 лот"
            StrategySignalType.EnterShort ->
                "1) TATNP — покупка 1 лот\n2) TATN — продажа 1 лот"
            else -> ""
        }
        val o = JSONObject()
            .put("signalType", signalType.name)
            .put("zScore", zScore)
            .put("timestampMillis", timestampMillis)
            .put("legsRu", legs)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, o.toString())
            .apply()
    }

    fun load(context: Context): SandboxSpreadExecUi? {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val type = runCatching { StrategySignalType.valueOf(o.optString("signalType")) }.getOrNull()
            ?: return null
        if (type != StrategySignalType.EnterLong && type != StrategySignalType.EnterShort) return null
        return SandboxSpreadExecUi(
            signalType = type,
            zScore = o.optDouble("zScore", 0.0),
            timestampMillis = o.optLong("timestampMillis", 0L),
            legsRu = o.optString("legsRu", "")
        )
    }
}
