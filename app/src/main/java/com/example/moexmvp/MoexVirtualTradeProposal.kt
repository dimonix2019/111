package com.example.moexmvp

import android.content.Context
import org.json.JSONObject

private const val VIRTUAL_TRADE_PREFS = "moex_virtual_trade"
private const val PREF_PENDING_JSON = "pending_virtual_entry_json"

internal data class PendingVirtualTradeProposal(
    val signalType: StrategySignalType,
    val zScore: Double,
    val timestampMillis: Long,
    val entryThreshold: Double,
    val exitThreshold: Double
) {
    val titleRu: String
        get() = when (signalType) {
            StrategySignalType.EnterLong -> "Вход: LONG TATN / SHORT TATNP"
            StrategySignalType.EnterShort -> "Вход: LONG TATNP / SHORT TATN"
            else -> "Сигнал"
        }

    val bodyRu: String
        get() = String.format(
            java.util.Locale.US,
            "Z = %.2f · порог входа ±%.2f, выход ±%.2f\n(подтверждение в приложении; при песочнице — 2 заявки на демо-счёт)",
            zScore,
            entryThreshold,
            exitThreshold
        )
}

internal fun savePendingVirtualTradeProposal(
    context: Context,
    signalType: StrategySignalType,
    zScore: Double,
    timestampMillis: Long
) {
    if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return
    val th = loadSavedDynamicThresholds(context)
        ?: DynamicThresholds(
            entry = DEFAULT_DYNAMIC_Z_ENTRY,
            exit = DEFAULT_DYNAMIC_Z_EXIT,
            calculatedDate = null
        )
    val json = JSONObject()
        .put("signalType", signalType.name)
        .put("zScore", zScore)
        .put("timestampMillis", timestampMillis)
        .put("entryThreshold", th.entry)
        .put("exitThreshold", th.exit)
    context.getSharedPreferences(VIRTUAL_TRADE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_PENDING_JSON, json.toString())
        .apply()
}

internal fun loadPendingVirtualTradeProposal(context: Context): PendingVirtualTradeProposal? {
    val raw = context.getSharedPreferences(VIRTUAL_TRADE_PREFS, Context.MODE_PRIVATE)
        .getString(PREF_PENDING_JSON, null) ?: return null
    val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val typeName = o.optString("signalType")
    val type = runCatching { StrategySignalType.valueOf(typeName) }.getOrNull() ?: return null
    if (type != StrategySignalType.EnterLong && type != StrategySignalType.EnterShort) return null
    return PendingVirtualTradeProposal(
        signalType = type,
        zScore = o.optDouble("zScore", 0.0),
        timestampMillis = o.optLong("timestampMillis", 0L),
        entryThreshold = o.optDouble("entryThreshold", DEFAULT_DYNAMIC_Z_ENTRY),
        exitThreshold = o.optDouble("exitThreshold", DEFAULT_DYNAMIC_Z_EXIT)
    )
}

internal fun clearPendingVirtualTradeProposal(context: Context) {
    context.getSharedPreferences(VIRTUAL_TRADE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(PREF_PENDING_JSON)
        .apply()
}
