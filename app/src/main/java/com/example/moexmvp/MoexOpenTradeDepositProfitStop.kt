package com.example.moexmvp

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Push с кнопкой «стоп лосс 2%» при достижении этой прибыли от «Суммы в сделке». */
internal const val OPEN_TRADE_PROFIT_STOP_ARM_PERCENT = 2.2

/** Защита прибыли: закрыть, если PnL опустится ниже N% от депозита (размера счёта). */
internal const val OPEN_TRADE_DEPOSIT_STOP_LOSS_PERCENT = 2.0

internal const val ACTION_ARM_OPEN_TRADE_DEPOSIT_STOP =
    "com.example.moexmvp.action.ARM_OPEN_TRADE_DEPOSIT_STOP"
internal const val EXTRA_OPEN_TRADE_ID = "open_trade_id"
internal const val EXTRA_DEPOSIT_STOP_PERCENT = "deposit_stop_percent"

private const val OPEN_TRADE_DEPOSIT_STOP_PREFS = "moex_open_trade_deposit_stop"
private const val PREF_DEPOSIT_STOP_JSON = "deposit_stop_json"

internal data class OpenTradeDepositProfitStop(
    val tradeId: String,
    val floorPnlRub: Double,
    val depositRub: Double,
    val stopPercent: Double,
    val armedAtMillis: Long,
    val pnlAtArmRub: Double,
    val brokerStopOrderIds: List<String> = emptyList(),
    val brokerStopSummary: String = "",
)

internal fun resolveAccountDepositRub(context: Context): Double {
    loadLastProdPortfolioCashRub(context)?.takeIf { it > 0.0 }?.let { return it }
    val strategyAccount = loadStrategyTestAccountSizeRub(context)
    if (strategyAccount > 0.0) return strategyAccount
    return TinkoffSandboxStorage.getPortfolioTradeAmountRub(context).coerceAtLeast(1.0)
}

internal fun computeDepositProfitStopFloorRub(
    depositRub: Double,
    stopPercent: Double = OPEN_TRADE_DEPOSIT_STOP_LOSS_PERCENT,
): Double {
    val deposit = depositRub.coerceAtLeast(1.0)
    return deposit * (stopPercent.coerceAtLeast(0.0) / 100.0)
}

internal fun armOpenTradeDepositProfitStop(
    context: Context,
    tradeId: String,
    currentPnlRub: Double,
    stopPercent: Double = OPEN_TRADE_DEPOSIT_STOP_LOSS_PERCENT,
): OpenTradeDepositProfitStop? {
    val depositRub = resolveAccountDepositRub(context)
    val floorRub = computeDepositProfitStopFloorRub(depositRub, stopPercent)
    val stop = OpenTradeDepositProfitStop(
        tradeId = tradeId,
        floorPnlRub = floorRub,
        depositRub = depositRub,
        stopPercent = stopPercent,
        armedAtMillis = System.currentTimeMillis(),
        pnlAtArmRub = currentPnlRub,
    )
    saveOpenTradeDepositProfitStop(context, stop)
    return stop
}

internal fun loadOpenTradeDepositProfitStop(context: Context, tradeId: String): OpenTradeDepositProfitStop? =
    loadAllOpenTradeDepositProfitStops(context)[tradeId]

internal fun loadAllOpenTradeDepositProfitStops(context: Context): Map<String, OpenTradeDepositProfitStop> {
    val raw = context.getSharedPreferences(OPEN_TRADE_DEPOSIT_STOP_PREFS, Context.MODE_PRIVATE)
        .getString(PREF_DEPOSIT_STOP_JSON, null)
        ?: return emptyMap()
    return runCatching { decodeOpenTradeDepositProfitStops(raw) }.getOrDefault(emptyMap())
}

internal fun saveOpenTradeDepositProfitStop(context: Context, stop: OpenTradeDepositProfitStop) {
    val all = loadAllOpenTradeDepositProfitStops(context).toMutableMap()
    all[stop.tradeId] = stop
    context.getSharedPreferences(OPEN_TRADE_DEPOSIT_STOP_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_DEPOSIT_STOP_JSON, encodeOpenTradeDepositProfitStops(all))
        .apply()
}

internal fun clearOpenTradeDepositProfitStop(context: Context, tradeId: String) {
    val all = loadAllOpenTradeDepositProfitStops(context).toMutableMap()
    if (all.remove(tradeId) == null) return
    context.getSharedPreferences(OPEN_TRADE_DEPOSIT_STOP_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_DEPOSIT_STOP_JSON, encodeOpenTradeDepositProfitStops(all))
        .apply()
}

