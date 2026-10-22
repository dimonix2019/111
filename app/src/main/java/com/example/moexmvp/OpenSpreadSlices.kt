package com.example.moexmvp

import org.json.JSONObject
import java.time.Instant
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

/** Откуда вход: фиксированные уровни или прокол динамического диапазона. */
internal enum class SpreadTradeBook {
    Base,
    DynamicRange,
}

internal fun spreadTradeBookLabel(book: SpreadTradeBook): String = when (book) {
    SpreadTradeBook.Base -> "база"
    SpreadTradeBook.DynamicRange -> "динамический диапазон"
}

internal data class SpreadFillLot(
    val ticker: String,
    val isBuy: Boolean,
    val lots: Int,
    val price: Double,
    val millis: Long,
)

/** Одна открытая сделка спреда, не нетто всего счёта. */
internal data class OpenSpreadSlice(
    val book: SpreadTradeBook,
    val tatnLots: Int,
    val tatnpLots: Int,
    val entryMillis: Long,
    val fillTatn: Double,
    val fillTatnp: Double,
)

private data class LotPiece(
    var lots: Int,
    val price: Double,
    val millis: Long,
)

private class LotBook {
    val q = ArrayDeque<LotPiece>()
    fun add(lots: Int, price: Double, millis: Long) {
        if (lots > 0 && price > 0) q.add(LotPiece(lots, price, millis))
    }

    fun total(): Int = q.sumOf { it.lots }

    /** Списать лоты. Цена — средняя списанных, время — самое позднее. */
    fun take(n: Int): Triple<Double, Long, Int>? {
        if (n <= 0 || total() <= 0) return null
        var left = n.coerceAtMost(total())
        var cost = 0.0
        var taken = 0
        var latest = 0L
        while (left > 0 && q.isNotEmpty()) {
            val head = q.first()
            val use = minOf(left, head.lots)
            cost += use * head.price
            taken += use
            latest = maxOf(latest, head.millis)
            head.lots -= use
            left -= use
            if (head.lots <= 0) q.removeFirst()
        }
        if (taken <= 0) return null
        return Triple(cost / taken, latest, taken)
    }
}

/**
 * Открытые пары по ленте сделок. Закрытие снимает срез с тем же числом лотов,
 * а не соседнюю сделку. Первая по времени — «база», следующие — «динамический диапазон».
 */
private fun openLongLots(open: List<OpenSpreadSlice>, long: Boolean): Int =
    open.filter { (it.tatnLots > 0) == long && it.tatnLots != 0 }.sumOf { abs(it.tatnLots) }

