package com.example.moexmvp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

internal enum class TradeExecPhase { Entry, Exit }

internal enum class TradeLegFillStatus { Full, Partial, Reject, Unknown }

internal data class TradeLegFillRecord(
    val id: String,
    val tradeId: String,
    val phase: TradeExecPhase,
    val ticker: String,
    val sideRu: String,
    val requestedLots: Int,
    val executedLots: Int,
    val fillStatus: TradeLegFillStatus,
    val fillPriceRub: Double?,
    val fillTimeMillis: Long,
    val orderId: String,
    val commissionRub: Double?,
    val refMoexPriceRub: Double?,
    val refSpreadPercent: Double?,
    val slippagePriceBps: Double?,
    val slippageSpreadPts: Double?,
    val executionMode: TinkoffExecutionMode,
    val signalBarMillis: Long = 0L,
    val zScore: Double = Double.NaN,
    val source: String = "",
) {
    val isPartial: Boolean get() = fillStatus == TradeLegFillStatus.Partial ||
        (requestedLots > 0 && executedLots in 1 until requestedLots)
}

internal object TradeExecutionLog {
    private const val PREFS = "trade_execution_log_prefs"
    private const val KEY_JSON = "fills_json_v1"
    private const val MAX_RECORDS = 2_000

    fun recordSpreadLegFills(
        context: Context,
        tradeId: String,
        phase: TradeExecPhase,
        legs: List<SandboxLegOrderResult>,
        executionMode: TinkoffExecutionMode,
        signalBarMillis: Long = 0L,
        zScore: Double = Double.NaN,
        refTatnPriceRub: Double? = null,
        refTatnpPriceRub: Double? = null,
        refSpreadPercent: Double? = null,
        source: String = "",
    ) {
        if (tradeId.isBlank() || legs.isEmpty()) return
        val app = context.applicationContext
        val spreadRef = refSpreadPercent ?: spreadPercentFromLegRefs(refTatnPriceRub, refTatnpPriceRub)
        legs.forEach { leg ->
            val parsed = parsePostOrderFill(leg.orderJson)
            val refPx = when (leg.ticker.uppercase(Locale.US)) {
                "TATN" -> refTatnPriceRub
                "TATNP" -> refTatnpPriceRub
                else -> null
            }
            val slipBps = computeLegSlippagePriceBps(
                sideRu = leg.sideRu,
                fillPriceRub = parsed.executedPriceRub,
                refPriceRub = refPx,
            )
            val record = TradeLegFillRecord(
                id = "${tradeId}_${phase.name}_${leg.ticker}_${leg.completedAtMillis}",
                tradeId = tradeId,
                phase = phase,
                ticker = leg.ticker,
                sideRu = leg.sideRu,
                requestedLots = parsed.requestedLots,
                executedLots = parsed.executedLots,
                fillStatus = parsed.fillStatus,
                fillPriceRub = parsed.executedPriceRub,
                fillTimeMillis = leg.completedAtMillis,
                orderId = parsed.orderId,
                commissionRub = parsed.commissionRub,
                refMoexPriceRub = refPx,
                refSpreadPercent = spreadRef,
                slippagePriceBps = slipBps,
                slippageSpreadPts = null,
                executionMode = executionMode,
                signalBarMillis = signalBarMillis,
                zScore = zScore,
                source = source,
            )
            appendRecord(app, record)
            logRecordToDiagnostics(app, record)
        }
        recomputeSpreadSlippageForTrade(app, tradeId, phase)
    }

