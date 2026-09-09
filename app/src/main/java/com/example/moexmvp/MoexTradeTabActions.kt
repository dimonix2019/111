package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

/** Строка таблицы «Сделки за 2 недели» на вкладке «Сделка». */
internal data class TradeTabClosedTrade(
    val id: String,
    val directionLabel: String,
    val entryTimeMsk: String,
    val exitTimeMsk: String,
    val quantityLots: Int,
    val entrySpreadPercent: Double?,
    val exitSpreadPercent: Double?,
    val pnlRub: Double?,
    val sourceLabel: String,
)

internal const val TRADE_TAB_HISTORY_DAYS = 14L

/**
 * Журнал закрытых сделок для вкладки «Сделка».
 * Сначала web-стол (там AUTO + ручные + подхваченные с брокера), иначе локальный журнал приложения.
 */
internal suspend fun loadTradeTabClosedTrades(
    context: Context,
    days: Long = TRADE_TAB_HISTORY_DAYS,
): Pair<List<TradeTabClosedTrade>, String> = withContext(Dispatchers.IO) {
    val cutoffDate = LocalDate.now(moexZoneId).minusDays(days)
    val cutoffLabel = cutoffDate.format(tradeDateFormatter)

    // 1) Счёт Т-Инвест (GetOperations) — работает без ПК, точные деньги.
    val fromBroker = runCatching { fetchSpreadTradesFromTInvest(context, days) }.getOrNull()
    if (!fromBroker.isNullOrEmpty()) {
        return@withContext fromBroker to "счёт Т-Инвест"
    }

    // 2) Web-стол (там AUTO + ручные + подхваченные с брокера, с комментариями).
    val web = WebDeskApi.fetchClosedTrades(context).getOrNull()
    if (web != null) {
        val rows = web.mapNotNull { t ->
            val exitLabel = t.exitTime?.take(16).orEmpty()
            val entryLabel = t.entryTime?.take(16).orEmpty()
            val datePart = (exitLabel.ifBlank { entryLabel }).take(10)
            if (datePart.isBlank() || datePart < cutoffLabel) return@mapNotNull null
            TradeTabClosedTrade(
                id = "#${t.id}",
                directionLabel = if (t.direction.equals("SHORT", true)) "Short" else "Long",
                entryTimeMsk = entryLabel,
                exitTimeMsk = exitLabel,
                quantityLots = t.quantityLots,
                entrySpreadPercent = t.entrySpread,
                exitSpreadPercent = t.exitSpread,
                pnlRub = t.pnlRub,
                sourceLabel = t.source?.uppercase(Locale.US) ?: "",
            )
        }.sortedByDescending { it.exitTimeMsk.ifBlank { it.entryTimeMsk } }
        return@withContext rows to "web-стол"
    }

    val cutoffMs = cutoffDate.atStartOfDay(moexZoneId).toInstant().toEpochMilli()
    val local = TinkoffClosedSpreadExecLog.loadRecent(context)
        .filter { it.exitTimestampMillis >= cutoffMs || it.closedAtMillis >= cutoffMs }
        .map { r ->
            TradeTabClosedTrade(
                id = r.tradeId,
                directionLabel = if (r.signalType == StrategySignalType.EnterShort) "Short" else "Long",
                entryTimeMsk = r.entryTimeMsk,
                exitTimeMsk = formatPortfolioExecutionTableMsk(r.exitTimestampMillis),
                quantityLots = r.quantityLots,
                entrySpreadPercent = r.entrySpreadPercent.takeIf { it.isFinite() },
                exitSpreadPercent = null,
                pnlRub = r.realizedNetRub ?: (r.longLegYieldRub + r.shortLegYieldRub),
                sourceLabel = if (r.source == PortfolioExecSource.AUTO) "AUTO" else "ручное",
            )
        }
        .sortedByDescending { it.exitTimeMsk }
    local to "локальный журнал"
}

/**
 * Кнопки «Открыть Long/Short» на вкладке «Сделка»: две рыночные заявки на боевой счёт
 * с расчётом лотов как у AUTO, запись в журналы приложения (источник MANUAL).
 * Web-монитор подхватит позицию с брокера своей сверкой.
 */