internal fun openSpreadSlices(fills: List<SpreadFillLot>): List<OpenSpreadSlice> {
    val tatnLong = LotBook()
    val tatnpLong = LotBook()
    val tatnShort = LotBook()
    val tatnpShort = LotBook()
    val closeLongTatn = LotBook()
    val closeLongTatnp = LotBook()
    val closeShortTatn = LotBook()
    val closeShortTatnp = LotBook()
    val open = mutableListOf<OpenSpreadSlice>()

    fun finishCloseLong() {
        while (closeLongTatn.total() > 0 && closeLongTatnp.total() > 0 && openLongLots(open, long = true) > 0) {
            val n = minOf(closeLongTatn.total(), closeLongTatnp.total(), openLongLots(open, long = true))
            closeLongTatn.take(n)
            closeLongTatnp.take(n)
            reduce(sideLong = true, lots = n)
        }
    }

    fun finishCloseShort() {
        while (closeShortTatn.total() > 0 && closeShortTatnp.total() > 0 && openLongLots(open, long = false) > 0) {
            val n = minOf(closeShortTatn.total(), closeShortTatnp.total(), openLongLots(open, long = false))
            closeShortTatn.take(n)
            closeShortTatnp.take(n)
            reduce(sideLong = false, lots = n)
        }
    }

    fun pairLong() {
        while (tatnLong.total() > 0 && tatnpLong.total() > 0) {
            val n = minOf(tatnLong.total(), tatnpLong.total())
            val tatn = tatnLong.take(n) ?: return
            val tatnp = tatnpLong.take(n) ?: return
            val lots = minOf(tatn.third, tatnp.third)
            if (lots <= 0) return
            open += OpenSpreadSlice(
                book = SpreadTradeBook.Base,
                tatnLots = lots,
                tatnpLots = -lots,
                entryMillis = maxOf(tatn.second, tatnp.second),
                fillTatn = tatn.first,
                fillTatnp = tatnp.first,
            )
        }
    }

    fun pairShort() {
        while (tatnShort.total() > 0 && tatnpShort.total() > 0) {
            val n = minOf(tatnShort.total(), tatnpShort.total())
            val tatn = tatnShort.take(n) ?: return
            val tatnp = tatnpShort.take(n) ?: return
            val lots = minOf(tatn.third, tatnp.third)
            if (lots <= 0) return
            open += OpenSpreadSlice(
                book = SpreadTradeBook.Base,
                tatnLots = -lots,
                tatnpLots = lots,
                entryMillis = maxOf(tatn.second, tatnp.second),
                fillTatn = tatn.first,
                fillTatnp = tatnp.first,
            )
        }
    }

    fun reduce(sideLong: Boolean, lots: Int) {
        var left = lots
        val matches = open.indices.filter { i ->
            val s = open[i]
            val size = abs(s.tatnLots)
            (s.tatnLots > 0) == sideLong && size > 0
        }.sortedBy { i ->
            val size = abs(open[i].tatnLots)
            if (size == lots) 0 else 1
        }
        for (i in matches) {
            if (left <= 0) break
            val s = open[i]
            val size = abs(s.tatnLots)
            val use = minOf(left, size)
            val next = size - use
            open[i] = if (next <= 0) {
                s.copy(tatnLots = 0, tatnpLots = 0)
            } else if (s.tatnLots > 0) {
                s.copy(tatnLots = next, tatnpLots = -next)
            } else {
                s.copy(tatnLots = -next, tatnpLots = next)
            }
            left -= use
            if (use == size) break
        }
    }

    for (fill in fills.sortedBy { it.millis }) {
        if (fill.lots <= 0 || fill.price <= 0) continue
        val ticker = fill.ticker.uppercase(Locale.US)
        when {
            ticker == "TATN" && fill.isBuy -> {
                val fromQueue = minOf(fill.lots, tatnShort.total())
                if (fromQueue > 0) tatnShort.take(fromQueue)
                val rest = fill.lots - fromQueue
                if (rest > 0 && openLongLots(open, long = false) > 0) {
                    closeShortTatn.add(rest, fill.price, fill.millis)
                } else if (rest > 0) {
                    tatnLong.add(rest, fill.price, fill.millis)
                }
            }
            ticker == "TATN" && !fill.isBuy -> {
                val fromQueue = minOf(fill.lots, tatnLong.total())
                if (fromQueue > 0) tatnLong.take(fromQueue)
                val rest = fill.lots - fromQueue
                if (rest > 0 && openLongLots(open, long = true) > 0) {
                    closeLongTatn.add(rest, fill.price, fill.millis)
                } else if (rest > 0) {
                    tatnShort.add(rest, fill.price, fill.millis)
                }
            }
            ticker == "TATNP" && !fill.isBuy -> {
                val fromQueue = minOf(fill.lots, tatnpShort.total())
                if (fromQueue > 0) tatnpShort.take(fromQueue)
                val rest = fill.lots - fromQueue
                if (rest > 0 && openLongLots(open, long = false) > 0) {
                    closeShortTatnp.add(rest, fill.price, fill.millis)
                } else if (rest > 0) {
                    tatnpLong.add(rest, fill.price, fill.millis)
                }
            }
            ticker == "TATNP" && fill.isBuy -> {
                val fromQueue = minOf(fill.lots, tatnpLong.total())
                if (fromQueue > 0) tatnpLong.take(fromQueue)
                val rest = fill.lots - fromQueue
                if (rest > 0 && openLongLots(open, long = true) > 0) {
                    closeLongTatnp.add(rest, fill.price, fill.millis)
                } else if (rest > 0) {
                    tatnpShort.add(rest, fill.price, fill.millis)
                }
            }
        }
        pairLong()
        pairShort()
        finishCloseLong()
        finishCloseShort()
    }
    return assignSpreadBooks(open.filter { it.tatnLots != 0 && it.tatnpLots != 0 })
}

internal fun assignSpreadBooks(slices: List<OpenSpreadSlice>): List<OpenSpreadSlice> {
    val sorted = slices.sortedBy { it.entryMillis }
    return sorted.mapIndexed { index, slice ->
        slice.copy(
            book = if (index == 0) SpreadTradeBook.Base else SpreadTradeBook.DynamicRange,
        )
    }
}

internal fun spreadSliceDepositRub(
    slice: OpenSpreadSlice,
    leverage: Double = SPREAD_LOT_PROD_DEFAULT_LEVERAGE,
): Double {
    if (leverage <= 0) return 0.0
    val notional = abs(slice.tatnLots) * slice.fillTatn + abs(slice.tatnpLots) * slice.fillTatnp
    return notional / leverage
}

/** Строка суммы только когда открыто две и больше сделок. */
internal fun formatOpenSpreadsSumLine(nets: List<Double>, deposits: List<Double>): String? {
    if (nets.size < 2 || nets.size != deposits.size) return null
    val rub = nets.sum()
    val dep = deposits.sum()
    val pct = if (dep > 0.0) rub / dep * 100.0 else 0.0
    return "Сумма ${formatCloseNowHeroRub(rub)} (${formatPercentSigned(pct)} от депозитов ${formatCloseNowCashAfterRub(dep)})"
}