    fun loadRecent(context: Context, limit: Int = MAX_RECORDS): List<TradeLegFillRecord> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return buildList {
            for (i in 0 until arr.length()) {
                parseRecord(arr.optJSONObject(i) ?: continue)?.let { add(it) }
            }
        }.takeLast(limit)
    }

    fun fillsForTrade(context: Context, tradeId: String): List<TradeLegFillRecord> =
        loadRecent(context).filter { it.tradeId == tradeId }

    fun formatLegFillSummary(record: TradeLegFillRecord?): String {
        if (record == null) return "—"
        val px = record.fillPriceRub?.let { "%.3f".format(Locale.US, it) } ?: "—"
        val lots = "${record.executedLots}/${record.requestedLots}"
        val slip = record.slippagePriceBps?.let { " slip ${"%.1f".format(Locale.US, it)} bps" }.orEmpty()
        val partial = if (record.isPartial) " · частично" else ""
        return "$px ₽ · $lots лот$slip$partial"
    }

    fun medianSlippageSpreadPts(context: Context, minSamples: Int = 3): Double? {
        val pts = loadRecent(context)
            .mapNotNull { it.slippageSpreadPts?.takeIf { v -> !v.isNaN() && abs(v) < 5.0 } }
        if (pts.size < minSamples) return null
        return pts.sorted()[pts.size / 2]
    }

    fun medianSlippagePriceBps(context: Context, minSamples: Int = 3): Double? {
        val bps = loadRecent(context)
            .mapNotNull { it.slippagePriceBps?.takeIf { v -> !v.isNaN() && abs(v) < 500.0 } }
        if (bps.size < minSamples) return null
        return bps.sorted()[bps.size / 2]
    }

    fun calibrationSummary(context: Context): String {
        val n = loadRecent(context).size
        val spreadSlip = medianSlippageSpreadPts(context)
        val bpsSlip = medianSlippagePriceBps(context)
        return buildString {
            append("лог $n ног")
            spreadSlip?.let { append(" · мед. slip спреда ${"%.3f".format(Locale.US, it)} п") }
            bpsSlip?.let { append(" · мед. slip ${"%.1f".format(Locale.US, it)} bps") }
        }
    }

    fun exportCsv(context: Context): String {
        val header = "tradeId,phase,ticker,side,reqLots,execLots,status,fillPrice,fillTimeMs,orderId," +
            "refMoex,refSpread,slipBps,slipSpreadPts,mode,source"
        val rows = loadRecent(context).map { r ->
            listOf(
                r.tradeId,
                r.phase.name,
                r.ticker,
                r.sideRu,
                r.requestedLots.toString(),
                r.executedLots.toString(),
                r.fillStatus.name,
                r.fillPriceRub?.let { "%.6f".format(Locale.US, it) }.orEmpty(),
                r.fillTimeMillis.toString(),
                r.orderId,
                r.refMoexPriceRub?.let { "%.4f".format(Locale.US, it) }.orEmpty(),
                r.refSpreadPercent?.let { "%.4f".format(Locale.US, it) }.orEmpty(),
                r.slippagePriceBps?.let { "%.2f".format(Locale.US, it) }.orEmpty(),
                r.slippageSpreadPts?.let { "%.4f".format(Locale.US, it) }.orEmpty(),
                r.executionMode.name,
                r.source,
            ).joinToString(",")
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    suspend fun backfillFromOperations(
        context: Context,
        fromMillis: Long,
        toMillis: Long,
    ): Int {
        if (currentExecutionMode(context) != TinkoffExecutionMode.Prod) return 0
        val mode = TinkoffExecutionMode.Prod
        val token = TinkoffSandboxStorage.getActiveToken(context, mode) ?: return 0
        val accountId = TinkoffSandboxStorage.getActiveAccountId(context, mode) ?: return 0
        val root = runCatching {
            tinkoffProdOperationsPostAsync(
                token,
                "GetOperations",
                JSONObject()
                    .put("accountId", accountId.trim())
                    .put("from", java.time.Instant.ofEpochMilli(fromMillis).toString())
                    .put("to", java.time.Instant.ofEpochMilli(toMillis).toString())
                    .put("state", "OPERATION_STATE_EXECUTED"),
            )
        }.getOrNull() ?: return 0
        val ops = collectOperationsForTradeLog(root)
        var added = 0
        for (op in ops) {
            val ticker = resolveOperationTicker(op) ?: continue
            if (ticker != "TATN" && ticker != "TATNP") continue
            val opMillis = parseOperationDateMillisForLog(op) ?: continue
            val price = parseOperationPriceRub(op) ?: continue
            val qty = parseOperationQuantityUnits(op)
            val tradeId = "OPS-${opMillis}-${ticker}"
            val record = TradeLegFillRecord(
                id = tradeId,
                tradeId = tradeId,
                phase = TradeExecPhase.Exit,
                ticker = ticker,
                sideRu = op.optString("type", "—"),
                requestedLots = qty,
                executedLots = qty,
                fillStatus = TradeLegFillStatus.Full,
                fillPriceRub = price,
                fillTimeMillis = opMillis,
                orderId = jsonFirstNonBlank(op, "id", "operationId").orEmpty(),
                commissionRub = abs(parseOperationMoneyFieldForLog(op, "commission") ?: 0.0),
                refMoexPriceRub = null,
                refSpreadPercent = null,
                slippagePriceBps = null,
                slippageSpreadPts = null,
                executionMode = mode,
                source = "GetOperations-backfill",
            )
            if (appendRecordIfNew(context.applicationContext, record)) added++
        }
        return added
    }

    private fun appendRecord(context: Context, record: TradeLegFillRecord) {
        appendRecordIfNew(context, record)
    }

    private fun appendRecordIfNew(context: Context, record: TradeLegFillRecord): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = loadRecent(context).toMutableList()
        if (list.any { it.id == record.id }) return false
        list += record
        prefs.edit()
            .putString(KEY_JSON, encodeRecords(list.takeLast(MAX_RECORDS)).toString())
            .apply()
        return true
    }

    private fun recomputeSpreadSlippageForTrade(app: Context, tradeId: String, phase: TradeExecPhase) {
        val records = fillsForTrade(app, tradeId).filter { it.phase == phase }
        val tatn = records.firstOrNull { it.ticker == "TATN" }
        val tatnp = records.firstOrNull { it.ticker == "TATNP" }
        val fillSpread = spreadPercentFromFillPrices(
            longPx = tatn?.fillPriceRub,
            shortPx = tatnp?.fillPriceRub,
            signalType = inferSignalTypeFromLegs(tatn, tatnp),
        ) ?: return
        val refSpread = records.firstNotNullOfOrNull { it.refSpreadPercent } ?: return
        val slipPts = fillSpread - refSpread
        val updated = records.map { it.copy(slippageSpreadPts = slipPts) }
        mergeUpdatedRecords(app, updated)
    }

    private fun inferSignalTypeFromLegs(tatn: TradeLegFillRecord?, tatnp: TradeLegFillRecord?): StrategySignalType {
        val tatnBuy = tatn?.sideRu?.contains("покуп", ignoreCase = true) == true
        return if (tatnBuy) StrategySignalType.EnterLong else StrategySignalType.EnterShort
    }

    private fun mergeUpdatedRecords(app: Context, updated: List<TradeLegFillRecord>) {
        if (updated.isEmpty()) return
        val byId = updated.associateBy { it.id }
        val merged = loadRecent(app).map { byId[it.id] ?: it }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, encodeRecords(merged.takeLast(MAX_RECORDS)).toString())
            .apply()
    }

    private fun logRecordToDiagnostics(context: Context, r: TradeLegFillRecord) {
        MoexDiagnostics.log(
            context,
            "exec",
            "${r.phase.name} ${r.tradeId} ${r.ticker} ${r.executedLots}/${r.requestedLots} " +
                "@${r.fillPriceRub?.let { "%.3f".format(Locale.US, it) } ?: "?"} " +
                "ref=${r.refMoexPriceRub?.let { "%.3f".format(Locale.US, it) } ?: "?"} " +
                "slip=${r.slippagePriceBps?.let { "%.1f".format(Locale.US, it) } ?: "?"}bps " +
                "spreadSlip=${r.slippageSpreadPts?.let { "%.3f".format(Locale.US, it) } ?: "?"}p " +
                r.fillStatus.name,
        )
    }

    private fun encodeRecords(records: List<TradeLegFillRecord>): JSONArray {
        val arr = JSONArray()
        records.forEach { arr.put(encodeRecord(it)) }
        return arr
    }

    private fun encodeRecord(r: TradeLegFillRecord): JSONObject =
        JSONObject()
            .put("id", r.id)
            .put("tradeId", r.tradeId)
            .put("phase", r.phase.name)
            .put("ticker", r.ticker)
            .put("sideRu", r.sideRu)
            .put("requestedLots", r.requestedLots)
            .put("executedLots", r.executedLots)
            .put("fillStatus", r.fillStatus.name)
            .put("fillPriceRub", r.fillPriceRub ?: JSONObject.NULL)
            .put("fillTimeMillis", r.fillTimeMillis)
            .put("orderId", r.orderId)
            .put("commissionRub", r.commissionRub ?: JSONObject.NULL)
            .put("refMoexPriceRub", r.refMoexPriceRub ?: JSONObject.NULL)
            .put("refSpreadPercent", r.refSpreadPercent ?: JSONObject.NULL)
            .put("slippagePriceBps", r.slippagePriceBps ?: JSONObject.NULL)
            .put("slippageSpreadPts", r.slippageSpreadPts ?: JSONObject.NULL)
            .put("executionMode", r.executionMode.name)
            .put("signalBarMillis", r.signalBarMillis)
            .put("zScore", r.zScore)
            .put("source", r.source)

    private fun parseRecord(o: JSONObject): TradeLegFillRecord? {
        val phase = runCatching { TradeExecPhase.valueOf(o.getString("phase")) }.getOrNull() ?: return null
        val mode = runCatching {
            TinkoffExecutionMode.valueOf(o.getString("executionMode"))
        }.getOrNull() ?: TinkoffExecutionMode.Sandbox
        val status = runCatching {
            TradeLegFillStatus.valueOf(o.getString("fillStatus"))
        }.getOrNull() ?: TradeLegFillStatus.Unknown
        return TradeLegFillRecord(
            id = o.optString("id"),
            tradeId = o.optString("tradeId"),
            phase = phase,
            ticker = o.optString("ticker"),
            sideRu = o.optString("sideRu"),
            requestedLots = o.optInt("requestedLots", 0),
            executedLots = o.optInt("executedLots", 0),
            fillStatus = status,
            fillPriceRub = o.optDouble("fillPriceRub").takeIf { !it.isNaN() && it > 0 },
            fillTimeMillis = o.optLong("fillTimeMillis"),
            orderId = o.optString("orderId"),
            commissionRub = o.optDouble("commissionRub").takeIf { !it.isNaN() && it >= 0 },
            refMoexPriceRub = o.optDouble("refMoexPriceRub").takeIf { !it.isNaN() && it > 0 },
            refSpreadPercent = o.optDouble("refSpreadPercent").takeIf { !it.isNaN() },
            slippagePriceBps = o.optDouble("slippagePriceBps").takeIf { !it.isNaN() },
            slippageSpreadPts = o.optDouble("slippageSpreadPts").takeIf { !it.isNaN() },
            executionMode = mode,
            signalBarMillis = o.optLong("signalBarMillis"),
            zScore = o.optDouble("zScore"),
            source = o.optString("source"),
        )
    }
}

