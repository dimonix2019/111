package com.example.moexmvp

import java.util.Locale
import kotlin.math.max

/** Цель Take Profit в % от вложения (как на web-столе). */
internal const val DEFAULT_TAKE_PROFIT_PCT = 2.0

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
        val end = formatPortfolioExecutionTableMsk(System.currentTimeMillis())
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