internal fun formatShadeOpenSpreads(
    slices: List<OpenSpreadSlice>,
    nets: List<Double>,
    deposits: List<Double>,
): String? {
    if (slices.isEmpty() || slices.size != nets.size) return null
    val lines = slices.mapIndexed { i, slice ->
        val whenTxt = compactMonitorDateTimeMsk(formatPortfolioExecutionTableMsk(slice.entryMillis))
        "${spreadTradeBookLabel(slice.book)} · $whenTxt · ${formatCompactSignedPnlRub(nets[i])}"
    }
    val total = formatOpenSpreadsSumLine(nets, deposits)
    return if (total == null) lines.joinToString("\n") else (lines + total).joinToString("\n")
}

internal fun signedLotsForSlice(slice: OpenSpreadSlice): Pair<Int, Int> =
    slice.tatnLots to slice.tatnpLots

internal data class OpenSpreadCard(
    val slice: OpenSpreadSlice,
    val entryTimeMsk: String,
    val closeNow: CloseNowPnl?,
    val depositRub: Double,
)

internal suspend fun fetchOpenSpreadSlices(token: String, accountId: String): List<OpenSpreadSlice> {
    if (token.isBlank() || accountId.isBlank()) return emptyList()
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
    }.getOrNull() ?: return emptyList()
    return openSpreadSlices(spreadFillLotsFromOperations(root))
}

internal fun spreadFillLotsFromOperations(root: JSONObject): List<SpreadFillLot> {
    val out = mutableListOf<JSONObject>()
    fun walk(o: JSONObject?, depth: Int) {
        if (o == null || depth > 8) return
        o.optJSONArray("operations")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        }
        val it = o.keys()
        while (it.hasNext()) walk(o.optJSONObject(it.next()), depth + 1)
    }
    walk(root, 0)
    return out.mapNotNull { op ->
        val ticker = resolveSpreadOperationTicker(op) ?: return@mapNotNull null
        if (ticker != "TATN" && ticker != "TATNP") return@mapNotNull null
        val ms = op.optString("date").trim().ifBlank { op.optString("Date") }
            .let { raw -> runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() }
            ?: return@mapNotNull null
        val type = op.optString("operationType").ifBlank { op.optString("type") }.uppercase(Locale.US)
        val buy = when {
            type.contains("BUY") -> true
            type.contains("SELL") -> false
            else -> return@mapNotNull null
        }
        val qtyObj = op.optJSONObject("quantity") ?: op.optJSONObject("quantityDone")
        val lots = qtyObj?.let { quotationUnitsToDouble(it)?.toInt() } ?: op.optInt("quantity", 0)
        if (lots <= 0) return@mapNotNull null
        val payment = op.optJSONObject("payment")?.let { quotationUnitsToDouble(it) }
        val price = when {
            payment != null && payment != 0.0 -> abs(payment) / lots
            else -> op.optJSONObject("price")?.let { quotationUnitsToDouble(it) }
        } ?: return@mapNotNull null
        if (price <= 0) return@mapNotNull null
        SpreadFillLot(ticker, buy, lots, price, ms)
    }
}

internal fun openSpreadCards(
    slices: List<OpenSpreadSlice>,
    quotesFor: (OpenSpreadSlice) -> PairQuotes?,
    fallbackTatn: Double?,
    fallbackTatnp: Double?,
    cashRub: Double?,
    nowMillis: Long,
): List<OpenSpreadCard> {
    val many = slices.size > 1
    return slices.map { slice ->
        val deposit = spreadSliceDepositRub(slice)
        val pnl = computeCloseNowPnl(
            tatnLots = slice.tatnLots,
            tatnpLots = slice.tatnpLots,
            fillTatnRub = slice.fillTatn,
            fillTatnpRub = slice.fillTatnp,
            quotes = quotesFor(slice),
            fallbackTatnLast = fallbackTatn,
            fallbackTatnpLast = fallbackTatnp,
            depositRub = deposit,
            cashRub = if (many) null else cashRub,
            entryTimeMsk = formatPortfolioExecutionTableMsk(slice.entryMillis),
            nowMillis = nowMillis,
        )
        OpenSpreadCard(
            slice = slice,
            entryTimeMsk = formatPortfolioExecutionTableMsk(slice.entryMillis),
            closeNow = pnl,
            depositRub = deposit,
        )
    }
}

internal fun formatShadeFromCards(cards: List<OpenSpreadCard>): String? {
    val withPnl = cards.mapNotNull { card -> card.closeNow?.let { card to it.netRub } }
    if (withPnl.size != cards.size || cards.isEmpty()) return null
    return formatShadeOpenSpreads(
        slices = cards.map { it.slice },
        nets = withPnl.map { it.second },
        deposits = cards.map { it.depositRub },
    )
}
