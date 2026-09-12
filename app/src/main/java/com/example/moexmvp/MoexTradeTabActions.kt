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
 * Кнопки «Открыть Long/Short» и экстренное закрытие на вкладке «Сделка».
 */
internal data class SpreadFlattenLeg(
    val ticker: String,
    val buy: Boolean,
    val lots: Int,
) {
    val dirRu: String get() = if (buy) "покупка" else "продажа"
}

/** Обратные заявки по фактическим лотам TATN/TATNP, включая одну «хвостовую» ногу. */
internal fun flattenLegsForBrokerSnap(snap: BrokerSpreadPositionSnap): List<SpreadFlattenLeg> {
    val out = mutableListOf<SpreadFlattenLeg>()
    if (snap.tatnLots != 0) {
        out += SpreadFlattenLeg(
            ticker = "TATN",
            buy = snap.tatnLots < 0,
            lots = kotlin.math.abs(snap.tatnLots),
        )
    }
    if (snap.tatnpLots != 0) {
        out += SpreadFlattenLeg(
            ticker = "TATNP",
            buy = snap.tatnpLots < 0,
            lots = kotlin.math.abs(snap.tatnpLots),
        )
    }
    return out
}

internal fun tradeTabManualEntryBlockReason(broker: BrokerSpreadPositionSnap): String? =
    if (broker.hasBrokerLegs) {
        "На брокере уже есть TATN ${broker.tatnLots} / TATNP ${broker.tatnpLots} — сначала экстренное закрытие."
    } else {
        null
    }

/**
 * Экстренное закрытие пары: рыночные заявки по лотам GetPortfolio, без флага
 * «исполнять сигналы на песочнице» и без зависимости от локального журнала входов.
 */
internal suspend fun runEmergencyFlattenFromTradeTab(
    context: Context,
    mode: TinkoffExecutionMode = TinkoffExecutionMode.Prod,
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val app = context.applicationContext
        val token = TinkoffSandboxStorage.getActiveToken(app, mode)
            ?: throw IOException("Нет токена (${executionModeLabelRu(mode)}).")
        val accountId = TinkoffSandboxStorage.getActiveAccountId(app, mode)
            ?: throw IOException("Нет счёта (${executionModeLabelRu(mode)}).")
        val extraTatn = runCatching {
            setOf(tinkoffResolveShareInstrumentId(mode, token, "TATN").uppercase(Locale.US))
        }.getOrDefault(emptySet())
        val extraTatnp = runCatching {
            setOf(tinkoffResolveShareInstrumentId(mode, token, "TATNP").uppercase(Locale.US))
        }.getOrDefault(emptySet())
        val portfolio = tinkoffGetPortfolio(mode, token, accountId)
        val broker = detectBrokerSpreadPosition(portfolio, extraTatn, extraTatnp)
        val plan = flattenLegsForBrokerSnap(broker)
        if (plan.isEmpty()) {
        TinkoffSandboxSpreadExecLog.clearOpenExecutions(app)
        saveStrategyPosition(app, ZStrategyPosition.Flat)
        BrokerAccountPrefs.clearTakeProfitAtOpen(app)
        suppressUserCancelledEntry(app)
        return@runCatching "На брокере нет позиции TATN/TATNP."
        }

        val posted = mutableListOf<String>()
        for (leg in plan) {
            val instId = canonicalMoexShareInstrumentId(
                leg.ticker,
                runCatching { tinkoffResolveShareInstrumentId(mode, token, leg.ticker) }.getOrNull(),
            )
            val dir = if (leg.buy) "ORDER_DIRECTION_BUY" else "ORDER_DIRECTION_SELL"
            tinkoffPostMarketOrder(mode, token, accountId, instId, dir, leg.lots)
            posted += "${leg.dirRu} ${leg.ticker} ×${leg.lots}"
            MoexDiagnostics.log(
                app,
                "trade_flatten",
                "posted ${leg.dirRu} ${leg.ticker} lots=${leg.lots} mode=$mode",
            )
        }

        val opens = TinkoffSandboxSpreadExecLog.loadRecent(app)
            .filter {
                it.signalType == StrategySignalType.EnterLong ||
                    it.signalType == StrategySignalType.EnterShort
            }
        for (open in opens) {
            runCatching {
                finalizePortfolioOpenTradeClose(
                    context = app,
                    execution = open,
                    recordExitInJournal = true,
                ).getOrThrow()
            }.onFailure { e ->
                MoexDiagnostics.logError(app, "trade_flatten", e, "local close ${open.tradeId}")
            }
        }
        TinkoffSandboxSpreadExecLog.clearOpenExecutions(app)
        saveStrategyPosition(app, ZStrategyPosition.Flat)
        BrokerAccountPrefs.clearTakeProfitAtOpen(app)
        suppressUserCancelledEntry(app)
        "Закрыто на ${executionAccountShortRu(mode)}: ${posted.joinToString(", ")}"
    }
}