internal data class ParsedPostOrderFill(
    val orderId: String,
    val requestedLots: Int,
    val executedLots: Int,
    val executedPriceRub: Double?,
    val commissionRub: Double?,
    val fillStatus: TradeLegFillStatus,
)

internal fun parsePostOrderFill(orderJson: JSONObject): ParsedPostOrderFill {
    val o = unwrapPostOrderResponse(orderJson)
    val orderId = jsonFirstNonBlank(o, "orderId", "order_id").orEmpty()
    val requested = o.optInt("lotsRequested", o.optInt("lots_requested", 0))
        .takeIf { it > 0 }
        ?: o.optJSONObject("lots")?.optInt("requested", 0)
        ?: 0
    val executed = o.optInt("lotsExecuted", o.optInt("lots_executed", 0))
        .takeIf { it > 0 }
        ?: o.optJSONObject("lots")?.optInt("executed", 0)
        ?: requested
    val priceObj = o.optJSONObject("executedOrderPrice")
        ?: o.optJSONObject("executed_order_price")
        ?: o.optJSONObject("initialOrderPrice")
        ?: o.optJSONObject("initial_order_price")
    val price = priceObj?.let { quotationUnitsToDouble(it) }
    val commObj = o.optJSONObject("executedCommission")
        ?: o.optJSONObject("executed_commission")
        ?: o.optJSONObject("initialCommission")
    val commission = commObj?.let { abs(quotationUnitsToDouble(it) ?: 0.0) }
    val st = jsonFirstNonBlank(
        o,
        "executionReportStatus",
        "execution_report_status",
        "orderState",
    ).orEmpty().uppercase(Locale.US)
    val status = when {
        st.contains("FILL") && !st.contains("PARTIAL") && executed >= requested && requested > 0 ->
            TradeLegFillStatus.Full
        st.contains("PARTIAL") || (executed in 1 until requested) ->
            TradeLegFillStatus.Partial
        st.contains("REJECT") || st.contains("CANCEL") ->
            TradeLegFillStatus.Reject
        executed > 0 -> TradeLegFillStatus.Full
        else -> TradeLegFillStatus.Unknown
    }
    return ParsedPostOrderFill(
        orderId = orderId,
        requestedLots = requested.coerceAtLeast(executed).coerceAtLeast(1),
        executedLots = executed.coerceAtLeast(0),
        executedPriceRub = price,
        commissionRub = commission,
        fillStatus = status,
    )
}

