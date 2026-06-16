package com.example.moexmvp

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal data class SpreadLotSizingResult(
    val quantityLots: Int,
    val cashRub: Double,
    val reserveRub: Double,
    val availableRub: Double,
    val goPerLotRub: Double,
    val pairNotionalPerLotRub: Double,
    val priceTatN: Double,
    val priceTatNp: Double,
    val lotSize: Int,
    val executionNotionalRub: Double,
    val liquidPortfolioRub: Double? = null,
    val marginHeadroomRub: Double? = null,
    val lotsFromCash: Int = 0,
    val lotsFromLeverage: Int = 0,
    val lotsFromMarginHeadroom: Int = 0,
)

internal data class SpreadLotSizingInput(
    val cashRub: Double,
    val priceTatN: Double,
    val priceTatNp: Double,
    val lotSize: Int = 1,
    val reserveFraction: Double = SPREAD_LOT_RESERVE_CASH_FRACTION,
    val reserveMinRub: Double = SPREAD_LOT_RESERVE_MIN_RUB,
    val marginRatePerLeg: Double = SPREAD_LOT_MARGIN_RATE_PER_LEG,
    val commissionBufferFraction: Double = SPREAD_LOT_COMMISSION_BUFFER_FRACTION,
    val minLots: Int = SPREAD_LOT_MIN_LOTS,
    val maxLots: Int = SPREAD_LOT_MAX_LOTS,
    val liquidPortfolioRub: Double? = null,
    val correctedMarginRub: Double? = null,
    val leverageForNotional: Double? = null,
    val marginPairFraction: Double = SPREAD_LOT_MARGIN_PAIR_FRACTION,
)

internal fun spreadPairNotionalRub(
    priceTatN: Double,
    priceTatNp: Double,
    lotSize: Int,
    quantityLots: Int,
): Double {
    val lot = lotSize.coerceAtLeast(1)
    val qty = quantityLots.coerceAtLeast(1)
    return (priceTatN.coerceAtLeast(0.0) + priceTatNp.coerceAtLeast(0.0)) * lot * qty
}

internal fun estimateSpreadMarginPerLotRub(
    priceTatN: Double,
    priceTatNp: Double,
    lotSize: Int,
    marginPairFraction: Double = SPREAD_LOT_MARGIN_PAIR_FRACTION,
): Double {
    val pairNotionalPerLotRub = (priceTatN.coerceAtLeast(0.0) + priceTatNp.coerceAtLeast(0.0)) * lotSize.coerceAtLeast(1)
    return pairNotionalPerLotRub * marginPairFraction.coerceAtLeast(0.01)
}

internal fun computeSpreadQuantityLots(input: SpreadLotSizingInput): SpreadLotSizingResult {
    val lotSize = input.lotSize.coerceAtLeast(1)
    val priceTatN = input.priceTatN.coerceAtLeast(0.0)
    val priceTatNp = input.priceTatNp.coerceAtLeast(0.0)
    require(priceTatN > 0.0 && priceTatNp > 0.0) {
        "Некорректные цены TATN/TATNP для расчёта лотов"
    }
    val pairNotionalPerLotRub = (priceTatN + priceTatNp) * lotSize
    val goBuyLegRub = priceTatN * lotSize
    val goSellLegRub = priceTatNp * lotSize * input.marginRatePerLeg
    val commissionBufferRub = pairNotionalPerLotRub * input.commissionBufferFraction
    val goPerLotRub = goBuyLegRub + goSellLegRub + commissionBufferRub
    val marginPerLotRub = estimateSpreadMarginPerLotRub(
        priceTatN,
        priceTatNp,
        lotSize,
        input.marginPairFraction,
    )

    val liquid = input.liquidPortfolioRub?.takeIf { it > 0.0 }
    val reserveBase = liquid ?: input.cashRub
    val reserveRub = max(input.reserveMinRub, reserveBase * input.reserveFraction)
    val availableCashRub = (input.cashRub - reserveRub).coerceAtLeast(0.0)
    val lotsFromCash = if (goPerLotRub <= 0.0) 0 else floor(availableCashRub / goPerLotRub).toInt()

    val leverage = input.leverageForNotional?.takeIf { it >= 1.0 }
    val lotsFromLeverage = if (liquid != null && leverage != null && pairNotionalPerLotRub > 0.0) {
        floor(liquid * leverage / pairNotionalPerLotRub).toInt()
    } else {
        0
    }

    val corrected = input.correctedMarginRub?.coerceAtLeast(0.0)
    val marginHeadroomRub = if (liquid != null && corrected != null) {
        (liquid - corrected - reserveRub).coerceAtLeast(0.0)
    } else {
        null
    }
    val lotsFromMarginHeadroom = if (marginHeadroomRub != null && marginHeadroomRub > 0.0 && marginPerLotRub > 0.0) {
        floor(marginHeadroomRub / marginPerLotRub).toInt()
    } else {
        0
    }

    val mostlyFlat = liquid != null && corrected != null && corrected < liquid * 0.05
    val rawLots = when {
        leverage != null && liquid != null && mostlyFlat ->
            max(lotsFromCash, lotsFromLeverage)
        leverage != null && liquid != null ->
            max(lotsFromCash, min(lotsFromLeverage, lotsFromMarginHeadroom))
        marginHeadroomRub != null ->
            max(lotsFromCash, lotsFromMarginHeadroom)
        else -> lotsFromCash
    }
    val quantityLots = rawLots.coerceIn(0, input.maxLots)
    return SpreadLotSizingResult(
        quantityLots = quantityLots,
        cashRub = input.cashRub,
        reserveRub = reserveRub,
        availableRub = availableCashRub,
        goPerLotRub = goPerLotRub,
        pairNotionalPerLotRub = pairNotionalPerLotRub,
        priceTatN = priceTatN,
        priceTatNp = priceTatNp,
        lotSize = lotSize,
        executionNotionalRub = spreadPairNotionalRub(
            priceTatN,
            priceTatNp,
            lotSize,
            quantityLots.coerceAtLeast(1),
        ),
        liquidPortfolioRub = liquid,
        marginHeadroomRub = marginHeadroomRub,
        lotsFromCash = lotsFromCash,
        lotsFromLeverage = lotsFromLeverage,
        lotsFromMarginHeadroom = lotsFromMarginHeadroom,
    )
}

