package com.example.moexmvp

import java.util.Locale
import kotlin.math.max

/** Цель Take Profit в % от вложения (как на web-столе). */
internal const val DEFAULT_TAKE_PROFIT_PCT = 2.0
internal const val TAKE_PROFIT_PCT_MIN = 0.1
internal const val TAKE_PROFIT_PCT_MAX = 50.0

/** Комиссия за сторону, % от номинала (parity closed_metrics.py). */
internal const val PROD_COMMISSION_PCT_PER_SIDE = 0.04

/** Прогноз чистой прибыли при выходе по Take Profit. */
internal data class TakeProfitForecast(
    val exitSpreadPercent: Double,
    val netPnlRub: Double,
    val pnlPercentFromDeposit: Double,
    val depositRub: Double,
    val takeProfitPct: Double = DEFAULT_TAKE_PROFIT_PCT,
)

/**
 * Спред-цель ТП: net ≈ deposit×tp% после комиссии выхода и overnight.
 * Long: entry + Δп.п.; Short: entry − Δп.п.
 */
internal fun takeProfitExitSpread(
    side: ZStrategyPosition,
    entrySpreadPercent: Double,
    depositRub: Double,
    effNotionalRub: Double,
    takeProfitPct: Double = DEFAULT_TAKE_PROFIT_PCT,
    exitCommissionRub: Double = 0.0,
    overnightRub: Double = 0.0,
): Double? {
    if (side == ZStrategyPosition.Flat) return null
    if (!entrySpreadPercent.isFinite() || !(effNotionalRub > 0)) return null
    val tp = takeProfitPct.takeIf { it > 0 } ?: return null
    val dep = max(0.0, depositRub)
    val needGross = dep * (tp / 100.0) + exitCommissionRub + overnightRub
    val deltaPp = needGross / effNotionalRub * 100.0
    return when (side) {
        ZStrategyPosition.Long -> entrySpreadPercent + deltaPp
        ZStrategyPosition.Short -> entrySpreadPercent - deltaPp
        ZStrategyPosition.Flat -> null
    }
}

/** Стоимость короткой ноги — база непокрытой позиции (parity overnight_fee.py). */
internal fun shortLegUncoveredRub(
    side: ZStrategyPosition,
    lots: Int,
    fillTatnRub: Double?,
    fillTatnpRub: Double?,
    notionalRub: Double?,
): Double {
    val qty = max(0, lots)
    if (qty > 0 && fillTatnRub != null && fillTatnpRub != null &&
        fillTatnRub > 0 && fillTatnpRub > 0
    ) {
        return when (side) {
            ZStrategyPosition.Long -> qty * fillTatnpRub
            ZStrategyPosition.Short -> qty * fillTatnRub
            ZStrategyPosition.Flat -> 0.0
        }
    }
    val nom = notionalRub?.takeIf { it > 0 } ?: return 0.0
    return nom / 2.0
}

/** Ступени тарифа Премиум → ₽/календарный день. */
internal fun overnightFeePerDayRub(uncoveredRub: Double): Double {
    val u = max(0.0, uncoveredRub)
    if (u <= 0) return 0.0
    return when {
        u <= 5_000 -> 0.0
        u <= 50_000 -> 35.0
        u <= 100_000 -> 70.0
        u <= 250_000 -> 175.0
        u <= 500_000 -> 340.0
        u <= 1_000_000 -> 680.0
        u <= 2_500_000 -> 1_700.0
        u <= 5_000_000 -> 3_400.0
        u <= 10_000_000 -> 6_800.0
        u <= 25_000_000 -> u * 0.00066
        u <= 50_000_000 -> u * 0.00063
        else -> u * 0.00055
    }
}

internal fun computeTakeProfitForecast(
    side: ZStrategyPosition,
    entrySpreadPercent: Double?,
    depositRub: Double?,
    notionalRub: Double?,
    lots: Int,
    fillTatnRub: Double?,
    fillTatnpRub: Double?,
    entryTimeMsk: String?,
    takeProfitPct: Double = DEFAULT_TAKE_PROFIT_PCT,
    nowMillis: Long = System.currentTimeMillis(),
): TakeProfitForecast? {
    if (side == ZStrategyPosition.Flat) return null
    val entry = entrySpreadPercent?.takeIf { it.isFinite() } ?: return null
    val deposit = depositRub?.takeIf { it > 0 } ?: return null
    val eff = notionalRub?.takeIf { it > 0 }
        ?: deposit * SPREAD_LOT_PROD_DEFAULT_LEVERAGE
    if (!(eff > 0)) return null

    val exitComm = eff * (PROD_COMMISSION_PCT_PER_SIDE / 100.0)
    val uncovered = shortLegUncoveredRub(side, lots, fillTatnRub, fillTatnpRub, eff)
    val ovnPerDay = overnightFeePerDayRub(uncovered)
    val ovnDays = entryTimeMsk?.let { entryLabel ->
        val end = formatPortfolioExecutionTableMsk(nowMillis)
        overnightDays(
            portfolioDateLabelFromMskTableTime(entryLabel),
            portfolioDateLabelFromMskTableTime(end),
        )
    } ?: 0L
    val overnightRub = ovnPerDay * ovnDays

    val exitSpread = takeProfitExitSpread(
        side = side,
        entrySpreadPercent = entry,
        depositRub = deposit,
        effNotionalRub = eff,
        takeProfitPct = takeProfitPct,
        exitCommissionRub = exitComm,
        overnightRub = overnightRub,
    ) ?: return null

    val pnlPts = when (side) {
        ZStrategyPosition.Long -> exitSpread - entry
        ZStrategyPosition.Short -> entry - exitSpread
        ZStrategyPosition.Flat -> return null
    }
    val gross = spreadPnlToRubApprox(pnlPts, eff)
    val net = gross - exitComm - overnightRub
    val pct = (net / deposit) * 100.0
    return TakeProfitForecast(
        exitSpreadPercent = exitSpread,
        netPnlRub = net,
        pnlPercentFromDeposit = pct,
        depositRub = deposit,
        takeProfitPct = takeProfitPct,
    )
}