internal suspend fun runManualSpreadEntryFromTradeTab(
    context: Context,
    signalType: StrategySignalType,
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        require(
            signalType == StrategySignalType.EnterLong || signalType == StrategySignalType.EnterShort
        ) { "Только EnterLong / EnterShort" }
        val app = context.applicationContext
        val mode = currentExecutionMode(app)
        require(mode == TinkoffExecutionMode.Prod) {
            "Вкладка «Сделка» работает с боевым счётом. Сейчас: ${executionModeLabelRu(mode)}."
        }
        val token = TinkoffSandboxStorage.getActiveToken(app, mode)
            ?: throw IOException("Нет токена (${executionModeLabelRu(mode)}).")
        val accountId = TinkoffSandboxStorage.getActiveAccountId(app, mode)
            ?: throw IOException("Нет счёта (${executionModeLabelRu(mode)}).")

        // Не переворачиваем существующую позицию: вход только из FLAT.
        val portfolio = tinkoffGetPortfolio(mode, token, accountId)
        val broker = detectBrokerSpreadPosition(portfolio)
        require(broker.side == ZStrategyPosition.Flat) {
            "На брокере уже есть позиция (${broker.side}) — сначала закройте её."
        }

        val market = loadCurrentPortfolioMarketSnapshot(app, forTestEntry = true)
        val dir = if (signalType == StrategySignalType.EnterShort) "SHORT" else "LONG"

        val entry = executeSpreadEntryDetailedForConfiguredMode(app, signalType)
        val legs = entry.legs
        val sizing = entry.sizing
        val executedAt = System.currentTimeMillis()

        recordStrategySignalEvent(
            context = app,
            signalType = signalType,
            zScore = market.zScore,
            timestampMillis = market.timestampMillis,
            skipJournalWallDedup = true,
            savePendingVirtualTradeIfEntry = false,
        )
        saveStrategyPosition(
            app,
            if (signalType == StrategySignalType.EnterShort) ZStrategyPosition.Short else ZStrategyPosition.Long,
        )
        appendPortfolioExecutionLedger(
            app,
            barTimestampMillis = market.timestampMillis,
            signalType = signalType,
            source = PortfolioExecSource.MANUAL,
        )
        val execution = TinkoffSandboxSpreadExecLog.recordFromLegs(
            app,
            signalType,
            market.zScore,
            barTimestampMillis = market.timestampMillis,
            executedAtMillis = executedAt,
            entrySpreadPercent = market.entrySpreadPercent,
            source = PortfolioExecSource.MANUAL,
            legs = legs,
            fromTestButton = false,
            quantityLots = sizing.quantityLots,
            executionNotionalRub = sizing.executionNotionalRub,
        )
        execution?.let { opened ->
            TradeExecutionLog.recordSpreadLegFills(
                context = app,
                tradeId = opened.tradeId,
                phase = TradeExecPhase.Entry,
                legs = legs,
                executionMode = mode,
                signalBarMillis = market.timestampMillis,
                zScore = market.zScore,
                refTatnPriceRub = sizing.priceTatN,
                refTatnpPriceRub = sizing.priceTatNp,
                refSpreadPercent = market.entrySpreadPercent,
                source = "manual_entry_trade_tab",
            )
            notifySandboxTradeOpened(
                context = app,
                execution = opened,
                notionalRub = sizing.executionNotionalRub,
                leverage = TinkoffSandboxStorage.getSandboxNotifyLeverage(app),
                portfolioTotalRub = legs.lastOrNull()?.portfolioTotalRub,
                portfolioCashRub = legs.lastOrNull()?.portfolioCashRub,
            )
        }
        "Открыт $dir ${sizing.quantityLots}+${sizing.quantityLots} лот (спред ${formatSpreadPct(market.entrySpreadPercent)})"
    }
}

private fun formatSpreadPct(v: Double): String =
    if (v.isFinite()) String.format(Locale.US, "%.2f%%", v) else "—"

