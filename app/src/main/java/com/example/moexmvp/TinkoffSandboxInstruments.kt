package com.example.moexmvp

import org.json.JSONObject
import java.util.Locale

/**
 * Fallback MOEX identifiers if [tinkoffResolveShareInstrumentId] fails.
 * Prefer UID/FIGI from FindInstrument (see PostOrderRequest.instrument_id).
 */
internal const val TINKOFF_MOEX_TATN_INSTRUMENT_ID = "TATN_TQBR"
internal const val TINKOFF_MOEX_TATNP_INSTRUMENT_ID = "TATNP_TQBR"
internal const val TINKOFF_MOEX_TATN_FIGI = "BBG004RVFFC0"
internal const val TINKOFF_MOEX_TATNP_FIGI = "BBG004S68829"

/** FIGI для PostOrder, если FindInstrument вернул ticker_TQBR или пусто. */
internal fun fallbackMoexShareFigi(ticker: String): String =
    if (ticker.trim().equals("TATNP", ignoreCase = true)) {
        TINKOFF_MOEX_TATNP_FIGI
    } else {
        TINKOFF_MOEX_TATN_FIGI
    }

/** PostOrder ждёт FIGI/UID, не `TATN_TQBR`. */
internal fun canonicalMoexShareInstrumentId(ticker: String, resolved: String?): String {
    val id = resolved?.trim().orEmpty()
    if (id.isEmpty() ||
        id.equals(TINKOFF_MOEX_TATN_INSTRUMENT_ID, ignoreCase = true) ||
        id.equals(TINKOFF_MOEX_TATNP_INSTRUMENT_ID, ignoreCase = true)
    ) {
        return fallbackMoexShareFigi(ticker)
    }
    return id
}

internal data class SpreadEntryLegSpec(
    val ticker: String,
    val buy: Boolean,
) {
    val orderDirection: String
        get() = if (buy) "ORDER_DIRECTION_BUY" else "ORDER_DIRECTION_SELL"
}

/**
 * Сначала шорт (продажа) — на счёт приходят деньги, затем покупка.
 * Иначе Long сначала покупает TATN и вторая нога (шорт префа) часто не проходит по марже.
 */
internal data class TinkoffMaxLots(
    val buyOwn: Int = 0,
    val buyMargin: Int = 0,
    val sellOwn: Int = 0,
    val sellMargin: Int = 0,
) {
    val maxBuy: Int get() = maxOf(buyOwn, buyMargin)
    val maxSell: Int get() = maxOf(sellOwn, sellMargin)
}

internal fun jsonLotsField(o: org.json.JSONObject?, vararg keys: String): Int {
    if (o == null) return 0
    for (k in keys) {
        when (val v = o.opt(k)) {
            is Number -> return v.toInt().coerceAtLeast(0)
            is String -> v.trim().toIntOrNull()?.let { return it.coerceAtLeast(0) }
            is org.json.JSONObject -> quotationUnitsToDouble(v)?.toInt()?.let { return it.coerceAtLeast(0) }
        }
    }
    return 0
}

internal fun parseTinkoffMaxLots(root: org.json.JSONObject): TinkoffMaxLots {
    val buy = root.optJSONObject("buyLimits") ?: root.optJSONObject("buy_limits")
    val buyM = root.optJSONObject("buyMarginLimits") ?: root.optJSONObject("buy_margin_limits")
    val sell = root.optJSONObject("sellLimits") ?: root.optJSONObject("sell_limits")
    val sellM = root.optJSONObject("sellMarginLimits") ?: root.optJSONObject("sell_margin_limits")
    return TinkoffMaxLots(
        buyOwn = jsonLotsField(buy, "buyMaxLots", "buy_max_lots", "maxLots", "max_lots"),
        buyMargin = jsonLotsField(buyM, "buyMaxLots", "buy_max_lots", "maxLots", "max_lots"),
        sellOwn = jsonLotsField(sell, "sellMaxLots", "sell_max_lots", "maxLots", "max_lots"),
        sellMargin = jsonLotsField(sellM, "sellMaxLots", "sell_max_lots", "maxLots", "max_lots"),
    )
}

internal fun maxLotsForLeg(spec: SpreadEntryLegSpec, tatn: TinkoffMaxLots, tatnp: TinkoffMaxLots): Int {
    val book = if (spec.ticker.equals("TATNP", ignoreCase = true)) tatnp else tatn
    return if (spec.buy) book.maxBuy else book.maxSell
}

