package com.example.moexmvp

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Полутик, если ISS не отдал BID/OFFER (как web close_forecast). */
internal const val CLOSE_NOW_HALF_TICK_RUB = 0.05

internal data class ShareQuote(
    val last: Double? = null,
    val bid: Double? = null,
    val ask: Double? = null,
)

internal data class PairQuotes(
    val tatn: ShareQuote = ShareQuote(),
    val tatnp: ShareQuote = ShareQuote(),
    val source: String = "iss",
)

/** Чистый результат, если закрыть пару рыночными заявками сейчас. */
internal data class CloseNowPnl(
    val netRub: Double,
    val pctFromDeposit: Double?,
    val grossRub: Double,
    val entryCommissionRub: Double,
    val exitCommissionRub: Double,
    val overnightShortRub: Double,
    val overnightMarginLoanRub: Double,
    val overnightDays: Long,
    val closeTatnRub: Double?,
    val closeTatnpRub: Double?,
    val quotesMode: String,
    val note: String,
) {
    val overnightTotalRub: Double get() = overnightShortRub + overnightMarginLoanRub
}

internal fun synthBidAsk(
    last: Double?,
    bid: Double?,
    ask: Double?,
): Triple<Double?, Double?, String> {
    val bIn = bid?.takeIf { it > 0 }
    val aIn = ask?.takeIf { it > 0 }
    if (bIn != null && aIn != null) return Triple(bIn, aIn, "book")
    val mid = last?.takeIf { it > 0 }
    if (mid == null) {
        if (bIn != null) return Triple(bIn, bIn + CLOSE_NOW_HALF_TICK_RUB * 2, "partial")
        if (aIn != null) return Triple(max(0.01, aIn - CLOSE_NOW_HALF_TICK_RUB * 2), aIn, "partial")
        return Triple(null, null, "none")
    }
    val b = bIn ?: (mid - CLOSE_NOW_HALF_TICK_RUB)
    val a = aIn ?: (mid + CLOSE_NOW_HALF_TICK_RUB)
    val bidOut = if (b > 0) b else mid * 0.9999
    var askOut = if (a > 0) a else mid * 1.0001
    if (askOut < bidOut) askOut = bidOut
    val mode = if (bIn != null && aIn != null) "book" else "last_fallback"
    return Triple(bidOut, askOut, mode)
}

/** Цена закрытия ноги: лонг продаём по bid, шорт откупаем по ask. */
internal fun closePriceForSignedLots(lots: Int, bid: Double?, ask: Double?): Double? =
    when {
        lots > 0 -> bid?.takeIf { it > 0 }
        lots < 0 -> ask?.takeIf { it > 0 }
        else -> null
    }

internal fun signedLegsUnrealizedRub(
    tatnLots: Int,
    tatnpLots: Int,
    fillTatn: Double?,
    fillTatnp: Double?,
    nowTatn: Double?,
    nowTatnp: Double?,
): Double? {
    var pnl = 0.0
    var any = false
    if (tatnLots != 0 && fillTatn != null && nowTatn != null && fillTatn > 0 && nowTatn > 0) {
        pnl += tatnLots * (nowTatn - fillTatn)
        any = true
    }
    if (tatnpLots != 0 && fillTatnp != null && nowTatnp != null && fillTatnp > 0 && nowTatnp > 0) {
        pnl += tatnpLots * (nowTatnp - fillTatnp)
        any = true
    }
    return if (any) pnl else null
}

internal fun pairNotionalFromPrices(lotsA: Int, pxA: Double?, lotsB: Int, pxB: Double?): Double {
    var n = 0.0
    if (lotsA != 0 && pxA != null && pxA > 0) n += abs(lotsA) * pxA
    if (lotsB != 0 && pxB != null && pxB > 0) n += abs(lotsB) * pxB
    return n
}

internal fun premiumCommissionRub(notionalRub: Double): Double {
    if (!(notionalRub > 0)) return 0.0
    return notionalRub * (PROD_COMMISSION_PCT_PER_SIDE / 100.0)
}