// --- История сделок со счёта Т-Инвест (GetOperations) ---

internal data class TInvestSpreadOp(
    val millis: Long,
    val ticker: String,
    val signedQty: Double,
    val priceRub: Double?,
    val paymentRub: Double,
)
internal class SpreadEpisode {
    var entryMs: Long = 0L
    var exitMs: Long = 0L
    var tatnQty: Double = 0.0
    var tatnpQty: Double = 0.0
    var peakLots: Double = 0.0
    var cashRub: Double = 0.0
    // VWAP открывающих и закрывающих операций по каждому тикеру.
    var openCostN: Double = 0.0; var openQtyN: Double = 0.0
    var openCostP: Double = 0.0; var openQtyP: Double = 0.0
    var closeCostN: Double = 0.0; var closeQtyN: Double = 0.0
    var closeCostP: Double = 0.0; var closeQtyP: Double = 0.0

    fun isFlat(): Boolean = kotlin.math.abs(tatnQty) < 1e-9 && kotlin.math.abs(tatnpQty) < 1e-9

    fun add(op: TInvestSpreadOp) {
        if (entryMs == 0L) entryMs = op.millis
        exitMs = op.millis
        cashRub += op.paymentRub
        val prevQty = if (op.ticker == "TATN") tatnQty else tatnpQty
        val newQty = prevQty + op.signedQty
        val opening = kotlin.math.abs(newQty) > kotlin.math.abs(prevQty)
        val px = op.priceRub
        if (px != null && px > 0) {
            val q = kotlin.math.abs(op.signedQty)
            if (op.ticker == "TATN") {
                if (opening) { openCostN += px * q; openQtyN += q } else { closeCostN += px * q; closeQtyN += q }
            } else {
                if (opening) { openCostP += px * q; openQtyP += q } else { closeCostP += px * q; closeQtyP += q }
            }
        }
        if (op.ticker == "TATN") tatnQty = newQty else tatnpQty = newQty
        peakLots = maxOf(peakLots, kotlin.math.abs(tatnQty), kotlin.math.abs(tatnpQty))
    }

    fun openVwap(ticker: String): Double? {
        val c = if (ticker == "TATN") openCostN else openCostP
        val q = if (ticker == "TATN") openQtyN else openQtyP
        return if (q > 0) c / q else null
    }

    fun closeVwap(ticker: String): Double? {
        val c = if (ticker == "TATN") closeCostN else closeCostP
        val q = if (ticker == "TATN") closeQtyN else closeQtyP
        return if (q > 0) c / q else null
    }
}

private fun spreadPctFromPrices(tatn: Double?, tatnp: Double?): Double? {
    if (tatn == null || tatnp == null || tatnp <= 0.0) return null
    return (tatn / tatnp - 1.0) * 100.0
}

/**
 * Обход операций: эпизод = от ухода из нуля до возврата в ноль по обоим тикерам.
 * Комиссии/овернайт между входом и выходом добавляются в cash эпизода.
 */
internal fun walkSpreadEpisodes(
    ops: List<TInvestSpreadOp>,
    feePayments: List<Pair<Long, Double>>,
): List<SpreadEpisode> {
    fun feesBetween(from: Long, to: Long): Double =
        feePayments.filter { it.first in from..to }.sumOf { it.second }
    val episodes = mutableListOf<SpreadEpisode>()
    var cur: SpreadEpisode? = null
    var curTatn = 0.0
    var curTatnp = 0.0
    for (op in ops) {
        val flat = kotlin.math.abs(curTatn) < 1e-9 && kotlin.math.abs(curTatnp) < 1e-9
        if (flat && cur == null) {
            cur = SpreadEpisode()
        }
        val ep = cur ?: continue
        ep.add(op)
        if (op.ticker == "TATN") curTatn += op.signedQty else curTatnp += op.signedQty
        if (kotlin.math.abs(curTatn) < 1e-9 && kotlin.math.abs(curTatnp) < 1e-9) {
            ep.cashRub += feesBetween(ep.entryMs - 60_000L, ep.exitMs + 5 * 60_000L)
            episodes += ep
            cur = null
        }
    }
    return episodes
}