internal suspend fun runManualSpreadEntryFromTradeTab(
    context: Context,
    signalType: StrategySignalType,
    takeProfitPct: Double = DEFAULT_TAKE_PROFIT_PCT,
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        require(
            signalType == StrategySignalType.EnterLong || signalType == StrategySignalType.EnterShort
        ) { "Только EnterLong / EnterShort" }
        val app = context.applicationContext
        val mode = TinkoffExecutionMode.Prod
        val token = TinkoffSandboxStorage.getActiveToken(app, mode)
            ?: throw IOException("Нет токена (боевой счёт). Настройте Prod на вкладке «Песочница».")
        val accountId = TinkoffSandboxStorage.getActiveAccountId(app, mode)
            ?: throw IOException("Нет счёта (боевой счёт). Настройте Prod на вкладке «Песочница».")

        val extraTatn = runCatching {
            setOf(tinkoffResolveShareInstrumentId(mode, token, "TATN").uppercase(Locale.US))
        }.getOrDefault(emptySet())
        val extraTatnp = runCatching {
            setOf(tinkoffResolveShareInstrumentId(mode, token, "TATNP").uppercase(Locale.US))
        }.getOrDefault(emptySet())
        val portfolio = tinkoffGetPortfolio(mode, token, accountId)
        val broker = detectBrokerSpreadPosition(portfolio, extraTatn, extraTatnp)
        tradeTabManualEntryBlockReason(broker)?.let { throw IOException(it) }

        val market = loadCurrentPortfolioMarketSnapshot(app, forTestEntry = true)
        val dir = if (signalType == StrategySignalType.EnterShort) "SHORT" else "LONG"

        val entry = executeSpreadEntryDetailedForConfiguredMode(app, signalType, mode)
        val legs = entry.legs
        val sizing = entry.sizing
        val executedAt = System.currentTimeMillis()
        BrokerAccountPrefs.saveTakeProfitForOpen(app, takeProfitPct)

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

/** Парное событие: нога TATN + противоположная нога TATNP в пределах минуты. */
internal data class SpreadPairEvent(
    val startMs: Long,
    val endMs: Long,
    val tatnQty: Double,
    val tatnpQty: Double,
    val cashRub: Double,
    val priceTatn: Double?,
    val priceTatnp: Double?,
) {
    /** LONG = купили TATN / продали TATNP; SHORT = наоборот. */
    val side: String?
        get() = when {
            tatnQty > 0 && tatnpQty < 0 -> "LONG"
            tatnQty < 0 && tatnpQty > 0 -> "SHORT"
            else -> null
        }
}

internal data class SpreadMatchedTrade(
    val direction: String,
    val entry: SpreadPairEvent,
    val exit: SpreadPairEvent,
    val lots: Int,
    val pnlRub: Double,
)

internal const val LEG_PAIR_WINDOW_MS = 60_000L

/**
 * Склейка ног в парные события: каждая операция ищет противоположную по знаку
 * операцию другого тикера в пределах [LEG_PAIR_WINDOW_MS] (ноги одной пары идут
 * подряд с разницей в секунды; выход+вход в одну минуту не слипаются).
 */
internal fun pairSpreadLegs(ops: List<TInvestSpreadOp>): List<SpreadPairEvent> {
    val events = mutableListOf<SpreadPairEvent>()
    val pending = mutableListOf<TInvestSpreadOp>()
    for (op in ops) {
        val matchIdx = pending.indexOfFirst {
            it.ticker != op.ticker &&
                it.signedQty * op.signedQty < 0 &&
                op.millis - it.millis <= LEG_PAIR_WINDOW_MS
        }
        if (matchIdx < 0) {
            pending += op
            continue
        }
        val other = pending.removeAt(matchIdx)
        val legs = listOf(other, op)
        val tn = legs.filter { it.ticker == "TATN" }.sumOf { it.signedQty }
        val tp = legs.filter { it.ticker == "TATNP" }.sumOf { it.signedQty }
        events += SpreadPairEvent(
            startMs = minOf(other.millis, op.millis),
            endMs = maxOf(other.millis, op.millis),
            tatnQty = tn,
            tatnpQty = tp,
            cashRub = legs.sumOf { it.paymentRub },
            priceTatn = legs.firstOrNull { it.ticker == "TATN" }?.priceRub,
            priceTatnp = legs.firstOrNull { it.ticker == "TATNP" }?.priceRub,
        )
    }
    return events
}

/**
 * Матчинг событий в сделки: LONG-событие открывает лонг, следующее SHORT-событие
 * его закрывает (и наоборот). PnL: если лоты входа и выхода совпали — по реальным
 * платежам + комиссии за удержание; при частичном fill — по ценам на лоты входа
 * (платёж при частичном исполнении отражает только исполненную часть).
 */
internal fun matchSpreadTrades(
    events: List<SpreadPairEvent>,
    feePayments: List<Pair<Long, Double>>,
): List<SpreadMatchedTrade> {
    fun feesBetween(from: Long, to: Long): Double =
        feePayments.filter { it.first in from..to }.sumOf { it.second }
    val trades = mutableListOf<SpreadMatchedTrade>()
    var open: SpreadPairEvent? = null
    var openSide: String? = null
    for (ev in events) {
        val side = ev.side ?: continue
        val cur = open
        if (cur == null) {
            open = ev
            openSide = side
            continue
        }
        if (side == openSide) {
            // переворот/добор без закрытия — предыдущее считаем незакрытым, заменяем
            open = ev
            openSide = side
            continue
        }
        val lotsEntry = minOf(kotlin.math.abs(cur.tatnQty), kotlin.math.abs(cur.tatnpQty))
        val lotsExit = minOf(kotlin.math.abs(ev.tatnQty), kotlin.math.abs(ev.tatnpQty))
        val pnl = if (kotlin.math.abs(lotsEntry - lotsExit) < 1.0) {
            cur.cashRub + ev.cashRub + feesBetween(cur.startMs - 60_000L, ev.endMs + 5 * 60_000L)
        } else {
            val lots = lotsEntry
            val en = cur.priceTatn; val ep = cur.priceTatnp
            val xn = ev.priceTatn; val xp = ev.priceTatnp
            if (en != null && ep != null && xn != null && xp != null) {
                if (openSide == "LONG") lots * ((xn - en) - (xp - ep))
                else lots * ((en - xn) - (ep - xp))
            } else {
                cur.cashRub + ev.cashRub
            }
        }
        trades += SpreadMatchedTrade(
            direction = if (openSide == "SHORT") "Short" else "Long",
            entry = cur,
            exit = ev,
            lots = lotsEntry.toInt().coerceAtLeast(1),
            pnlRub = pnl,
        )
        open = null
        openSide = null
    }
    return trades
}

private fun spreadPctFromPrices(tatn: Double?, tatnp: Double?): Double? {
    if (tatn == null || tatnp == null || tatnp <= 0.0) return null
    return (tatn / tatnp - 1.0) * 100.0
}

/**
 * Закрытые пары TATN/TATNP за [days] дней из операций счёта Т-Инвест:
 * ноги склеиваются в пары (±60 сек), пары матчатся вход→выход;
 * PnL = реальные платежи + комиссии (при полном круге) или по ценам (при частичном fill).
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

    // В GetOperations у операций часто нет поля ticker — только figi/instrumentUid,
    // поэтому сначала узнаём id инструментов и матчим по ним.
    val tatnIds = runCatching { tinkoffResolveShareInstrumentId(mode, token, "TATN") }
        .getOrDefault(TINKOFF_MOEX_TATN_INSTRUMENT_ID)
        .let { setOf(it.uppercase(Locale.US), TINKOFF_MOEX_TATN_INSTRUMENT_ID, TINKOFF_MOEX_TATN_FIGI) }
    val tatnpIds = runCatching { tinkoffResolveShareInstrumentId(mode, token, "TATNP") }
        .getOrDefault(TINKOFF_MOEX_TATNP_INSTRUMENT_ID)
        .let { setOf(it.uppercase(Locale.US), TINKOFF_MOEX_TATNP_INSTRUMENT_ID, TINKOFF_MOEX_TATNP_FIGI) }

    fun tickerOf(op: org.json.JSONObject): String? {
        for (k in listOf("instrumentUid", "instrument_uid", "uid", "figi", "FIGI")) {
            val v = op.optString(k, "").trim().uppercase(Locale.US)
            if (v.isEmpty()) continue
            if (v in tatnIds) return "TATN"
            if (v in tatnpIds) return "TATNP"
        }
        return resolveOperationTicker(op)
    }

    var skippedNoTicker = 0
    var skippedType = 0
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
        if (!isBuy && !isSell) {
            skippedType++
            continue
        }
        val ticker = tickerOf(op)
        if (ticker == null) {
            skippedNoTicker++
            continue
        }
        val price = parseOperationMoneyField(op, "price")
        // Истинное исполнение = payment/price: при частичном fill поле quantity врёт
        // (06.09: quantity=360, исполнено 35 — payment = 35×price).
        val qtyFromPayment = if (price != null && price > 0.0 && payment != 0.0) {
            kotlin.math.abs(payment) / price
        } else {
            0.0
        }
        val qty = when {
            qtyFromPayment > 0.0 -> qtyFromPayment
            else -> op.optDouble("quantity", 0.0).let { if (it > 0) it else op.optDouble("lots", 0.0) }
        }
        if (qty <= 0.0) continue
        ops += TInvestSpreadOp(
            millis = ms,
            ticker = ticker,
            signedQty = if (isBuy) qty else -qty,
            priceRub = price,
            paymentRub = payment,
        )
    }
    MoexDiagnostics.log(
        context,
        "trade_history",
        "GetOperations 14д: операций TATN/TATNP=${ops.size}, без тикера=$skippedNoTicker, прочие типы=$skippedType, комиссий=${feePayments.size}",
    )
    if (ops.isEmpty()) return@withContext null
    ops.sortBy { it.millis }
    feePayments.sortBy { it.first }

    val events = pairSpreadLegs(ops)
    val trades = matchSpreadTrades(events, feePayments)
    if (trades.isEmpty()) return@withContext null

    trades.mapIndexed { idx, t ->
        TradeTabClosedTrade(
            id = "Т${idx + 1}",
            directionLabel = t.direction,
            entryTimeMsk = formatPortfolioExecutionTableMsk(t.entry.startMs),
            exitTimeMsk = formatPortfolioExecutionTableMsk(t.exit.endMs),
            quantityLots = t.lots,
            entrySpreadPercent = spreadPctFromPrices(t.entry.priceTatn, t.entry.priceTatnp),
            exitSpreadPercent = spreadPctFromPrices(t.exit.priceTatn, t.exit.priceTatnp),
            pnlRub = t.pnlRub,
            sourceLabel = "",
        )
    }.sortedByDescending { it.exitTimeMsk }
}