/** Плата Премиум за заёмные деньги (отриц. кэш), %/день. */
internal fun borrowedCashOvernightRub(cashRub: Double?, days: Long): Double {
    val cash = cashRub ?: return 0.0
    if (!(cash < 0) || days <= 0) return 0.0
    return abs(cash) * (TINKOFF_OVERNIGHT_FEE_PERCENT_PER_DAY / 100.0) * days
}

internal fun shortUncoveredNowRub(
    tatnLots: Int,
    tatnpLots: Int,
    closeTatn: Double?,
    closeTatnp: Double?,
    fillTatn: Double?,
    fillTatnp: Double?,
): Double {
    if (tatnpLots < 0) {
        val px = closeTatnp?.takeIf { it > 0 } ?: fillTatnp?.takeIf { it > 0 } ?: return 0.0
        return abs(tatnpLots) * px
    }
    if (tatnLots < 0) {
        val px = closeTatn?.takeIf { it > 0 } ?: fillTatn?.takeIf { it > 0 } ?: return 0.0
        return abs(tatnLots) * px
    }
    return 0.0
}

internal fun computeCloseNowPnl(
    tatnLots: Int,
    tatnpLots: Int,
    fillTatnRub: Double?,
    fillTatnpRub: Double?,
    quotes: PairQuotes?,
    fallbackTatnLast: Double?,
    fallbackTatnpLast: Double?,
    depositRub: Double?,
    cashRub: Double?,
    entryTimeMsk: String?,
    nowMillis: Long = System.currentTimeMillis(),
): CloseNowPnl? {
    if (tatnLots == 0 && tatnpLots == 0) return null
    val tn = quotes?.tatn ?: ShareQuote()
    val tp = quotes?.tatnp ?: ShareQuote()
    val (tnBid, tnAsk, tnMode) = synthBidAsk(
        tn.last ?: fallbackTatnLast,
        tn.bid,
        tn.ask,
    )
    val (tpBid, tpAsk, tpMode) = synthBidAsk(
        tp.last ?: fallbackTatnpLast,
        tp.bid,
        tp.ask,
    )
    val closeTatn = closePriceForSignedLots(tatnLots, tnBid, tnAsk)
    val closeTatnp = closePriceForSignedLots(tatnpLots, tpBid, tpAsk)
    val gross = signedLegsUnrealizedRub(
        tatnLots = tatnLots,
        tatnpLots = tatnpLots,
        fillTatn = fillTatnRub,
        fillTatnp = fillTatnpRub,
        nowTatn = closeTatn,
        nowTatnp = closeTatnp,
    ) ?: return null

    val entryNotional = pairNotionalFromPrices(tatnLots, fillTatnRub, tatnpLots, fillTatnpRub)
    val exitNotional = pairNotionalFromPrices(tatnLots, closeTatn, tatnpLots, closeTatnp)
    val entryComm = premiumCommissionRub(entryNotional)
    val exitComm = premiumCommissionRub(exitNotional)

    val ovnDays = entryTimeMsk?.let { entryLabel ->
        val end = formatPortfolioExecutionTableMsk(nowMillis)
        overnightDays(
            portfolioDateLabelFromMskTableTime(entryLabel),
            portfolioDateLabelFromMskTableTime(end),
        )
    } ?: 0L
    val uncovered = shortUncoveredNowRub(
        tatnLots, tatnpLots, closeTatn, closeTatnp, fillTatnRub, fillTatnpRub,
    )
    val ovnShort = overnightFeePerDayRub(uncovered) * ovnDays
    val ovnLoan = borrowedCashOvernightRub(cashRub, ovnDays)
    val net = gross - entryComm - exitComm - ovnShort - ovnLoan
    val dep = depositRub?.takeIf { it > 0 }
    val tnNeeded = tatnLots != 0
    val tpNeeded = tatnpLots != 0
    val bookOk = (!tnNeeded || tnMode == "book") && (!tpNeeded || tpMode == "book")
    val quotesMode = when {
        bookOk && (tnNeeded || tpNeeded) -> "book"
        (!tnNeeded || tnMode != "none") && (!tpNeeded || tpMode != "none") -> "last_fallback"
        else -> "none"
    }
    val note = when (quotesMode) {
        "book" -> "по BID/OFFER ISS · тариф Премиум"
        "last_fallback" -> "BID/OFFER нет — LAST ±0,05 ₽ · Премиум"
        else -> "тариф Премиум"
    }
    return CloseNowPnl(
        netRub = net,
        pctFromDeposit = dep?.let { net / it * 100.0 },
        grossRub = gross,
        entryCommissionRub = entryComm,
        exitCommissionRub = exitComm,
        overnightShortRub = ovnShort,
        overnightMarginLoanRub = ovnLoan,
        overnightDays = ovnDays,
        closeTatnRub = closeTatn,
        closeTatnpRub = closeTatnp,
        quotesMode = quotesMode,
        note = note,
    )
}