/**
 * Закрытые пары TATN/TATNP за [days] дней из операций счёта Т-Инвест:
 * эпизод = от ухода из нуля до возврата в ноль по обоим тикерам;
 * PnL = сумма платежей по операциям эпизода (покупки −, продажи +, комиссии −).
 */
internal suspend fun fetchSpreadTradesFromTInvest(
    context: Context,
    days: Long = TRADE_TAB_HISTORY_DAYS,
): List<TradeTabClosedTrade>? = withContext(Dispatchers.IO) {
    val mode = TinkoffExecutionMode.Prod
    val token = TinkoffSandboxStorage.getActiveToken(context, mode) ?: return@withContext null
    val accountId = TinkoffSandboxStorage.getActiveAccountId(context, mode) ?: return@withContext null
    val toMs = System.currentTimeMillis()
    val fromMs = toMs - days * 86_400_000L
    val root = runCatching {
        tinkoffProdOperationsPostAsync(
            token,
            "GetOperations",
            org.json.JSONObject()
                .put("accountId", accountId.trim())
                .put("from", java.time.Instant.ofEpochMilli(fromMs).toString())
                .put("to", java.time.Instant.ofEpochMilli(toMs).toString())
                .put("state", "OPERATION_STATE_EXECUTED"),
        )
    }.getOrNull() ?: return@withContext null

    val ops = mutableListOf<TInvestSpreadOp>()
    val feePayments = mutableListOf<Pair<Long, Double>>()
    for (op in collectOperationsArray(root)) {
        val ms = parseOperationDateMillis(op) ?: continue
        val type = op.optString("operationType", op.optString("type", "")).uppercase(Locale.US)
        val payment = parseOperationMoneyField(op, "payment") ?: 0.0
        if ("FEE" in type || "COMMISSION" in type || "OVERNIGHT" in type || "MARGIN" in type) {
            if (payment != 0.0) feePayments += ms to payment
            continue
        }
        val isBuy = "BUY" in type
        val isSell = "SELL" in type || ("SALE" in type)
        if (!isBuy && !isSell) continue
        val ticker = resolveOperationTicker(op) ?: continue
        val qty = op.optDouble("quantity", 0.0).let { if (it > 0) it else op.optDouble("lots", 0.0) }
        if (qty <= 0.0) continue
        ops += TInvestSpreadOp(
            millis = ms,
            ticker = ticker,
            signedQty = if (isBuy) qty else -qty,
            priceRub = parseOperationMoneyField(op, "price"),
            paymentRub = payment,
        )
    }
    if (ops.isEmpty()) return@withContext null
    ops.sortBy { it.millis }
    feePayments.sortBy { it.first }

    val episodes = walkSpreadEpisodes(ops, feePayments)
    if (episodes.isEmpty()) return@withContext null

    episodes.mapIndexedNotNull { idx, ep ->
        // Направление: лонг = в эпизоде покупали TATN (openQtyN — объём открытия TATN).
        val isLong = ep.openQtyN >= ep.openQtyP
        val dirLabel = if (isLong) "Long" else "Short"
        val entrySpread = spreadPctFromPrices(ep.openVwap("TATN"), ep.openVwap("TATNP"))
        val exitSpread = spreadPctFromPrices(ep.closeVwap("TATN"), ep.closeVwap("TATNP"))
        val lots = ep.peakLots.toInt().coerceAtLeast(1)
        if (lots < 1) return@mapIndexedNotNull null
        TradeTabClosedTrade(
            id = "Т${idx + 1}",
            directionLabel = dirLabel,
            entryTimeMsk = formatPortfolioExecutionTableMsk(ep.entryMs),
            exitTimeMsk = formatPortfolioExecutionTableMsk(ep.exitMs),
            quantityLots = lots,
            entrySpreadPercent = entrySpread,
            exitSpreadPercent = exitSpread,
            pnlRub = ep.cashRub,
            sourceLabel = "",
        )
    }.sortedByDescending { it.exitTimeMsk }
}
