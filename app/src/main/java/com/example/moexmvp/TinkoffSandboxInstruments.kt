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