internal fun coerceTakeProfitPct(pct: Double): Double {
    if (!pct.isFinite() || pct <= 0.0) return DEFAULT_TAKE_PROFIT_PCT
    return pct.coerceIn(TAKE_PROFIT_PCT_MIN, TAKE_PROFIT_PCT_MAX)
}

internal fun takeProfitRubFromPercent(pct: Double, depositRub: Double): Double {
    if (!pct.isFinite() || !depositRub.isFinite() || depositRub <= 0.0) return 0.0
    return depositRub * (pct / 100.0)
}

internal fun takeProfitPercentFromRub(rub: Double, depositRub: Double): Double {
    if (!rub.isFinite() || !depositRub.isFinite() || depositRub <= 1e-6) {
        return DEFAULT_TAKE_PROFIT_PCT
    }
    return (rub / depositRub) * 100.0
}

internal fun parseTakeProfitNumber(raw: String): Double? {
    val t = raw.trim().replace(" ", "").replace(',', '.')
    if (t.isEmpty() || t == "." || t == "-") return null
    return t.toDoubleOrNull()?.takeIf { it.isFinite() }
}

internal fun filterTakeProfitInput(raw: String, maxLen: Int = 8): String {
    val sb = StringBuilder()
    var sep = false
    for (ch in raw) {
        when {
            ch.isDigit() -> sb.append(ch)
            (ch == '.' || ch == ',') && !sep -> {
                sb.append(ch)
                sep = true
            }
        }
        if (sb.length >= maxLen) break
    }
    return sb.toString()
}

internal fun formatTakeProfitPctInput(pct: Double): String {
    val v = if (pct.isFinite()) pct else DEFAULT_TAKE_PROFIT_PCT
    val asInt = kotlin.math.round(v)
    return if (kotlin.math.abs(v - asInt) < 1e-6) {
        asInt.toInt().toString()
    } else {
        String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.').replace('.', ',')
    }
}

internal fun formatTakeProfitRubInput(rub: Double): String {
    if (!rub.isFinite() || rub < 0.0) return ""
    return kotlin.math.round(rub).toLong().toString()
}

/** Пустые поля → 2%. Невалидный ввод → null (кнопка «Открыть» неактивна). */
internal fun resolveTakeProfitPctFromInputs(
    pctText: String,
    rubText: String,
    depositRub: Double,
): Double? {
    val pct = parseTakeProfitNumber(pctText)
    if (pct != null && pct > 0.0) return coerceTakeProfitPct(pct)
    val rub = parseTakeProfitNumber(rubText)
    if (rub != null && rub > 0.0 && depositRub > 0.0) {
        return coerceTakeProfitPct(takeProfitPercentFromRub(rub, depositRub))
    }
    if (pctText.isBlank() && rubText.isBlank()) return DEFAULT_TAKE_PROFIT_PCT
    return null
}

internal fun takeProfitDepositRub(
    portfolioTotalRub: Double?,
    cashRub: Double?,
    depositRub: Double?,
): Double =
    portfolioTotalRub?.takeIf { it > 0.0 }
        ?: cashRub?.takeIf { it > 0.0 }
        ?: depositRub?.takeIf { it > 0.0 }
        ?: 0.0

internal fun shouldFireTakeProfit(
    expectedYieldRub: Double?,
    depositRub: Double,
    takeProfitPct: Double,
): Boolean {
    val y = expectedYieldRub ?: return false
    if (!y.isFinite() || y <= 0.0) return false
    if (!depositRub.isFinite() || depositRub <= 1e-6) return false
    if (!takeProfitPct.isFinite() || takeProfitPct <= 0.0) return false
    return y + 1e-6 >= depositRub * (takeProfitPct / 100.0)
}

