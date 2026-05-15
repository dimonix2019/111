package com.example.moexmvp

import android.content.Context
import org.json.JSONObject

private const val VIRTUAL_TRADE_PREFS = "moex_virtual_trade"
private const val PREF_PENDING_JSON = "pending_virtual_entry_json"
private const val PREF_REJECTED_VIRTUAL_TS = "rejected_virtual_ts"
private const val PREF_REJECTED_VIRTUAL_TYPE = "rejected_virtual_type"

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
            "Z = %.2f · порог входа ±%.2f, выход ±%.2f\n(подтверждение в приложении; при песочнице — 2 заявки: 1×покупка + 1×продажа по ногам спрэда TATN/TATNP)",
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
    val th = loadRealTradeZThresholds(
        context,
        loadSavedDynamicThresholds(context)
            ?: DynamicThresholds(
                entry = DEFAULT_DYNAMIC_Z_ENTRY,
                exit = DEFAULT_DYNAMIC_Z_EXIT,
                calculatedDate = null
            )
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
        .remove(PREF_REJECTED_VIRTUAL_TS)
        .remove(PREF_REJECTED_VIRTUAL_TYPE)
        .commit()
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

/**
 * Убирает карточку «Принять».
 * @param consumedProposal если задан — этот вход больше не показываем после «Отклонить» / «Принять» (в т.ч. без песочницы).
 */
internal fun clearPendingVirtualTradeProposal(
    context: Context,
    consumedProposal: PendingVirtualTradeProposal? = null
) {
    val ed = context.getSharedPreferences(VIRTUAL_TRADE_PREFS, Context.MODE_PRIVATE).edit()
        .remove(PREF_PENDING_JSON)
    if (consumedProposal != null) {
        ed.putLong(PREF_REJECTED_VIRTUAL_TS, consumedProposal.timestampMillis)
            .putString(PREF_REJECTED_VIRTUAL_TYPE, consumedProposal.signalType.name)
    }
    ed.commit()
}

/** Сброс prefs карточки «Принять» и отклонённых входов (например при очистке журнала). */
internal fun clearVirtualTradeProposalPrefs(context: Context) {
    context.applicationContext.getSharedPreferences(VIRTUAL_TRADE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(PREF_PENDING_JSON)
        .remove(PREF_REJECTED_VIRTUAL_TS)
        .remove(PREF_REJECTED_VIRTUAL_TYPE)
        .commit()
}

/** После авто-входа на песочницу: убрать карточку и не поднимать её из журнала для этой записи входа. */
internal fun markVirtualTradeConsumedForJournalEntry(
    context: Context,
    signalType: StrategySignalType,
    timestampMillis: Long
) {
    if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return
    context.getSharedPreferences(VIRTUAL_TRADE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(PREF_PENDING_JSON)
        .putLong(PREF_REJECTED_VIRTUAL_TS, timestampMillis)
        .putString(PREF_REJECTED_VIRTUAL_TYPE, signalType.name)
        .commit()
}

/**
 * Если карточки нет, а в журнале последняя запись — вход и позиция совпадает — восстановить pending (после сбоя prefs / гонки apply()).
 */
internal fun restorePendingVirtualTradeFromJournalIfNeeded(context: Context) {
    if (loadPendingVirtualTradeProposal(context) != null) return
    val events = loadStrategySignalEvents(context)
    val last = events.lastOrNull() ?: return
    if (last.signalType != StrategySignalType.EnterLong && last.signalType != StrategySignalType.EnterShort) return
    val prefs = context.getSharedPreferences(VIRTUAL_TRADE_PREFS, Context.MODE_PRIVATE)
    val rejTs = prefs.getLong(PREF_REJECTED_VIRTUAL_TS, Long.MIN_VALUE)
    val rejTy = prefs.getString(PREF_REJECTED_VIRTUAL_TYPE, null)
    if (last.timestampMillis == rejTs && last.signalType.name == rejTy) return
    val pos = loadSavedStrategyPosition(context)
    val matches = when (last.signalType) {
        StrategySignalType.EnterLong -> pos == ZStrategyPosition.Long
        StrategySignalType.EnterShort -> pos == ZStrategyPosition.Short
        else -> false
    }
    if (!matches) return
    savePendingVirtualTradeProposal(
        context = context,
        signalType = last.signalType,
        zScore = last.zScore,
        timestampMillis = last.timestampMillis
    )
}
