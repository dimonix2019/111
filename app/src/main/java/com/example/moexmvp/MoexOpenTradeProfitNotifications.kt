package com.example.moexmvp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Пороги прибыли открытой сделки (% от «Суммы в сделке»), по одному push на порог за сделку. */
internal val OPEN_TRADE_PROFIT_NOTIFY_THRESHOLDS_PERCENT = listOf(2.0, 3.0)

private const val OPEN_TRADE_PROFIT_NOTIFY_PREFS = "moex_open_trade_profit_notify"
private const val PREF_PROFIT_STATES_JSON = "profit_states_json"

internal data class OpenTradeProfitNotifyState(
    val notifiedThresholdsPercent: Set<Double> = emptySet(),
)

internal data class OpenTradeProfitNotifyAction(
    val tradeId: String,
    val tradeDisplayId: String,
    val thresholdPercent: Double,
    val pnlRub: Double,
    val pnlPercent: Double,
)

internal fun planOpenTradeProfitNotifications(
    openGroups: List<PortfolioTradeGroupRow>,
    investedRub: Double,
    previousStates: Map<String, OpenTradeProfitNotifyState>,
    thresholdsPercent: List<Double> = OPEN_TRADE_PROFIT_NOTIFY_THRESHOLDS_PERCENT,
): Pair<List<OpenTradeProfitNotifyAction>, Map<String, OpenTradeProfitNotifyState>> {
    val actions = mutableListOf<OpenTradeProfitNotifyAction>()
    val nextStates = linkedMapOf<String, OpenTradeProfitNotifyState>()
    val openTradeIds = openGroups.map { it.tradeId }.toSet()

    for (group in openGroups) {
        if (!group.isOpen) continue
        val tradeId = group.tradeId
        val prev = previousStates[tradeId] ?: OpenTradeProfitNotifyState()
        val pnlPercent = openTradeReturnPercent(group.netPnlRubApprox, investedRub)
        if (pnlPercent.isNaN()) {
            nextStates[tradeId] = prev
            continue
        }
        var notified = prev.notifiedThresholdsPercent
        for (threshold in thresholdsPercent.sorted()) {
            if (pnlPercent >= threshold && threshold !in notified) {
                actions += OpenTradeProfitNotifyAction(
                    tradeId = tradeId,
                    tradeDisplayId = group.tradeDisplayId,
                    thresholdPercent = threshold,
                    pnlRub = group.netPnlRubApprox,
                    pnlPercent = pnlPercent,
                )
                notified = notified + threshold
            }
        }
        if (notified.isNotEmpty()) {
            nextStates[tradeId] = OpenTradeProfitNotifyState(notifiedThresholdsPercent = notified)
        }
    }

    for (tradeId in previousStates.keys) {
        if (tradeId !in openTradeIds) {
            // сделка закрыта — сброс состояния
        }
    }

    return actions to nextStates
}

internal suspend fun processOpenTradeProfitNotifications(
    context: Context,
    points: List<DataPoint>,
) {
    if (points.size < 2) return
    val openGroups = loadOpenPortfolioTradeGroupsForRiskMonitor(context, points)
    if (openGroups.isEmpty()) {
        saveOpenTradeProfitNotifyStates(context, emptyMap())
        return
    }
    val investedRub = resolveOpenTradeInvestedRub(context)
    val previousStates = loadOpenTradeProfitNotifyStates(context)
    val (actions, nextStates) = planOpenTradeProfitNotifications(
        openGroups = openGroups,
        investedRub = investedRub,
        previousStates = previousStates,
    )
    if (actions.isNotEmpty()) {
        dispatchOpenTradeProfitNotifications(context, actions)
    }
    saveOpenTradeProfitNotifyStates(context, nextStates)
}

internal fun dispatchOpenTradeProfitNotifications(
    context: Context,
    actions: List<OpenTradeProfitNotifyAction>,
) {
    for (action in actions) {
        val thresholdLabel = formatOpenTradeProfitThresholdLabel(action.thresholdPercent)
        showPushNotification(
            context = context,
            title = "Прибыль: $thresholdLabel",
            body = buildOpenTradeProfitNotificationBody(action),
            notificationId = openTradeProfitNotificationId(action.tradeId, action.thresholdPercent),
            correlationTag = openTradeProfitCorrelationTag(action.tradeId, action.thresholdPercent),
        )
    }
}

internal fun formatOpenTradeProfitThresholdLabel(thresholdPercent: Double): String =
    if (kotlin.math.abs(thresholdPercent - thresholdPercent.toLong().toDouble()) < 1e-9) {
        "+${thresholdPercent.toLong()}%"
    } else {
        String.format(Locale.US, "+%.1f%%", thresholdPercent)
    }

internal fun buildOpenTradeProfitNotificationBody(action: OpenTradeProfitNotifyAction): String =
    String.format(
        Locale.US,
        "%s · %s · %s",
        action.tradeDisplayId,
        formatCompactSignedPnlRub(action.pnlRub),
        formatCompactSignedPnlPercent(action.pnlPercent),
    )

internal fun openTradeProfitCorrelationTag(tradeId: String, thresholdPercent: Double): String =
    "openTradeProfit|${thresholdPercent}|$tradeId"

internal fun openTradeProfitNotificationId(tradeId: String, thresholdPercent: Double): Int =
    openTradeProfitCorrelationTag(tradeId, thresholdPercent).hashCode()

internal fun loadOpenTradeProfitNotifyStates(context: Context): Map<String, OpenTradeProfitNotifyState> {
    val raw = context.getSharedPreferences(OPEN_TRADE_PROFIT_NOTIFY_PREFS, Context.MODE_PRIVATE)
        .getString(PREF_PROFIT_STATES_JSON, null)
        ?: return emptyMap()
    return runCatching { decodeOpenTradeProfitNotifyStates(raw) }.getOrDefault(emptyMap())
}

internal fun saveOpenTradeProfitNotifyStates(
    context: Context,
    states: Map<String, OpenTradeProfitNotifyState>,
) {
    context.getSharedPreferences(OPEN_TRADE_PROFIT_NOTIFY_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_PROFIT_STATES_JSON, encodeOpenTradeProfitNotifyStates(states))
        .apply()
}

internal fun encodeOpenTradeProfitNotifyStates(
    states: Map<String, OpenTradeProfitNotifyState>,
): String {
    val root = JSONObject()
    for ((tradeId, state) in states) {
        val arr = JSONArray()
        for (t in state.notifiedThresholdsPercent.sorted()) {
            arr.put(t)
        }
        root.put(tradeId, arr)
    }
    return root.toString()
}

internal fun decodeOpenTradeProfitNotifyStates(raw: String): Map<String, OpenTradeProfitNotifyState> {
    val root = JSONObject(raw)
    val out = linkedMapOf<String, OpenTradeProfitNotifyState>()
    for (key in root.keys()) {
        val arr = root.optJSONArray(key) ?: continue
        val thresholds = buildSet {
            for (i in 0 until arr.length()) {
                val v = arr.optDouble(i, Double.NaN)
                if (!v.isNaN()) add(v)
            }
        }
        out[key] = OpenTradeProfitNotifyState(notifiedThresholdsPercent = thresholds)
    }
    return out
}