private fun unwrapPostOrderResponse(root: JSONObject): JSONObject {
    var o = root
    for (k in listOf(
        "postOrderResponse", "post_order_response",
        "postSandboxOrderResponse", "post_sandbox_order_response",
    )) {
        o.optJSONObject(k)?.let { o = it }
    }
    return o
}

internal fun computeLegSlippagePriceBps(
    sideRu: String,
    fillPriceRub: Double?,
    refPriceRub: Double?,
): Double? {
    if (fillPriceRub == null || refPriceRub == null || refPriceRub <= 0.0) return null
    val rawBps = (fillPriceRub - refPriceRub) / refPriceRub * 10_000.0
    val buy = sideRu.contains("покуп", ignoreCase = true)
    return if (buy) rawBps else -rawBps
}

internal fun spreadPercentFromLegRefs(tatn: Double?, tatnp: Double?): Double? {
    if (tatn == null || tatnp == null || tatnp <= 0.0) return null
    return (tatn / tatnp - 1.0) * 100.0
}

internal fun spreadPercentFromFillPrices(
    longPx: Double?,
    shortPx: Double?,
    signalType: StrategySignalType,
): Double? = when (signalType) {
    StrategySignalType.EnterLong -> spreadPercentFromLegRefs(longPx, shortPx)
    StrategySignalType.EnterShort -> spreadPercentFromLegRefs(shortPx, longPx)
    else -> null
}

