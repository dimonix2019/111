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
    /** Число операций TATN/TATNP + комиссий за период. */
    val roundTripCount: Int,
    val fromMillis: Long,
    val toMillis: Long,
    /** Откуда взята цифра: движение денег payment или расчёт yield. */
    val source: ProdSpreadWindowPnlSource = ProdSpreadWindowPnlSource.AccountPayments,
)

internal enum class ProdSpreadWindowPnlSource {
    /** Σ payment по операциям счёta (прямо из Tinkoff). */
    AccountPayments,
    /** Σ yield − комиссии (fallback). */
    YieldMinusCommission,
    /** Δ totalAmountPortfolio GetPortfolio с сохранённого снимка. */
    PortfolioTotalDelta,
}

internal fun parsePortfolioTotalRubDouble(portfolioJson: JSONObject): Double? {
    val formatted = formatSandboxPortfolioTotalRub(portfolioJson) ?: return null
    return parseFormattedRubString(formatted)
}

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
): ProdSpreadWindowPnlSummary =
    summarizeProdSpreadAccountCashFlow(operations, fromMillis, toMillis)
        ?: summarizeProdSpreadYieldFallback(operations, fromMillis, toMillis)

/**
 * PnL за период = Σ payment по операциям TATN/TATNP и комиссиям на счёте.
 * Это фактическое движение денег в Tinkoff, без расчёта спреда в приложении.
 */
internal fun summarizeProdSpreadAccountCashFlow(
    operations: List<JSONObject>,
    fromMillis: Long,
    toMillis: Long,
): ProdSpreadWindowPnlSummary? {
    var netPayment = 0.0
    var commissionAbs = 0.0
    var opCount = 0
    var hasExplicitPayment = false
    for (op in operations) {
        val opMillis = parseOperationDateMillis(op) ?: continue
        if (opMillis < fromMillis || opMillis > toMillis) continue
        if (isNonTradingPortfolioOperation(op)) continue
        if (!isSpreadAccountOperation(op)) continue
        val payment = parseOperationPaymentRub(op)
        if (payment != null) {
            hasExplicitPayment = true
            netPayment += payment
        } else if (!isFeeOnlySpreadOperation(op)) {
            continue
        }
        val comm = operationCommissionRub(op)
        if (comm > 0.0) commissionAbs += comm
        opCount++
    }
    if (!hasExplicitPayment || opCount == 0) return null
    return ProdSpreadWindowPnlSummary(
        netRub = netPayment,
        grossYieldRub = netPayment,
        commissionRub = commissionAbs,
        roundTripCount = opCount,
        fromMillis = fromMillis,
        toMillis = toMillis,
        source = ProdSpreadWindowPnlSource.AccountPayments,
    )
}

private fun summarizeProdSpreadYieldFallback(
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
        source = ProdSpreadWindowPnlSource.YieldMinusCommission,
    )
}

internal fun buildClosedRowsFromProdOperationsWindow(
    operations: List<JSONObject>,
    fromMillis: Long,
    toMillis: Long,
    accountSummary: ProdSpreadWindowPnlSummary? = null,
): List<PortfolioConfirmedTradeTableRow> {
    val parsed = operations.mapNotNull { op ->
        val opMillis = parseOperationDateMillis(op) ?: return@mapNotNull null
        if (opMillis < fromMillis || opMillis > toMillis) return@mapNotNull null
        if (isNonTradingPortfolioOperation(op)) return@mapNotNull null
        if (!isSpreadAccountOperation(op)) return@mapNotNull null
        val payment = parseOperationPaymentRub(op)
        val yield = parseOperationMoneyField(op, "yield", "yield_value")
        if (yield == null && payment == null && !isFeeOnlySpreadOperation(op)) return@mapNotNull null
        val comm = operationCommissionRub(op)
        val qty = parseOperationQuantityUnits(op)
        ParsedAccountOperation(
            millis = opMillis,
            ticker = resolveOperationTicker(op),
            paymentRub = payment ?: 0.0,
            yieldRub = yield,
            commissionRub = comm,
            quantityUnits = qty,
            sideRu = parseOperationSideRu(op),
            tradeNotionalRub = parseOperationTradeNotionalRub(op, qty),
            isFeeOnly = resolveOperationTicker(op) == null,
        )
    }.sortedByDescending { it.millis }
    val rows = parsed.mapIndexed { index, op ->
        buildProdAccountOperationRow(index + 1, op)
    }
    return ensureProdBrokerSummaryRow(rows, accountSummary)
}

