package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/** Парсит «9 918,76 ₽» / «9918.76 ₽» из строк UI портфеля. */
internal fun parseFormattedRubString(value: String?): Double? {
    if (value.isNullOrBlank()) return null
    val normalized = value
        .replace("₽", "")
        .replace("\u00A0", "")
        .replace(" ", "")
        .replace(",", ".")
        .trim()
    return normalized.toDoubleOrNull()
}

internal fun parsePortfolioTotalRubDouble(portfolioJson: JSONObject): Double? {
    val formatted = formatSandboxPortfolioTotalRub(portfolioJson) ?: return null
    return parseFormattedRubString(formatted)
}

internal data class ProdCloseRealizedCapture(
    val legPnl: SpreadLegBrokerPnl,
    val realizedNetRub: Double,
    val entryPortfolioCashRub: Double,
    val exitPortfolioCashRub: Double,
    val operationsCommissionRub: Double,
)

/**
 * Реализованный PnL закрытой сделки на Prod:
 * - чистый результат = Δ денег на счёте (как в T‑Invest);
 * - ноги = сумма yield из GetOperations за период сделки (не expectedYield MTM).
 */
internal suspend fun captureProdCloseRealizedPnl(
    context: Context,
    execution: SandboxSpreadExecUi,
    exitLegs: List<SandboxLegOrderResult>,
    exitTimestampMillis: Long,
    mtmBeforeClose: SpreadLegBrokerPnl? = null,
    /** Wall-clock момент закрытия — для GetOperations (не 15м бар). */
    exitExecutedAtMillis: Long = System.currentTimeMillis(),
): ProdCloseRealizedCapture? {
    if (currentExecutionMode(context) != TinkoffExecutionMode.Prod) return null
    val mode = TinkoffExecutionMode.Prod
    val token = TinkoffSandboxStorage.getActiveToken(context, mode) ?: return null
    val accountId = TinkoffSandboxStorage.getActiveAccountId(context, mode) ?: return null

    val exitCash = exitLegs.lastOrNull()?.portfolioCashRub?.let { parseFormattedRubString(it) }
        ?: runCatching {
            parsePortfolioCashRubDouble(tinkoffGetPortfolio(mode, token, accountId))
        }.getOrNull()
    val entryCash = execution.entryPortfolioCashRub.takeIf { it > 0.0 }
    val realizedNet = if (entryCash != null && exitCash != null) {
        exitCash - entryCash
    } else {
        null
    }

    val fromMillis = execution.executedAtMillis - 60_000L
    val closeWall = maxOf(exitExecutedAtMillis, execution.executedAtMillis + 1L)
    val toMillis = closeWall + 5 * 60_000L
    var opCapture = fetchSpreadRealizedLegPnlFromOperations(
        token = token,
        accountId = accountId,
        fromMillis = fromMillis,
        toMillis = toMillis,
        signalType = execution.signalType,
    )
    if (opCapture == null) {
        delay(1_500L)
        opCapture = fetchSpreadRealizedLegPnlFromOperations(
            token = token,
            accountId = accountId,
            fromMillis = fromMillis,
            toMillis = toMillis + 60_000L,
            signalType = execution.signalType,
        )
    }

    val legPnl = opCapture?.legPnl
        ?: scaleMtmLegsToRealizedNet(mtmBeforeClose, realizedNet)
        ?: mtmBeforeClose
        ?: SpreadLegBrokerPnl(0.0, 0.0)

    val netRub = realizedNet
        ?: (legPnl.longLegYieldRub + legPnl.shortLegYieldRub - (opCapture?.commissionRub ?: 0.0))

    return ProdCloseRealizedCapture(
        legPnl = legPnl,
        realizedNetRub = netRub,
        entryPortfolioCashRub = entryCash ?: 0.0,
        exitPortfolioCashRub = exitCash ?: 0.0,
        operationsCommissionRub = opCapture?.commissionRub ?: 0.0,
    )
}