internal fun parsePortfolioCashRubDouble(portfolioJson: JSONObject): Double? {
    val q = findPortfolioMoneyQuotation(portfolioJson) ?: return null
    return quotationUnitsToDouble(q)
}

internal suspend fun resolveMoexSpreadPrices(context: Context): Pair<Double, Double> {
    val points = loadZStrategySignalSeries(context.applicationContext, PortfolioM15LoadMode.CACHE_ONLY)
    val last = points.lastOrNull()
    if (last != null && last.tatnClose > 0.0 && last.tatnpClose > 0.0) {
        return last.tatnClose to last.tatnpClose
    }
    val market = loadCurrentPortfolioMarketSnapshot(context.applicationContext, forceNetworkRefresh = false)
    val till = java.time.Instant.ofEpochMilli(market.timestampMillis)
        .atZone(moexZoneId)
        .toLocalDate()
        .plusDays(1)
    val from = till.minusDays(3)
    val tatn = loadCandleBars("TATN", from, till, interval = 10).lastOrNull()?.close
    val tatnp = loadCandleBars("TATNP", from, till, interval = 10).lastOrNull()?.close
    if (tatn != null && tatnp != null && tatn > 0.0 && tatnp > 0.0) {
        return tatn to tatnp
    }
    throw IOException("Не удалось получить цены TATN/TATNP для расчёта лотов")
}

internal suspend fun resolveSpreadOrderQuantityLots(
    mode: TinkoffExecutionMode,
    token: String,
    accountId: String,
    context: Context,
): SpreadLotSizingResult {
    val portfolio = tinkoffGetPortfolio(mode, token, accountId)
    val cashRub = parsePortfolioCashRubDouble(portfolio)
        ?: throw IOException("Не удалось прочитать деньги на счёте (totalAmountCurrencies)")
    val (priceTatN, priceTatNp) = resolveMoexSpreadPrices(context)
    val marginAttrs = if (mode == TinkoffExecutionMode.Prod) {
        runCatching { tinkoffGetMarginAttributes(mode, token, accountId) }.getOrNull()
    } else {
        null
    }
    val leverage = if (mode == TinkoffExecutionMode.Prod) {
        TinkoffSandboxStorage.getSandboxNotifyLeverage(context)
            .coerceIn(1.0, 30.0)
    } else {
        null
    }
    val sizing = computeSpreadQuantityLots(
        SpreadLotSizingInput(
            cashRub = cashRub,
            priceTatN = priceTatN,
            priceTatNp = priceTatNp,
            lotSize = 1,
            liquidPortfolioRub = marginAttrs?.liquidPortfolioRub,
            correctedMarginRub = marginAttrs?.correctedMarginRub,
            leverageForNotional = leverage,
        )
    )
    if (sizing.quantityLots < SPREAD_LOT_MIN_LOTS) {
        val marginHint = marginAttrs?.let { attrs ->
            " ликвид=${"%.0f".format(attrs.liquidPortfolioRub)} ₽, скорр.маржа=${"%.0f".format(attrs.correctedMarginRub)} ₽"
        }.orEmpty()
        throw IOException(
            "Недостаточно средств для входа: на счёте ${"%.0f".format(cashRub)} ₽, " +
                "после резерва ${"%.0f".format(sizing.availableRub)} ₽, " +
                "нужно ≈${"%.0f".format(sizing.goPerLotRub)} ₽ на 1 лот пары$marginHint"
        )
    }
    return sizing
}

internal fun spreadVolumeText(quantityLots: Int): String {
    val qty = quantityLots.coerceAtLeast(1)
    return "$qty+$qty лот"
}

internal fun spreadLegSideRu(buy: Boolean, quantityLots: Int): String {
    val qty = quantityLots.coerceAtLeast(1)
    return if (buy) "покупка $qty лот" else "продажа $qty лот"
}

internal fun tradeExecutionNotionalRub(exec: SandboxSpreadExecUi, fallbackRub: Double): Double =
    exec.executionNotionalRub.takeIf { it > 0.0 } ?: fallbackRub