internal fun formatCloseNowHeroRub(v: Double): String =
    String.format(Locale.US, "%+,.0f ₽", kotlin.math.round(v)).replace(',', ' ')

internal fun formatCloseNowBreakdown(pnl: CloseNowPnl): String {
    val parts = mutableListOf<String>()
    parts += "комиссия вх/вых ${formatRubExpense(pnl.entryCommissionRub)}/${formatRubExpense(pnl.exitCommissionRub)}"
    if (pnl.overnightDays > 0 || pnl.overnightTotalRub > 0) {
        parts += "перенос ${formatRubExpense(pnl.overnightShortRub)}"
        if (pnl.overnightMarginLoanRub > 0) {
            parts += "маржа ${formatRubExpense(pnl.overnightMarginLoanRub)}"
        }
        if (pnl.overnightDays > 0) parts += "${pnl.overnightDays} д"
    }
    val px = buildString {
        pnl.closeTatnRub?.let { append("TATN ${String.format(Locale.US, "%.1f", it)}") }
        pnl.closeTatnpRub?.let {
            if (isNotEmpty()) append(" / ")
            append("TATNP ${String.format(Locale.US, "%.1f", it)}")
        }
    }
    return listOfNotNull(px.takeIf { it.isNotBlank() }, parts.joinToString(" · ").takeIf { it.isNotBlank() })
        .joinToString(" · ")
}

internal fun parseIssMarketdataQuote(json: String): ShareQuote? {
    val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
    val md = root.optJSONObject("marketdata") ?: return null
    val cols = md.optJSONArray("columns") ?: return null
    val data = md.optJSONArray("data") ?: return null
    if (cols.length() == 0 || data.length() == 0) return null
    val row = data.optJSONArray(0) ?: return null
    val index = linkedMapOf<String, Int>()
    for (i in 0 until cols.length()) {
        index[cols.optString(i).uppercase(Locale.US)] = i
    }
    fun cell(vararg names: String): Double? {
        for (n in names) {
            val i = index[n] ?: continue
            val raw = row.opt(i) ?: continue
            val v = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> null
            }
            if (v != null && v > 0 && v.isFinite()) return v
        }
        return null
    }
    return ShareQuote(
        last = cell("LAST", "LCURRENTPRICE", "MARKETPRICE"),
        bid = cell("BID", "LASTBID"),
        ask = cell("OFFER", "LASTOFFER"),
    )
}

internal fun fetchIssShareQuote(secId: String): ShareQuote? {
    val url = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/" +
        "$secId.json?iss.meta=off&iss.only=marketdata"
    val request = okhttp3.Request.Builder()
        .url(url)
        .header("Cache-Control", "no-cache")
        .build()
    return runCatching {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            parseIssMarketdataQuote(response.body?.string().orEmpty())
        }
    }.getOrNull()
}

internal suspend fun fetchIssPairQuotes(): PairQuotes = kotlinx.coroutines.coroutineScope {
    val tn = kotlinx.coroutines.async {
        runCatching { fetchIssShareQuote("TATN") }.getOrNull() ?: ShareQuote()
    }
    val tp = kotlinx.coroutines.async {
        runCatching { fetchIssShareQuote("TATNP") }.getOrNull() ?: ShareQuote()
    }
    PairQuotes(tatn = tn.await(), tatnp = tp.await(), source = "iss")
}