internal data class OperationsLegCapture(
    val legPnl: SpreadLegBrokerPnl,
    val commissionRub: Double,
)

/** Сводка реализованного PnL по TATN/TATNP из GetOperations за окно (как в Tinkoff). */
internal data class ProdSpreadWindowPnlSummary(
    val netRub: Double,
    val grossYieldRub: Double,
    val commissionRub: Double,
    /** Число операций с yield (закрытые круги в UI Tinkoff). */
    val roundTripCount: Int,
    val fromMillis: Long,
    val toMillis: Long,
)

internal suspend fun fetchProdSpreadOperationsInWindow(
    context: Context,
    fromMillis: Long,
    toMillis: Long,
): List<JSONObject>? {
    if (currentExecutionMode(context) != TinkoffExecutionMode.Prod) return null
    val mode = TinkoffExecutionMode.Prod
    val token = TinkoffSandboxStorage.getActiveToken(context, mode) ?: return null
    val accountId = TinkoffSandboxStorage.getActiveAccountId(context, mode) ?: return null
    val root = runCatching {
        tinkoffProdOperationsPostAsync(
            token,
            "GetOperations",
            JSONObject()
                .put("accountId", accountId.trim())
                .put("from", Instant.ofEpochMilli(fromMillis).toString())
                .put("to", Instant.ofEpochMilli(toMillis).toString())
                .put("state", "OPERATION_STATE_EXECUTED"),
        )
    }.getOrNull() ?: return null
    return collectOperationsArray(root)
}

internal fun summarizeProdSpreadOperations(
    operations: List<JSONObject>,
    fromMillis: Long,
    toMillis: Long,
): ProdSpreadWindowPnlSummary {
    var grossYield = 0.0
    var commission = 0.0
    var roundTrips = 0
    for (op in operations) {
        val opMillis = parseOperationDateMillis(op) ?: continue
        if (opMillis < fromMillis || opMillis > toMillis) continue
        commission += operationCommissionRub(op)
        val ticker = resolveOperationTicker(op) ?: continue
        if (ticker != "TATN" && ticker != "TATNP") continue
        val yield = parseOperationMoneyField(op, "yield", "yield_value") ?: continue
        grossYield += yield
        roundTrips++
    }
    return ProdSpreadWindowPnlSummary(
        netRub = grossYield - commission,
        grossYieldRub = grossYield,
        commissionRub = commission,
        roundTripCount = roundTrips,
        fromMillis = fromMillis,
        toMillis = toMillis,
    )
}

internal fun buildClosedRowsFromProdOperationsWindow(
    operations: List<JSONObject>,
    fromMillis: Long,
    toMillis: Long,
): List<PortfolioConfirmedTradeTableRow> {
    val parsed = operations.mapNotNull { op ->
        val opMillis = parseOperationDateMillis(op) ?: return@mapNotNull null
        if (opMillis < fromMillis || opMillis > toMillis) return@mapNotNull null
        val ticker = resolveOperationTicker(op) ?: return@mapNotNull null
        if (ticker != "TATN" && ticker != "TATNP") return@mapNotNull null
        val yield = parseOperationMoneyField(op, "yield", "yield_value") ?: return@mapNotNull null
        val comm = operationCommissionRub(op)
        ParsedYieldOperation(
            millis = opMillis,
            ticker = ticker,
            yieldRub = yield,
            commissionRub = comm,
            quantity = parseOperationQuantityLots(op),
        )
    }.sortedByDescending { it.millis }
    return parsed.mapIndexed { index, op ->
        val timeMsk = formatPortfolioExecutionTableMsk(op.millis)
        val net = op.yieldRub - op.commissionRub
        PortfolioConfirmedTradeTableRow(
            tradeId = "T-B%03d".format(Locale.US, index + 1),
            tradeDisplayId = "—",
            directionLabel = "spread",
            entryTimeMsk = timeMsk,
            exitTimeMsk = timeMsk,
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "—",
            shortLegSideRu = "—",
            volumeText = spreadVolumeText(op.quantity.coerceAtLeast(1)),
            confirmLabel = "брокер",
            entryZ = 0.0,
            exitZ = 0.0,
            notificationIdsText = "—",
            legLongPnlSplitRubApprox = net / 2.0,
            legShortPnlSplitRubApprox = net / 2.0,
            grossPnlRubApprox = op.yieldRub,
            netPnlRubApprox = net,
            commissionRubApprox = op.commissionRub,
            overnightRubApprox = 0.0,
        )
    }
}