internal fun clampSpreadLotsToBrokerMax(
    wantLots: Int,
    signalType: StrategySignalType,
    tatn: TinkoffMaxLots,
    tatnp: TinkoffMaxLots,
): Int {
    var cap = wantLots.coerceAtLeast(0)
    for (spec in spreadEntryLegPlan(signalType)) {
        cap = minOf(cap, maxLotsForLeg(spec, tatn, tatnp))
    }
    return cap.coerceAtLeast(0)
}

internal fun explainSpreadMaxLotsBlock(
    signalType: StrategySignalType,
    wantLots: Int,
    tatn: TinkoffMaxLots,
    tatnp: TinkoffMaxLots,
): String? {
    val plan = spreadEntryLegPlan(signalType)
    val dirRu = if (signalType == StrategySignalType.EnterShort) "Short" else "Long"
    for (spec in plan) {
        val max = maxLotsForLeg(spec, tatn, tatnp)
        if (!spec.buy && max < 1) {
            val other = if (spec.ticker == "TATN") "TATNP" else "TATN"
            return "$dirRu не открыть: T‑Invest даёт продать ${spec.ticker} 0 лот " +
                "(нет бумаги в займ или пустой дилерский стакан на выходных). " +
                "Шорт $other при этом может проходить — поэтому Long сегодня открывался, а Short нет."
        }
        if (spec.buy && max < 1) {
            return "$dirRu не открыть: T‑Invest даёт купить ${spec.ticker} 0 лот."
        }
    }
    val cap = clampSpreadLotsToBrokerMax(wantLots, signalType, tatn, tatnp)
    if (cap < 1) {
        return "$dirRu не открыть: брокер даёт 0 лот по ногам пары (нужно $wantLots)."
    }
    return null
}

internal fun spreadEntryLegPlan(signalType: StrategySignalType): List<SpreadEntryLegSpec> =
    when (signalType) {
        StrategySignalType.EnterLong -> listOf(
            SpreadEntryLegSpec("TATNP", buy = false),
            SpreadEntryLegSpec("TATN", buy = true),
        )
        StrategySignalType.EnterShort -> listOf(
            SpreadEntryLegSpec("TATN", buy = false),
            SpreadEntryLegSpec("TATNP", buy = true),
        )
        else -> throw IllegalArgumentException("Только EnterLong / EnterShort")
    }

internal fun knownTatnInstrumentIds(extra: Set<String> = emptySet()): Set<String> =
    buildSet {
        add(TINKOFF_MOEX_TATN_INSTRUMENT_ID)
        add(TINKOFF_MOEX_TATN_FIGI)
        extra.forEach { id -> add(id.uppercase(Locale.US)) }
    }

internal fun knownTatnpInstrumentIds(extra: Set<String> = emptySet()): Set<String> =
    buildSet {
        add(TINKOFF_MOEX_TATNP_INSTRUMENT_ID)
        add(TINKOFF_MOEX_TATNP_FIGI)
        extra.forEach { id -> add(id.uppercase(Locale.US)) }
    }

/**
 * TATN / TATNP из ticker **или** figi/instrumentUid (GetPortfolio часто без ticker, как GetOperations).
 * TATNP проверяется раньше TATN, чтобы `TATNP_TQBR` не схлопнулся в TATN.
 */
internal fun resolveTatnTatnpTicker(
    obj: JSONObject,
    extraTatnIds: Set<String> = emptySet(),
    extraTatnpIds: Set<String> = emptySet(),
): String? {
    fun field(vararg keys: String): String {
        for (k in keys) {
            val v = obj.optString(k, "").trim()
            if (v.isNotEmpty()) return v.uppercase(Locale.US)
        }
        return ""
    }
    val ticker = field("ticker", "Ticker")
    if (ticker == "TATNP") return "TATNP"
    if (ticker == "TATN") return "TATN"
    val ids = listOf(
        field("instrumentUid", "instrument_uid", "uid"),
        field("figi", "FIGI"),
    ).filter { it.isNotEmpty() }
    val tatnp = knownTatnpInstrumentIds(extraTatnpIds)
    val tatn = knownTatnInstrumentIds(extraTatnIds)
    if (ids.any { it in tatnp || "TATNP" in it }) return "TATNP"
    if (ids.any { it in tatn || "TATN" in it }) return "TATN"
    return null
}
