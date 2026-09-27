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
    /** Уровень для графика: текущий indicative spread + требуемое изменение executable spread. */
    val exitSpreadPercent: Double,
    val executableExitSpreadPercent: Double,
    val entrySpreadPercent: Double,
    val targetTatnRub: Double,
    val targetTatnpRub: Double,
    val netPnlRub: Double,
    val pnlPercentFromDeposit: Double,
    val depositRub: Double,
    val entryCommissionRub: Double,
    val exitCommissionRub: Double,
    val overnightShortRub: Double,
    val overnightMarginLoanRub: Double,
    val overnightDays: Long,
    val takeProfitPct: Double = DEFAULT_TAKE_PROFIT_PCT,
)

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
    depositRub: Double?,
    cashRub: Double?,
    tatnLots: Int,
    tatnpLots: Int,
    fillTatnRub: Double?,
    fillTatnpRub: Double?,
    closeTatnRub: Double?,
    closeTatnpRub: Double?,
    currentReferenceSpreadPercent: Double?,
    entryTimeMsk: String?,
    takeProfitPct: Double = DEFAULT_TAKE_PROFIT_PCT,
    nowMillis: Long = System.currentTimeMillis(),
): TakeProfitForecast? {
    if (side == ZStrategyPosition.Flat) return null
    if (side == ZStrategyPosition.Long && (tatnLots <= 0 || tatnpLots >= 0)) return null
    if (side == ZStrategyPosition.Short && (tatnLots >= 0 || tatnpLots <= 0)) return null
    val fillTatn = fillTatnRub?.takeIf { it > 0.0 && it.isFinite() } ?: return null
    val fillTatnp = fillTatnpRub?.takeIf { it > 0.0 && it.isFinite() } ?: return null
    val deposit = depositRub?.takeIf { it > 0 } ?: return null
    val tp = takeProfitPct.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val anchorTatnp = closeTatnpRub?.takeIf { it > 0.0 && it.isFinite() } ?: fillTatnp
    val currentTatn = closeTatnRub?.takeIf { it > 0.0 && it.isFinite() } ?: fillTatn
    val entrySpread = spreadPercentFromLegPrices(fillTatn, fillTatnp) ?: return null
    val currentExecutableSpread = spreadPercentFromLegPrices(currentTatn, anchorTatnp) ?: return null
    val entryNotional = pairNotionalFromPrices(tatnLots, fillTatn, tatnpLots, fillTatnp)
    val entryCommission = premiumCommissionRub(entryNotional)
    val ovnDays = entryTimeMsk?.let { entryLabel ->
        val end = formatPortfolioExecutionTableMsk(nowMillis)
        overnightDays(
            portfolioDateLabelFromMskTableTime(entryLabel),
            portfolioDateLabelFromMskTableTime(end),
        )
    } ?: 0L
    val overnightLoan = borrowedCashOvernightRub(cashRub, ovnDays)
    val targetNet = deposit * (tp / 100.0)

    data class TargetResult(
        val spread: Double,
        val tatn: Double,
        val tatnp: Double,
        val net: Double,
        val exitCommission: Double,
        val overnightShort: Double,
    )

    fun resultAt(spread: Double): TargetResult {
        val targetTatnp = anchorTatnp
        val targetTatn = targetTatnp * (1.0 + spread / 100.0)
        val gross = signedLegsUnrealizedRub(
            tatnLots = tatnLots,
            tatnpLots = tatnpLots,
            fillTatn = fillTatn,
            fillTatnp = fillTatnp,
            nowTatn = targetTatn,
            nowTatnp = targetTatnp,
        ) ?: Double.NaN
        val exitNotional = pairNotionalFromPrices(tatnLots, targetTatn, tatnpLots, targetTatnp)
        val exitCommission = premiumCommissionRub(exitNotional)
        val uncovered = shortUncoveredNowRub(
            tatnLots = tatnLots,
            tatnpLots = tatnpLots,
            closeTatn = targetTatn,
            closeTatnp = targetTatnp,
            fillTatn = fillTatn,
            fillTatnp = fillTatnp,
        )
        val overnightShort = overnightFeePerDayRub(uncovered) * ovnDays
        return TargetResult(
            spread = spread,
            tatn = targetTatn,
            tatnp = targetTatnp,
            net = gross - entryCommission - exitCommission - overnightShort - overnightLoan,
            exitCommission = exitCommission,
            overnightShort = overnightShort,
        )
    }

    var nearSpread = currentExecutableSpread
    var near = resultAt(nearSpread)
    if (!near.net.isFinite()) return null
    var farSpread = nearSpread
    var far = near
    if (near.net < targetNet) {
        var steps = 0
        while (far.net < targetNet && steps < 800) {
            farSpread += if (side == ZStrategyPosition.Long) 0.25 else -0.25
            if (farSpread <= -99.0 || farSpread >= 200.0) return null
            far = resultAt(farSpread)
            steps++
        }
        if (far.net < targetNet) return null
    }

    var low = minOf(nearSpread, farSpread)
    var high = maxOf(nearSpread, farSpread)
    repeat(80) {
        val mid = (low + high) / 2.0
        val value = resultAt(mid).net
        if (side == ZStrategyPosition.Long) {
            if (value < targetNet) low = mid else high = mid
        } else {
            if (value >= targetNet) low = mid else high = mid
        }
    }
    val targetSpread = if (side == ZStrategyPosition.Long) high else low
    val solved = resultAt(targetSpread)
    val referenceNow = currentReferenceSpreadPercent?.takeIf { it.isFinite() }
        ?: currentExecutableSpread
    val chartSpread = referenceNow + (solved.spread - currentExecutableSpread)
    val pct = (solved.net / deposit) * 100.0
    return TakeProfitForecast(
        exitSpreadPercent = chartSpread,
        executableExitSpreadPercent = solved.spread,
        entrySpreadPercent = entrySpread,
        targetTatnRub = solved.tatn,
        targetTatnpRub = solved.tatnp,
        netPnlRub = solved.net,
        pnlPercentFromDeposit = pct,
        depositRub = deposit,
        entryCommissionRub = entryCommission,
        exitCommissionRub = solved.exitCommission,
        overnightShortRub = solved.overnightShort,
        overnightMarginLoanRub = overnightLoan,
        overnightDays = ovnDays,
        takeProfitPct = tp,
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

internal fun formatTakeProfitForecastLine(forecast: TakeProfitForecast): String {
    val spreadTxt = String.format(Locale.US, "%.2f", forecast.exitSpreadPercent)
        .replace('.', ',') + "%"
    val pnlRound = kotlin.math.round(forecast.netPnlRub).toDouble()
    val dep = kotlin.math.round(forecast.depositRub).toInt()
    val depFormatted = String.format(Locale.US, "%,d", dep).replace(',', ' ')
    val pctTxt = String.format(Locale.US, "%+.1f", forecast.pnlPercentFromDeposit)
        .replace('.', ',') + "%"
    val commissions = kotlin.math.round(forecast.entryCommissionRub + forecast.exitCommissionRub)
    val carry = kotlin.math.round(forecast.overnightShortRub + forecast.overnightMarginLoanRub)
    return "при выходе ТП $spreadTxt ≈ ${formatRubSigned(pnlRound)} " +
        "($pctTxt от вложения $depFormatted ₽)\n" +
        "учтено: комиссии ${commissions.toLong()} ₽ · перенос ${forecast.overnightDays} д. ${carry.toLong()} ₽"
}