private data class ParsedYieldOperation(
    val millis: Long,
    val ticker: String,
    val yieldRub: Double,
    val commissionRub: Double,
    val quantity: Int,
)

private fun operationCommissionRub(op: JSONObject): Double {
    val direct = abs(parseOperationMoneyField(op, "commission") ?: 0.0)
    if (direct > 0.0) return direct
    val type = op.firstNonBlankString("operationType", "type", "operation_type").orEmpty().uppercase(Locale.US)
    if (type.contains("FEE") || type.contains("COMMISSION") || type.contains("SERVICE")) {
        return abs(parseOperationMoneyField(op, "payment") ?: 0.0)
    }
    return 0.0
}

private fun parseOperationQuantityLots(op: JSONObject): Int {
    val qty = op.optInt("quantity", op.optInt("quantityDone", 0))
    if (qty > 0) return qty
    val lots = op.optInt("quantityLots", op.optInt("lots", 0))
    if (lots > 0) return lots
    return op.optJSONObject("quantity")?.optInt("value", 0) ?: 0
}

private fun scaleMtmLegsToRealizedNet(
    mtm: SpreadLegBrokerPnl?,
    realizedNet: Double?,
): SpreadLegBrokerPnl? {
    if (mtm == null || realizedNet == null) return null
    val mtmNet = mtm.netGrossRub
    if (abs(mtmNet) < 0.01) {
        return SpreadLegBrokerPnl(realizedNet / 2.0, realizedNet / 2.0)
    }
    val scale = realizedNet / mtmNet
    return SpreadLegBrokerPnl(
        longLegYieldRub = mtm.longLegYieldRub * scale,
        shortLegYieldRub = mtm.shortLegYieldRub * scale,
    )
}

internal suspend fun fetchSpreadRealizedLegPnlFromOperations(
    token: String,
    accountId: String,
    fromMillis: Long,
    toMillis: Long,
    signalType: StrategySignalType,
): OperationsLegCapture? {
    val root = runCatching {
        tinkoffProdOperationsPostAsync(
            token,
            "GetOperations",
            JSONObject()
                .put("accountId", accountId.trim())
                .put("from", Instant.ofEpochMilli(fromMillis).toString())
                .put("to", Instant.ofEpochMilli(toMillis).toString())
                .put("state", "OPERATION_STATE_EXECUTED"),
        )
    }.getOrNull() ?: return null

    val operations = collectOperationsArray(root)
    if (operations.isEmpty()) return null

    var tatnYield = 0.0
    var tatnpYield = 0.0
    var commissionRub = 0.0
    var matched = 0

    for (op in operations) {
        val opMillis = parseOperationDateMillis(op) ?: continue
        if (opMillis < fromMillis || opMillis > toMillis) continue
        val ticker = resolveOperationTicker(op) ?: continue
        val yieldRub = parseOperationMoneyField(op, "yield", "yield_value") ?: continue
        when (ticker) {
            "TATN" -> {
                tatnYield += yieldRub
                matched++
            }
            "TATNP" -> {
                tatnpYield += yieldRub
                matched++
            }
        }
        commissionRub += abs(parseOperationMoneyField(op, "commission") ?: 0.0)
    }
    if (matched == 0) return null

    val legPnl = when (signalType) {
        StrategySignalType.EnterLong -> SpreadLegBrokerPnl(
            longLegYieldRub = tatnYield,
            shortLegYieldRub = tatnpYield,
        )
        StrategySignalType.EnterShort -> SpreadLegBrokerPnl(
            longLegYieldRub = tatnpYield,
            shortLegYieldRub = tatnYield,
        )
        else -> return null
    }
    return OperationsLegCapture(legPnl = legPnl, commissionRub = commissionRub)
}