/** Одна строка «Σ счёт», если операции есть в сводке, но детализация пуста. */
internal fun ensureProdBrokerSummaryRow(
    rows: List<PortfolioConfirmedTradeTableRow>,
    summary: ProdSpreadWindowPnlSummary?,
): List<PortfolioConfirmedTradeTableRow> {
    if (summary == null || rows.isNotEmpty()) return rows
    return listOf(buildProdAccountSummaryRow(summary))
}

internal fun prodSpreadPnlSourceLabel(source: ProdSpreadWindowPnlSource): String = when (source) {
    ProdSpreadWindowPnlSource.AccountPayments -> "счёт Tinkoff"
    ProdSpreadWindowPnlSource.YieldMinusCommission -> "Tinkoff (yield)"
    ProdSpreadWindowPnlSource.PortfolioTotalDelta -> "счёт Tinkoff Δ"
}

private fun buildProdAccountSummaryRow(summary: ProdSpreadWindowPnlSummary): PortfolioConfirmedTradeTableRow {
    val timeMsk = formatPortfolioExecutionTableMsk(summary.toMillis)
    return PortfolioConfirmedTradeTableRow(
        tradeId = "T-B001",
        tradeDisplayId = "Σ",
        directionLabel = "spread",
        entryTimeMsk = timeMsk,
        exitTimeMsk = timeMsk,
        longLegTicker = "TATN",
        shortLegTicker = "TATNP",
        longLegSideRu = "—",
        shortLegSideRu = "—",
        volumeText = "${summary.roundTripCount} оп.",
        confirmLabel = "счёт",
        entryZ = 0.0,
        exitZ = 0.0,
        notificationIdsText = "—",
        legLongPnlSplitRubApprox = summary.netRub / 2.0,
        legShortPnlSplitRubApprox = summary.netRub / 2.0,
        grossPnlRubApprox = summary.netRub,
        netPnlRubApprox = summary.netRub,
        commissionRubApprox = summary.commissionRub,
        overnightRubApprox = 0.0,
    )
}

private fun buildProdAccountOperationRow(
    index: Int,
    op: ParsedAccountOperation,
): PortfolioConfirmedTradeTableRow {
    val timeMsk = formatPortfolioExecutionTableMsk(op.millis)
    val ticker = op.ticker ?: "—"
    val resultRub = op.yieldRub ?: op.paymentRub
    val sideText = formatBrokerOperationLegText(op.sideRu, op.quantityUnits, op.tradeNotionalRub)
    val (longSide, shortSide) = when (ticker) {
        "TATN" -> sideText to "—"
        "TATNP" -> "—" to sideText
        else -> sideText to "—"
    }
    val (longPnl, shortPnl) = when (ticker) {
        "TATN" -> resultRub to Double.NaN
        "TATNP" -> Double.NaN to resultRub
        else -> resultRub / 2.0 to resultRub / 2.0
    }
    return PortfolioConfirmedTradeTableRow(
        tradeId = "T-B%03d".format(Locale.US, index),
        tradeDisplayId = "—",
        directionLabel = if (op.isFeeOnly) "комиссия" else op.sideRu,
        entryTimeMsk = timeMsk,
        exitTimeMsk = timeMsk,
        longLegTicker = if (ticker == "TATNP") "TATN" else ticker,
        shortLegTicker = if (ticker == "TATN") "TATNP" else ticker,
        longLegSideRu = longSide,
        shortLegSideRu = shortSide,
        volumeText = formatBrokerOperationQuantityText(op.quantityUnits),
        confirmLabel = "брокер",
        entryZ = 0.0,
        exitZ = 0.0,
        notificationIdsText = "—",
        legLongPnlSplitRubApprox = longPnl,
        legShortPnlSplitRubApprox = shortPnl,
        grossPnlRubApprox = resultRub + op.commissionRub,
        netPnlRubApprox = resultRub,
        commissionRubApprox = op.commissionRub,
        overnightRubApprox = 0.0,
    )
}

internal fun formatBrokerOperationQuantityText(quantityUnits: Int): String =
    if (quantityUnits > 0) "$quantityUnits ак." else "—"