private fun collectOperationsForTradeLog(root: JSONObject): List<JSONObject> {
    val out = mutableListOf<JSONObject>()
    fun walk(o: JSONObject?, depth: Int) {
        if (o == null || depth > 8) return
        val arr = o.optJSONArray("operations") ?: o.optJSONArray("Operations")
        if (arr != null) {
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

private fun parseOperationDateMillisForLog(op: JSONObject): Long? {
    val s = jsonFirstNonBlank(op, "date", "Date") ?: return null
    return runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
}

private fun parseOperationMoneyFieldForLog(op: JSONObject, vararg keys: String): Double? {
    for (k in keys) {
        op.optJSONObject(k)?.let { quotationUnitsToDouble(it) }?.let { return it }
    }
    return null
}

private fun parseOperationPriceRub(op: JSONObject): Double? {
    val payment = parseOperationMoneyFieldForLog(op, "payment", "price")
    val qty = parseOperationQuantityUnits(op).takeIf { it > 0 } ?: return payment
    return payment?.let { abs(it / qty) }
}

private fun parseOperationQuantityUnits(op: JSONObject): Int {
    val q = op.optJSONObject("quantity") ?: op.optJSONObject("quantityDone")
    return q?.let { quotationUnitsToDouble(it)?.toInt() } ?: op.optInt("quantity", 0)
}

private fun resolveOperationTicker(op: JSONObject): String? {
    val inst = op.optJSONObject("instrument") ?: op.optJSONObject("Instrument")
    return jsonFirstNonBlank(inst ?: op, "ticker", "Ticker")?.uppercase(Locale.US)
}

private fun jsonFirstNonBlank(o: JSONObject, vararg keys: String): String? {
    for (k in keys) {
        val v = o.optString(k, "").trim()
        if (v.isNotEmpty() && v != "null") return v
    }
    return null
}