private fun collectOperationsArray(root: JSONObject): List<JSONObject> {
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

private fun parseOperationDateMillis(op: JSONObject): Long? {
    val raw = op.firstNonBlankString("date", "Date", "datetime", "operationDate")
        ?: return null
    return runCatching { Instant.parse(raw.trim()).toEpochMilli() }.getOrNull()
}

private fun JSONObject.firstNonBlankString(vararg keys: String): String? {
    for (k in keys) {
        val v = optString(k, "").trim()
        if (v.isNotEmpty()) return v
    }
    return null
}

private fun resolveOperationTicker(op: JSONObject): String? {
    op.firstNonBlankString("ticker", "Ticker")?.uppercase(Locale.US)?.let { return it }
    val figi = op.firstNonBlankString("figi", "FIGI")?.uppercase(Locale.US).orEmpty()
    val uid = op.firstNonBlankString(
        "instrumentUid",
        "instrument_uid",
        "instrumentUid",
        "uid",
    )?.uppercase(Locale.US).orEmpty()
    val type = op.firstNonBlankString("operationType", "type", "operation_type").orEmpty()
    val desc = op.firstNonBlankString("description", "name").orEmpty().uppercase(Locale.US)
    return when {
        "TATNP" in figi || "TATNP" in uid || "TATNP_TQBR" in uid -> "TATNP"
        "TATN" in figi || "TATN" in uid || "TATN_TQBR" in uid -> "TATN"
        type.contains("TATNP") || desc.contains("TATNP") -> "TATNP"
        type.contains("TATN") || desc.contains("TATN") -> "TATN"
        desc.contains("ПРИВИЛЕГИР") -> "TATNP"
        desc.contains("ТАТНЕФТЬ") || desc.contains("TATNEFT") -> {
            if (desc.contains("ПРИВИЛЕГИР")) "TATNP" else "TATN"
        }
        else -> null
    }
}

private fun parseOperationMoneyField(op: JSONObject, vararg keys: String): Double? {
    for (k in keys) {
        op.optJSONObject(k)?.let { return quotationUnitsToDouble(it) }
    }
    return null
}

internal suspend fun recordProdClosedTradeAfterExit(
    context: Context,
    execution: SandboxSpreadExecUi,
    exitLegs: List<SandboxLegOrderResult>,
    exitTimestampMillis: Long,
    exitZScore: Double,
    mtmBeforeClose: SpreadLegBrokerPnl?,
    exitExecutedAtMillis: Long = System.currentTimeMillis(),
): ProdClosedSpreadExecRecord? {
    if (currentExecutionMode(context) != TinkoffExecutionMode.Prod) return null
    val capture = captureProdCloseRealizedPnl(
        context = context,
        execution = execution,
        exitLegs = exitLegs,
        exitTimestampMillis = exitTimestampMillis,
        mtmBeforeClose = mtmBeforeClose,
        exitExecutedAtMillis = exitExecutedAtMillis,
    ) ?: return null
    val exitWall = maxOf(exitExecutedAtMillis, execution.executedAtMillis + 1L)
    return TinkoffClosedSpreadExecLog.recordClose(
        context = context,
        execution = execution,
        brokerPnl = capture.legPnl,
        exitTimestampMillis = exitWall,
        exitZScore = exitZScore,
        realizedNetRub = capture.realizedNetRub,
        entryPortfolioCashRub = capture.entryPortfolioCashRub,
        exitPortfolioCashRub = capture.exitPortfolioCashRub,
        operationsCommissionRub = capture.operationsCommissionRub,
    )
}
