package com.example.moexmvp

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

/** Источник времени входа для UI. */
internal enum class SpreadEntryTimeSource {
    ExecLog,
    TradeFillLog,
    BrokerPrefs,
    GetOperations,
}

/**
 * Цепочка: журнал исполнений → лог ног → prefs → GetOperations (последние 90 д).
 */
internal suspend fun resolveSpreadEntryTimeMsk(
    context: Context,
    side: ZStrategyPosition,
    token: String,
    accountId: String,
    exec: SandboxSpreadExecUi?,
): Pair<String, SpreadEntryTimeSource>? {
    if (side == ZStrategyPosition.Flat) return null

    exec?.entryTimeMsk?.takeIf { it.isNotBlank() && it != "—" }?.let {
        return it to SpreadEntryTimeSource.ExecLog
    }

    resolveEntryTimeFromTradeFillLog(context, side)?.let { return it }

    BrokerAccountPrefs.entryTimeMskAtOpen(context)?.takeIf { it.isNotBlank() }?.let {
        return it to SpreadEntryTimeSource.BrokerPrefs
    }

    fetchSpreadEntryTimeFromOperations(token, accountId, side)?.let {
        BrokerAccountPrefs.saveEntryTimeAtOpen(context, it)
        return it to SpreadEntryTimeSource.GetOperations
    }

    return null
}

private fun resolveEntryTimeFromTradeFillLog(
    context: Context,
    side: ZStrategyPosition,
): Pair<String, SpreadEntryTimeSource>? {
    val fills = TradeExecutionLog.loadRecent(context)
        .filter { it.phase == TradeExecPhase.Entry && it.executionMode == TinkoffExecutionMode.Prod }
    if (fills.isEmpty()) return null

    val wantTatnBuy = side == ZStrategyPosition.Long
    val tatnMs = fills.filter { it.ticker == "TATN" && legSideIsBuy(it.sideRu) == wantTatnBuy }
        .maxOfOrNull { it.fillTimeMillis }
    val tatnpMs = fills.filter { it.ticker == "TATNP" && legSideIsBuy(it.sideRu) == !wantTatnBuy }
        .maxOfOrNull { it.fillTimeMillis }

    val ms = listOfNotNull(tatnMs, tatnpMs).maxOrNull() ?: return null
    if (tatnMs != null && tatnpMs != null && kotlin.math.abs(tatnMs - tatnpMs) > 7L * 24 * 3_600_000) {
        return null
    }
    return formatPortfolioExecutionTableMsk(ms) to SpreadEntryTimeSource.TradeFillLog
}

private fun legSideIsBuy(sideRu: String): Boolean =
    sideRu.contains("покуп", ignoreCase = true)

internal suspend fun fetchSpreadEntryTimeFromOperations(
    token: String,
    accountId: String,
    side: ZStrategyPosition,
): String? {
    if (side == ZStrategyPosition.Flat) return null
    val now = System.currentTimeMillis()
    val from = now - 90L * 24 * 3_600_000
    val root = runCatching {
        tinkoffProdOperationsPostAsync(
            token,
            "GetOperations",
            JSONObject()
                .put("accountId", accountId.trim())
                .put("from", Instant.ofEpochMilli(from).toString())
                .put("to", Instant.ofEpochMilli(now).toString())
                .put("state", "OPERATION_STATE_EXECUTED"),
        )
    }.getOrNull() ?: return null

    val ops = collectSpreadOperations(root)
        .mapNotNull { op ->
            val ticker = resolveSpreadOperationTicker(op) ?: return@mapNotNull null
            if (ticker != "TATN" && ticker != "TATNP") return@mapNotNull null
            val ms = parseSpreadOperationDateMillis(op) ?: return@mapNotNull null
            val buy = operationIsBuy(op) ?: return@mapNotNull null
            SpreadOperationHit(ticker, ms, buy)
        }
        .sortedByDescending { it.millis }

    val wantTatnBuy = side == ZStrategyPosition.Long
    val tatnMs = ops.firstOrNull { it.ticker == "TATN" && it.isBuy == wantTatnBuy }?.millis
    val tatnpMs = ops.firstOrNull { it.ticker == "TATNP" && it.isBuy == !wantTatnBuy }?.millis
    val ms = listOfNotNull(tatnMs, tatnpMs).maxOrNull() ?: return null
    if (tatnMs != null && tatnpMs != null && kotlin.math.abs(tatnMs - tatnpMs) > 7L * 24 * 3_600_000) {
        return formatPortfolioExecutionTableMsk(ms)
    }
    return formatPortfolioExecutionTableMsk(ms)
}

private data class SpreadOperationHit(
    val ticker: String,
    val millis: Long,
    val isBuy: Boolean,
)

private fun collectSpreadOperations(root: JSONObject): List<JSONObject> {
    val out = mutableListOf<JSONObject>()
    fun walk(o: JSONObject?, depth: Int) {
        if (o == null || depth > 8) return
        o.optJSONArray("operations")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(it) }
            }
        }
        val it = o.keys()
        while (it.hasNext()) {
            walk(o.optJSONObject(it.next()), depth + 1)
        }
    }
    walk(root, 0)
    return out
}

private fun parseSpreadOperationDateMillis(op: JSONObject): Long? {
    val raw = jsonFirstNonBlankOp(op, "date", "Date") ?: return null
    return runCatching { Instant.parse(raw.trim()).toEpochMilli() }.getOrNull()
}

private fun operationIsBuy(op: JSONObject): Boolean? {
    val t = jsonFirstNonBlankOp(op, "operationType", "type", "operation_type")
        ?.uppercase(Locale.US).orEmpty()
    when {
        t.contains("BUY") -> return true
        t.contains("SELL") -> return false
    }
    val payment = op.optJSONObject("payment")?.let { quotationUnitsToDouble(it) } ?: return null
    return payment < 0
}

private fun resolveSpreadOperationTicker(op: JSONObject): String? {
    val inst = op.optJSONObject("instrument")
    jsonFirstNonBlankOp(inst ?: op, "ticker", "Ticker")?.uppercase(Locale.US)?.let { return it }
    val figi = jsonFirstNonBlankOp(inst ?: op, "figi", "FIGI").orEmpty().uppercase(Locale.US)
    return when {
        "TATNP" in figi -> "TATNP"
        "TATN" in figi -> "TATN"
        else -> null
    }
}

private fun jsonFirstNonBlankOp(o: JSONObject, vararg keys: String): String? {
    for (k in keys) {
        val v = o.optString(k, "").trim()
        if (v.isNotEmpty() && v != "null") return v
    }
    return null
}