internal fun clearOpenTradeDepositProfitStopsExcept(context: Context, openTradeIds: Set<String>) {
    val all = loadAllOpenTradeDepositProfitStops(context)
    val filtered = all.filterKeys { it in openTradeIds }
    if (filtered.size == all.size) return
    context.getSharedPreferences(OPEN_TRADE_DEPOSIT_STOP_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_DEPOSIT_STOP_JSON, encodeOpenTradeDepositProfitStops(filtered))
        .apply()
}

internal fun formatDepositProfitStopArmedBody(stop: OpenTradeDepositProfitStop): String =
    buildString {
        append(String.format(
        Locale.US,
        "Депозит %s · стоп при PnL < %s (2%% счёта) · сейчас %s",
        formatRubSigned(stop.depositRub),
        formatRubSigned(stop.floorPnlRub),
        formatRubSigned(stop.pnlAtArmRub),
        ))
        if (stop.brokerStopSummary.isNotBlank()) {
            append('\n')
            append(stop.brokerStopSummary)
        }
    }

private fun formatBrokerLegStopOrdersSummary(placements: List<BrokerLegStopOrderPlacement>): String =
    placements.joinToString("; ") { p ->
        val side = if (p.closeDirection.endsWith("_SELL")) "SELL" else "BUY"
        "${p.ticker} $side stop ${String.format(Locale.US, "%.1f", p.stopPriceRub)} ₽ #${p.stopOrderId}"
    }

internal suspend fun armOpenTradeBrokerLegStopLoss(
    context: Context,
    execution: SandboxSpreadExecUi,
    stopPercent: Double,
): List<BrokerLegStopOrderPlacement> {
    if (currentExecutionMode(context) != TinkoffExecutionMode.Prod) return emptyList()
    return placeProdOpenTradeLegStopLossOrders(context, execution, stopPercent)
}

internal suspend fun processOpenTradeDepositProfitStops(
    context: Context,
    points: List<DataPoint>,
) {
    if (points.size < 2) return
    val stops = loadAllOpenTradeDepositProfitStops(context)
    if (stops.isEmpty()) return
    val openGroups = loadOpenPortfolioTradeGroupsForRiskMonitor(context, points)
    val openIds = openGroups.map { it.tradeId }.toSet()
    clearOpenTradeDepositProfitStopsExcept(context, openIds)
    if (openGroups.isEmpty()) return

    val executions = withContext(Dispatchers.IO) {
        TinkoffSandboxSpreadExecLog.loadRecent(context)
    }
    for (group in openGroups) {
        if (!group.isOpen) continue
        val stop = stops[group.tradeId] ?: continue
        if (group.netPnlRubApprox.isNaN()) continue
        if (group.netPnlRubApprox > stop.floorPnlRub) continue
        val execution = executions.firstOrNull { it.tradeId == group.tradeId } ?: continue
        withContext(Dispatchers.IO) {
            runCatching { closePortfolioOpenTrade(context, execution).getOrThrow() }
        }
        clearOpenTradeDepositProfitStop(context, group.tradeId)
        showPushNotification(
            context = context,
            title = "Стоп-лосс ${stop.stopPercent.toInt()}% депозита",
            body = String.format(
                Locale.US,
                "%s · PnL %s ≤ порог %s · сделка закрыта",
                group.tradeDisplayId,
                formatCompactSignedPnlRub(group.netPnlRubApprox),
                formatRubSigned(stop.floorPnlRub),
            ),
            notificationId = openTradeDepositStopTriggeredNotificationId(group.tradeId),
            correlationTag = "openTradeDepositStop|hit|${group.tradeId}",
            skipDuplicateCheck = true,
        )
    }
}

internal fun openTradeDepositStopTriggeredNotificationId(tradeId: String): Int =
    "openTradeDepositStop|hit|$tradeId".hashCode()