internal fun formatBrokerOperationLegText(
    sideRu: String,
    quantityUnits: Int,
    tradeNotionalRub: Double,
): String {
    val qtyPart = if (quantityUnits > 0) "$quantityUnits ак." else "—"
    val sumPart = if (tradeNotionalRub > 0.0) formatRubSigned(tradeNotionalRub) else "—"
    return "$sideRu · $qtyPart · $sumPart"
}

private data class ParsedAccountOperation(
    val millis: Long,
    val ticker: String?,
    val paymentRub: Double,
    val yieldRub: Double?,
    val commissionRub: Double,
    val quantityUnits: Int,
    val sideRu: String,
    val tradeNotionalRub: Double,
    val isFeeOnly: Boolean,
)

private fun parseOperationPaymentRub(op: JSONObject): Double? =
    parseOperationMoneyField(op, "payment", "payment_value")

private fun isNonTradingPortfolioOperation(op: JSONObject): Boolean {
    val type = op.firstNonBlankString("operationType", "type", "operation_type").orEmpty().uppercase(Locale.US)
    return when {
        type.contains("PAY_IN") || type.contains("PAY_OUT") -> true
        type.contains("INPUT") || type.contains("OUTPUT") -> true
        type.contains("TAX") -> true
        type.contains("DIVIDEND") || type.contains("DIV") -> true
        type.contains("COUPON") -> true
        type.contains("TRANSFER") && !type.contains("FEE") -> true
        else -> false
    }
}

private fun isSpreadAccountOperation(op: JSONObject): Boolean {
    val type = op.firstNonBlankString("operationType", "type", "operation_type").orEmpty().uppercase(Locale.US)
    if (type.contains("FEE") || type.contains("COMMISSION") || type.contains("SERVICE")) return true
    val ticker = resolveOperationTicker(op) ?: return false
    return ticker == "TATN" || ticker == "TATNP"
}

private fun isFeeOnlySpreadOperation(op: JSONObject): Boolean {
    val type = op.firstNonBlankString("operationType", "type", "operation_type").orEmpty().uppercase(Locale.US)
    return type.contains("FEE") || type.contains("COMMISSION") || type.contains("SERVICE")
}

private fun operationCommissionRub(op: JSONObject): Double {
    val direct = abs(parseOperationMoneyField(op, "commission") ?: 0.0)
    if (direct > 0.0) return direct
    val type = op.firstNonBlankString("operationType", "type", "operation_type").orEmpty().uppercase(Locale.US)
    if (type.contains("FEE") || type.contains("COMMISSION") || type.contains("SERVICE")) {
        return abs(parseOperationMoneyField(op, "payment") ?: 0.0)
    }
    return 0.0
}

private fun parseOperationQuantityUnits(op: JSONObject): Int {
    val qObj = op.optJSONObject("quantity")
        ?: op.optJSONObject("quantityDone")
        ?: op.optJSONObject("quantityLots")
    qObj?.let { quotationUnitsToDouble(it)?.toInt()?.takeIf { it > 0 }?.let { return it } }
    val qty = op.optInt("quantity", op.optInt("quantityDone", 0))
    if (qty > 0) return qty
    return op.optInt("quantityLots", op.optInt("lots", 0))
}

private fun parseOperationSideRu(op: JSONObject): String {
    val type = op.firstNonBlankString("operationType", "type", "operation_type").orEmpty().uppercase(Locale.US)
    return when {
        type.contains("BUY") -> "покупка"
        type.contains("SELL") -> "продажа"
        else -> {
            val payment = parseOperationPaymentRub(op) ?: 0.0
            when {
                payment < -0.01 -> "покупка"
                payment > 0.01 -> "продажа"
                else -> "—"
            }
        }
    }
}

private fun parseOperationTradeNotionalRub(op: JSONObject, quantityUnits: Int): Double {
    parseOperationMoneyField(op, "price", "Price")?.let { price ->
        if (quantityUnits > 0) return abs(price * quantityUnits)
    }
    parseOperationPaymentRub(op)?.let { payment ->
        return abs(payment)
    }
    return 0.0
}

private fun parseOperationQuantityLots(op: JSONObject): Int = parseOperationQuantityUnits(op)

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