/**
 * Решение фонового тейка. Заявки нельзя слать по last/дилеру:
 * лонг продаёт TATN по bid и покупает TATNP по ask.
 */
internal data class BackgroundTakeProfitDecision(
    val allow: Boolean,
    val reason: String,
    val entrySpreadPercent: Double?,
    val bookSpreadPercent: Double?,
    val pnlPercent: Double?,
)

internal fun evaluateBackgroundTakeProfitClose(
    sessionOpen: Boolean,
    side: ZStrategyPosition,
    tatnLots: Int,
    tatnpLots: Int,
    tatnBid: Double?,
    tatnAsk: Double?,
    tatnpBid: Double?,
    tatnpAsk: Double?,
    fillTatnRub: Double?,
    fillTatnpRub: Double?,
    depositRub: Double,
    takeProfitPct: Double,
    cashRub: Double? = null,
    entryTimeMsk: String? = null,
    nowMillis: Long = System.currentTimeMillis(),
): BackgroundTakeProfitDecision {
    val entrySpread = spreadPercentFromLegPrices(
        fillTatnRub ?: Double.NaN,
        fillTatnpRub ?: Double.NaN,
    )
    if (!sessionOpen) {
        return BackgroundTakeProfitDecision(
            allow = false,
            reason = "session_closed",
            entrySpreadPercent = entrySpread,
            bookSpreadPercent = null,
            pnlPercent = null,
        )
    }
    val closeTatn = closePriceForSignedLots(tatnLots, tatnBid, tatnAsk)
    val closeTatnp = closePriceForSignedLots(tatnpLots, tatnpBid, tatnpAsk)
    val bookSpread = if (closeTatn != null && closeTatnp != null) {
        spreadPercentFromLegPrices(closeTatn, closeTatnp)
    } else {
        null
    }
    fun deny(reason: String, pnl: Double? = null) = BackgroundTakeProfitDecision(
        allow = false,
        reason = reason,
        entrySpreadPercent = entrySpread,
        bookSpreadPercent = bookSpread,
        pnlPercent = pnl,
    )
    if (closeTatn == null || closeTatnp == null || bookSpread == null) {
        return deny("no_book")
    }
    if (entrySpread != null) {
        val worseThanEntry = when (side) {
            ZStrategyPosition.Long -> bookSpread + 1e-9 < entrySpread
            ZStrategyPosition.Short -> bookSpread - 1e-9 > entrySpread
            else -> true
        }
        if (worseThanEntry) return deny("exit_worse_than_entry")
    } else {
        return deny("no_entry")
    }
    val pnl = computeCloseNowPnl(
        tatnLots = tatnLots,
        tatnpLots = tatnpLots,
        fillTatnRub = fillTatnRub,
        fillTatnpRub = fillTatnpRub,
        quotes = PairQuotes(
            tatn = ShareQuote(bid = tatnBid, ask = tatnAsk),
            tatnp = ShareQuote(bid = tatnpBid, ask = tatnpAsk),
            source = "exchange",
        ),
        fallbackTatnLast = null,
        fallbackTatnpLast = null,
        depositRub = depositRub,
        cashRub = cashRub,
        entryTimeMsk = entryTimeMsk,
        nowMillis = nowMillis,
    )
    val pct = pnl?.pctFromDeposit
    if (pnl == null || pnl.quotesMode != "book" || pct == null || !pct.isFinite()) {
        return deny("no_book", pct)
    }
    if (pct + 1e-6 < takeProfitPct) return deny("net_below_tp", pct)
    return BackgroundTakeProfitDecision(
        allow = true,
        reason = "ok",
        entrySpreadPercent = entrySpread,
        bookSpreadPercent = bookSpread,
        pnlPercent = pct,
    )
}

internal fun formatBackgroundTakeProfitGate(decision: BackgroundTakeProfitDecision): String {
    fun pct(v: Double?): String =
        if (v == null || !v.isFinite()) "?" else String.format(Locale.US, "%.2f", v)
    return "entry=${pct(decision.entrySpreadPercent)} book=${pct(decision.bookSpreadPercent)} " +
        "pnlPct=${pct(decision.pnlPercent)} reason=${decision.reason}"
}

internal fun formatTakeProfitForecastLine(forecast: TakeProfitForecast): String {
    val spreadTxt = String.format(Locale.US, "%.1f", forecast.exitSpreadPercent)
        .replace('.', ',') + "%"
    val pnlRound = kotlin.math.round(forecast.netPnlRub).toDouble()
    val dep = kotlin.math.round(forecast.depositRub).toInt()
    val depFormatted = String.format(Locale.US, "%,d", dep).replace(',', ' ')
    val pctTxt = String.format(Locale.US, "%+.1f", forecast.pnlPercentFromDeposit)
        .replace('.', ',') + "%"
    return "при выходе ТП $spreadTxt ≈ ${formatRubSigned(pnlRound)} ($pctTxt от вложения $depFormatted ₽)"
}