internal suspend fun handleArmOpenTradeDepositStopIntentSuspend(context: Context, intent: Intent) {
    if (intent.action != ACTION_ARM_OPEN_TRADE_DEPOSIT_STOP) return
    val tradeId = intent.getStringExtra(EXTRA_OPEN_TRADE_ID)?.trim().orEmpty()
    if (tradeId.isEmpty()) return
    val stopPercent = intent.getDoubleExtra(EXTRA_DEPOSIT_STOP_PERCENT, OPEN_TRADE_DEPOSIT_STOP_LOSS_PERCENT)
        .takeIf { !it.isNaN() && it > 0.0 }
        ?: OPEN_TRADE_DEPOSIT_STOP_LOSS_PERCENT

    val app = context.applicationContext
    val executions = TinkoffSandboxSpreadExecLog.loadRecent(app)
    val execution = executions.firstOrNull { it.tradeId == tradeId } ?: return
    val placements = try {
        armOpenTradeBrokerLegStopLoss(app, execution, stopPercent)
    } catch (e: IOException) {
        showPushNotification(
            context = app,
            title = "Стоп-лосс ${stopPercent.toInt()}% не поставлен",
            body = "Tinkoff stop-order: ${e.message ?: e.javaClass.simpleName}",
            notificationId = openTradeDepositStopArmedNotificationId(tradeId),
            correlationTag = "openTradeDepositStop|broker_failed|$tradeId",
            skipDuplicateCheck = true,
        )
        return
    }
    val brokerPnl = if (currentExecutionMode(app) == TinkoffExecutionMode.Prod) {
        runCatching { loadProdSpreadLegBrokerPnl(app, execution.signalType)?.netGrossRub }.getOrNull()
    } else {
        null
    }
    val pnl = execution.netPnlRubApprox.takeUnless { it.isNaN() } ?: brokerPnl ?: 0.0

    val baseStop = armOpenTradeDepositProfitStop(
        context = app,
        tradeId = tradeId,
        currentPnlRub = pnl,
        stopPercent = stopPercent,
    ) ?: return
    val stop = if (placements.isEmpty()) {
        baseStop
    } else {
        baseStop.copy(
            brokerStopOrderIds = placements.map { it.stopOrderId },
            brokerStopSummary = formatBrokerLegStopOrdersSummary(placements),
        ).also { saveOpenTradeDepositProfitStop(app, it) }
    }

    showPushNotification(
        context = app,
        title = if (placements.isEmpty()) {
            "Стоп-лосс ${stopPercent.toInt()}% депозита включён"
        } else {
            "Stop-loss ${stopPercent.toInt()}% выставлен в Tinkoff"
        },
        body = formatDepositProfitStopArmedBody(stop),
        notificationId = openTradeDepositStopArmedNotificationId(tradeId),
        correlationTag = "openTradeDepositStop|armed|$tradeId",
        skipDuplicateCheck = true,
    )
}

internal fun handleArmOpenTradeDepositStopIntent(context: Context, intent: Intent) {
    runBlocking { handleArmOpenTradeDepositStopIntentSuspend(context, intent) }
}

internal fun openTradeDepositStopArmedNotificationId(tradeId: String): Int =
    "openTradeDepositStop|armed|$tradeId".hashCode()

internal fun buildArmDepositStopPendingIntent(
    context: Context,
    tradeId: String,
    stopPercent: Double = OPEN_TRADE_DEPOSIT_STOP_LOSS_PERCENT,
    requestCode: Int,
): android.app.PendingIntent {
    val intent = Intent(context, OpenTradeDepositProfitStopReceiver::class.java).apply {
        action = ACTION_ARM_OPEN_TRADE_DEPOSIT_STOP
        putExtra(EXTRA_OPEN_TRADE_ID, tradeId)
        putExtra(EXTRA_DEPOSIT_STOP_PERCENT, stopPercent)
    }
    return android.app.PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )
}

internal fun encodeOpenTradeDepositProfitStops(stops: Map<String, OpenTradeDepositProfitStop>): String {
    val root = JSONObject()
    for ((tradeId, stop) in stops) {
        root.put(
            tradeId,
            JSONObject()
                .put("floorPnlRub", stop.floorPnlRub)
                .put("depositRub", stop.depositRub)
                .put("stopPercent", stop.stopPercent)
                .put("armedAtMillis", stop.armedAtMillis)
                .put("pnlAtArmRub", stop.pnlAtArmRub)
                .put("brokerStopOrderIds", JSONArray().apply { stop.brokerStopOrderIds.forEach(::put) })
                .put("brokerStopSummary", stop.brokerStopSummary),
        )
    }
    return root.toString()
}

internal fun decodeOpenTradeDepositProfitStops(raw: String): Map<String, OpenTradeDepositProfitStop> {
    val root = JSONObject(raw)
    val out = linkedMapOf<String, OpenTradeDepositProfitStop>()
    for (key in root.keys()) {
        val o = root.optJSONObject(key) ?: continue
        out[key] = OpenTradeDepositProfitStop(
            tradeId = key,
            floorPnlRub = o.optDouble("floorPnlRub", Double.NaN),
            depositRub = o.optDouble("depositRub", Double.NaN),
            stopPercent = o.optDouble("stopPercent", OPEN_TRADE_DEPOSIT_STOP_LOSS_PERCENT),
            armedAtMillis = o.optLong("armedAtMillis", 0L),
            pnlAtArmRub = o.optDouble("pnlAtArmRub", Double.NaN),
            brokerStopOrderIds = buildList {
                val arr = o.optJSONArray("brokerStopOrderIds") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            },
            brokerStopSummary = o.optString("brokerStopSummary", ""),
        )
    }
    return out
}

class OpenTradeDepositProfitStopReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val pendingResult = goAsync()
        Thread {
            try {
                handleArmOpenTradeDepositStopIntent(context, intent)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
