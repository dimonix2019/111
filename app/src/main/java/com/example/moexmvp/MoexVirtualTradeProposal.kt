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
    val exitThreshold: Double,
    val receivedAtMillis: Long
) {
    val titleRu: String
        get() = when (signalType) {
            StrategySignalType.EnterLong -> "Вход: LONG TATN / SHORT TATNP"
            StrategySignalType.EnterShort -> "Вход: LONG TATNP / SHORT TATN"
            else -> "Сигнал"
        }

    val bodyRu: String
        get() = buildString {
            append(formatMessageReceivedLine(receivedAtMillis))
            append('\n')
            append(
                String.format(
                    java.util.Locale.US,
                    "Z = %.2f · порог входа ±%.2f, выход ±%.2f\n(подтверждение в приложении; при песочнице — 2 заявки: 1×покупка + 1×продажа по ногам спрэда TATN/TATNP)",
                    zScore,
                    entryThreshold,
                    exitThreshold
                )
            )
        }
}

/** Не поднимать ту же карточку / AUTO-вход, которую пользователь только что отклонил. */
internal fun shouldOfferPendingVirtualTrade(
    rejectedTimestampMillis: Long,
    rejectedTypeName: String?,
    signalType: StrategySignalType,
    timestampMillis: Long,
): Boolean {
    if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) {
        return false
    }
    return rejectedTimestampMillis != timestampMillis || rejectedTypeName != signalType.name
}

internal fun loadRejectedVirtualEntry(context: Context): Pair<Long, String?> {
    val prefs = context.getSharedPreferences(VIRTUAL_TRADE_PREFS, Context.MODE_PRIVATE)
    return prefs.getLong(PREF_REJECTED_VIRTUAL_TS, Long.MIN_VALUE) to
        prefs.getString(PREF_REJECTED_VIRTUAL_TYPE, null)
}

internal fun isRejectedVirtualEntry(
    context: Context,
    signalType: StrategySignalType,
    timestampMillis: Long,
): Boolean {
    val (rejTs, rejTy) = loadRejectedVirtualEntry(context)
    return !shouldOfferPendingVirtualTrade(rejTs, rejTy, signalType, timestampMillis)
}

internal fun savePendingVirtualTradeProposal(
    context: Context,
    signalType: StrategySignalType,
    zScore: Double,
    timestampMillis: Long
) {
    if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return
    if (isRejectedVirtualEntry(context, signalType, timestampMillis)) return
    val th = loadRealTradeZThresholds(
        context,
        loadSavedDynamicThresholds(context)
            ?: DynamicThresholds(
                entry = DEFAULT_DYNAMIC_Z_ENTRY,
                exit = DEFAULT_DYNAMIC_Z_EXIT,
                calculatedDate = null
            )
    )
    val receivedAt = System.currentTimeMillis()
    val json = JSONObject()
        .put("signalType", signalType.name)
        .put("zScore", zScore)
        .put("timestampMillis", timestampMillis)
        .put("receivedAtMillis", receivedAt)
        .put("entryThreshold", th.entry)
        .put("exitThreshold", th.exit)
    context.getSharedPreferences(VIRTUAL_TRADE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_PENDING_JSON, json.toString())
        .commit()
}

internal fun loadPendingVirtualTradeProposal(context: Context): PendingVirtualTradeProposal? {
    val raw = context.getSharedPreferences(VIRTUAL_TRADE_PREFS, Context.MODE_PRIVATE)
        .getString(PREF_PENDING_JSON, null) ?: return null
    val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val typeName = o.optString("signalType")
    val type = runCatching { StrategySignalType.valueOf(typeName) }.getOrNull() ?: return null
    if (type != StrategySignalType.EnterLong && type != StrategySignalType.EnterShort) return null
    val barTs = o.optLong("timestampMillis", 0L)
    return PendingVirtualTradeProposal(
        signalType = type,
        zScore = o.optDouble("zScore", 0.0),
        timestampMillis = barTs,
        entryThreshold = o.optDouble("entryThreshold", DEFAULT_DYNAMIC_Z_ENTRY),
        exitThreshold = o.optDouble("exitThreshold", DEFAULT_DYNAMIC_Z_EXIT),
        receivedAtMillis = o.optLong("receivedAtMillis", barTs)
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

/** Карточка «Принять» только пока нет открытой сделки. */
internal fun shouldShowPendingVirtualTradeCard(
    pending: PendingVirtualTradeProposal?,
    tradeOpen: Boolean,
    savedPosition: ZStrategyPosition = ZStrategyPosition.Flat,
): Boolean {
    if (pending == null) return false
    if (tradeOpen) return false
    if (savedPosition == ZStrategyPosition.Long || savedPosition == ZStrategyPosition.Short) return false
    return true
}

/**
 * Восстанавливать карточку «Принять» только если сделки ещё нет.
 * Раньше: журнал вход + позиция Long/Short → карточка снова всплывала сразу после открытия.
 */
internal fun shouldRestorePendingVirtualFromJournal(position: ZStrategyPosition): Boolean =
    position == ZStrategyPosition.Flat

/** PendingIntent в push: только если вход подтверждается карточкой «Принять» (не авто-режим). */
internal fun entryVirtualTradeTapIfManualAccept(
    context: Context,
    signalType: StrategySignalType,
    zScore: Double,
    timestampMillis: Long
): VirtualTradeTapIntent? {
    if (TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context)) return null
    if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return null
    return VirtualTradeTapIntent(signalType, zScore, timestampMillis)
}

/**
 * После экстренного закрытия / «Отклонить»: убрать карточку, не дать AUTO открыть ту же пару
 * и не сбрасывать last-processed 15м бар (иначе монитор заново видит пересечение на последней паре).
 */
internal fun suppressUserCancelledEntry(
    context: Context,
    proposal: PendingVirtualTradeProposal? = loadPendingVirtualTradeProposal(context),
) {
    val app = context.applicationContext
    if (proposal != null) {
        clearPendingVirtualTradeProposal(app, proposal)
        val zSig = when (proposal.signalType) {
            StrategySignalType.EnterLong -> ZStrategySignal.EnterLong
            StrategySignalType.EnterShort -> ZStrategySignal.EnterShort
            else -> null
        }
        if (zSig != null) {
            mark15mStrategySignalEdgeConsumed(app, proposal.timestampMillis, zSig)
        }
        rememberSandboxAutoEntryDedup(app, proposal.signalType, proposal.timestampMillis)
    }
    val lastBar = loadLastProcessed15mBarTimestamp(app)
        ?: loadStrategySignalEvents(app).maxOfOrNull { it.timestampMillis }
    if (lastBar != null && lastBar > 0L) {
        saveLastProcessed15mBarTimestamp(app, lastBar)
    }
}

internal fun restorePendingVirtualTradeFromJournalIfNeeded(context: Context) {
    if (TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context)) return
    val pos = loadSavedStrategyPosition(context)
    if (!shouldRestorePendingVirtualFromJournal(pos)) {
        clearPendingVirtualTradeProposal(context)
    }
}
