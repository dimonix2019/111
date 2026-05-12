package com.example.moexmvp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.Locale
import java.util.UUID

private val jsonMedia = "application/json".toMediaType()

/** Strip accidental "Bearer " prefix and whitespace (users often paste full header). */
internal fun normalizeInvestToken(raw: String): String {
    var t = raw.trim()
    if (t.startsWith("Bearer ", ignoreCase = true)) {
        t = t.substring(7).trim()
    }
    return t.trim()
}

private fun parseJsonObjectOrEmpty(text: String): JSONObject {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return JSONObject()
    return try {
        JSONTokener(trimmed).nextValue() as? JSONObject ?: JSONObject()
    } catch (_: Exception) {
        JSONObject().put("_raw", trimmed)
    }
}

private fun extractApiErrorMessage(httpCode: Int, body: String): String {
    val o = runCatching { JSONObject(body) }.getOrNull()
        ?: return body.ifBlank { "HTTP $httpCode" }
    val msg = sequenceOf(
        o.optString("message"),
        o.optJSONObject("status")?.optString("message") ?: "",
        o.optString("description"),
        o.optString("error"),
        o.optJSONObject("error")?.optString("message") ?: "",
        o.optJSONObject("details")?.optString("message") ?: ""
    ).firstOrNull { it.isNotBlank() }
    val code = o.opt("code")?.toString()?.takeIf { it.isNotBlank() }
    val snippet = body.trim().replace("\n", " ").take(320)
    val vague = msg.isNullOrBlank() ||
        msg.equals("Ошибка", ignoreCase = true) ||
        msg.equals("error", ignoreCase = true) ||
        msg.length < 4
    return buildString {
        append("HTTP $httpCode")
        if (code != null) append(" · code=").append(code)
        when {
            !msg.isNullOrBlank() && !vague -> append(": ").append(msg)
            !msg.isNullOrBlank() && vague && snippet.isNotEmpty() -> {
                append(": ").append(msg)
                append(" · ").append(snippet)
            }
            !msg.isNullOrBlank() && vague -> append(": ").append(msg)
            snippet.isNotEmpty() -> append(": ").append(snippet)
        }
    }
}

private suspend fun tinkoffSbxPostRaw(
    prefixes: List<String>,
    token: String,
    method: String,
    bodyJson: String
): String =
    withContext(Dispatchers.IO) {
        val norm = normalizeInvestToken(token)
        var lastNetwork: IOException? = null
        for (prefix in prefixes) {
            val url = "$prefix/$method"
            val req = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMedia))
                .header("Authorization", "Bearer $norm")
                .header("Accept", "application/json")
                .build()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IOException("${extractApiErrorMessage(resp.code, text)} · $url")
                    }
                    return@withContext text
                }
            } catch (e: IOException) {
                if (e.message?.startsWith("HTTP ") == true) throw e
                lastNetwork = e
            }
        }
        throw lastNetwork ?: IOException("T‑Invest REST: нет доступных хостов")
    }

private suspend fun tinkoffSandboxPostRaw(token: String, method: String, bodyJson: String): String =
    tinkoffSbxPostRaw(TINVEST_SANDBOX_REST_PREFIXES, token, method, bodyJson)

private suspend fun tinkoffInstrumentsPostRaw(token: String, method: String, bodyJson: String): String =
    tinkoffSbxPostRaw(TINVEST_SANDBOX_INSTRUMENTS_PREFIXES, token, method, bodyJson)

internal suspend fun tinkoffInstrumentsPostAsync(token: String, method: String, body: JSONObject): JSONObject {
    val raw = tinkoffInstrumentsPostRaw(token, method, body.toString())
    return parseJsonObjectOrEmpty(raw)
}

internal suspend fun tinkoffSandboxPostAsync(token: String, method: String, body: JSONObject): JSONObject {
    val raw = tinkoffSandboxPostRaw(token, method, body.toString())
    return parseJsonObjectOrEmpty(raw)
}

private fun JSONObject.firstNonBlankString(vararg keys: String): String? {
    for (k in keys) {
        val v = optString(k, "").trim()
        if (v.isNotEmpty()) return v
    }
    return null
}

private fun extractAccountId(root: JSONObject): String? {
    root.firstNonBlankString("accountId", "account_id", "id")?.let { return it }
    val it = root.keys()
    while (it.hasNext()) {
        val key = it.next()
        root.optJSONObject(key)?.firstNonBlankString("accountId", "account_id", "id")?.let { return it }
    }
    return null
}

internal suspend fun tinkoffOpenSandboxAccount(token: String, name: String): String {
    val root = tinkoffSandboxPostAsync(token, "OpenSandboxAccount", JSONObject().put("name", name))
    return extractAccountId(root) ?: throw IOException("Нет accountId в ответе: $root")
}

internal data class SandboxAccountRow(val id: String, val name: String)

private fun parseSandboxAccounts(root: JSONObject): List<SandboxAccountRow> {
    val arr: JSONArray = root.optJSONArray("accounts")
        ?: root.optJSONArray("Accounts")
        ?: JSONArray()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.firstNonBlankString("id", "accountId", "account_id") ?: continue
            val name = o.firstNonBlankString("name", "Name").orEmpty()
            add(SandboxAccountRow(id = id, name = name))
        }
    }
}

internal suspend fun tinkoffGetSandboxAccounts(token: String): List<SandboxAccountRow> {
    var root = tinkoffSandboxPostAsync(token, "GetSandboxAccounts", JSONObject())
    var rows = parseSandboxAccounts(root)
    if (rows.isEmpty()) {
        root = tinkoffSandboxPostAsync(
            token,
            "GetSandboxAccounts",
            JSONObject().put("status", "ACCOUNT_STATUS_ALL")
        )
        rows = parseSandboxAccounts(root)
    }
    return rows
}

private fun payInBodyCamel(accountId: String, unitsRub: Long): JSONObject {
    val amount = JSONObject()
        .put("currency", "RUB")
        .put("units", unitsRub)
        .put("nano", 0)
    return JSONObject()
        .put("accountId", accountId)
        .put("amount", amount)
}

/** Официальная схема SandboxPayInRequest: account_id + MoneyValue (ISO валюта, units/nano — числа). */
private fun payInBodySnake(accountId: String, unitsRub: Long): JSONObject {
    val amount = JSONObject()
        .put("currency", "RUB")
        .put("units", unitsRub)
        .put("nano", 0)
    return JSONObject()
        .put("account_id", accountId)
        .put("amount", amount)
}

internal suspend fun tinkoffSandboxPayIn(token: String, accountId: String, unitsRub: Long): JSONObject {
    var last: IOException? = null
    for (body in listOf(payInBodySnake(accountId, unitsRub), payInBodyCamel(accountId, unitsRub))) {
        try {
            return tinkoffSandboxPostAsync(token, "SandboxPayIn", body)
        } catch (e: IOException) {
            last = e
        }
    }
    throw last ?: IOException("SandboxPayIn: неизвестная ошибка")
}

private fun postMarketBodyCamel(
    accountId: String,
    instrumentId: String,
    direction: String,
    quantityLots: Int,
    orderId: String
): JSONObject {
    val price = JSONObject()
        .put("units", 0)
        .put("nano", 0)
    return JSONObject()
        .put("accountId", accountId)
        .put("instrumentId", instrumentId)
        .put("quantity", quantityLots)
        .put("direction", direction)
        .put("orderType", "ORDER_TYPE_MARKET")
        .put("orderId", orderId)
        .put("price", price)
        .put("timeInForce", "TIME_IN_FORCE_DAY")
        .put("confirmMarginTrade", true)
}

private fun postMarketBodySnake(
    accountId: String,
    instrumentId: String,
    direction: String,
    quantityLots: Int,
    orderId: String
): JSONObject {
    val price = JSONObject()
        .put("units", 0)
        .put("nano", 0)
    return JSONObject()
        .put("account_id", accountId)
        .put("instrument_id", instrumentId)
        .put("quantity", quantityLots)
        .put("direction", direction)
        .put("order_type", "ORDER_TYPE_MARKET")
        .put("order_id", orderId)
        .put("price", price)
        .put("time_in_force", "TIME_IN_FORCE_DAY")
        .put("confirm_margin_trade", true)
}

private fun postMarketBodyCamelNoPrice(
    accountId: String,
    instrumentId: String,
    direction: String,
    quantityLots: Int,
    orderId: String
): JSONObject = JSONObject()
    .put("accountId", accountId)
    .put("instrumentId", instrumentId)
    .put("quantity", quantityLots)
    .put("direction", direction)
    .put("orderType", "ORDER_TYPE_MARKET")
    .put("orderId", orderId)
    .put("timeInForce", "TIME_IN_FORCE_DAY")
    .put("confirmMarginTrade", true)

private fun postMarketBodySnakeNoPrice(
    accountId: String,
    instrumentId: String,
    direction: String,
    quantityLots: Int,
    orderId: String
): JSONObject = JSONObject()
    .put("account_id", accountId)
    .put("instrument_id", instrumentId)
    .put("quantity", quantityLots)
    .put("direction", direction)
    .put("order_type", "ORDER_TYPE_MARKET")
    .put("order_id", orderId)
    .put("time_in_force", "TIME_IN_FORCE_DAY")
    .put("confirm_margin_trade", true)

/** One market order on the sandbox account (1 lot by default). */
internal suspend fun tinkoffPostSandboxMarketOrder(
    token: String,
    accountId: String,
    instrumentId: String,
    orderDirection: String,
    quantityLots: Int = 1
): JSONObject {
    var last: IOException? = null
    val bodies = listOf(
        { postMarketBodyCamel(accountId, instrumentId, orderDirection, quantityLots, UUID.randomUUID().toString()) },
        { postMarketBodySnake(accountId, instrumentId, orderDirection, quantityLots, UUID.randomUUID().toString()) },
        { postMarketBodyCamelNoPrice(accountId, instrumentId, orderDirection, quantityLots, UUID.randomUUID().toString()) },
        { postMarketBodySnakeNoPrice(accountId, instrumentId, orderDirection, quantityLots, UUID.randomUUID().toString()) }
    )
    for (factory in bodies) {
        try {
            return tinkoffSandboxPostAsync(token, "PostSandboxOrder", factory())
        } catch (e: IOException) {
            last = e
        }
    }
    throw last ?: IOException("PostSandboxOrder: неизвестная ошибка")
}

/**
 * REST PostOrder expects [instrument_id] as FIGI or instrument UID, not always `TICKER_TQBR`.
 * Resolves via InstrumentsService/FindInstrument on the sandbox host.
 */
internal suspend fun tinkoffResolveShareInstrumentId(token: String, ticker: String): String {
    val want = ticker.trim().uppercase(Locale.US)
    val root = tinkoffInstrumentsPostAsync(
        token,
        "FindInstrument",
        JSONObject()
            .put("query", want)
            .put("instrumentKind", "INSTRUMENT_TYPE_SHARE")
            .put("apiTradeAvailableFlag", true)
    )
    val arr = root.optJSONArray("instruments")
        ?: root.optJSONArray("Instruments")
        ?: throw IOException("FindInstrument: нет массива instruments · ${root.toString().take(400)}")
    val matches = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("ticker", o.optString("Ticker", "")).trim().uppercase(Locale.US)
            if (t == want) add(o)
        }
    }
    if (matches.isEmpty()) {
        throw IOException("FindInstrument: тикер $want не найден · ${root.toString().take(400)}")
    }
    val tqbr = matches.firstOrNull {
        it.optString("classCode", it.optString("class_code", "")).equals("TQBR", ignoreCase = true)
    }
    val chosen = tqbr ?: matches.first()
    return chosen.firstNonBlankString(
        "instrumentUid", "instrument_uid", "uid",
        "figi", "FIGI"
    ) ?: throw IOException("FindInstrument: нет uid/figi для $want · ${chosen.toString().take(400)}")
}

/**
 * Opens a Z-spread position on the sandbox: 2 MOEX equity legs (1 lot each).
 * LONG spread = long TATN / short TATNP; SHORT spread = the opposite.
 */
internal suspend fun tinkoffSandboxExecuteSpreadEntry(
    token: String,
    accountId: String,
    signalType: StrategySignalType
) {
    val tatn = try {
        tinkoffResolveShareInstrumentId(token, "TATN")
    } catch (_: Exception) {
        TINKOFF_MOEX_TATN_INSTRUMENT_ID
    }
    val tatnp = try {
        tinkoffResolveShareInstrumentId(token, "TATNP")
    } catch (_: Exception) {
        TINKOFF_MOEX_TATNP_INSTRUMENT_ID
    }
    val buy = "ORDER_DIRECTION_BUY"
    val sell = "ORDER_DIRECTION_SELL"
    when (signalType) {
        StrategySignalType.EnterLong -> {
            tinkoffPostSandboxMarketOrder(token, accountId, tatn, buy)
            tinkoffPostSandboxMarketOrder(token, accountId, tatnp, sell)
        }
        StrategySignalType.EnterShort -> {
            tinkoffPostSandboxMarketOrder(token, accountId, tatnp, buy)
            tinkoffPostSandboxMarketOrder(token, accountId, tatn, sell)
        }
        else -> throw IOException("Только EnterLong / EnterShort")
    }
}

internal suspend fun tinkoffGetSandboxPortfolio(token: String, accountId: String): JSONObject {
    val attempts = listOf(
        JSONObject().put("accountId", accountId).put("currency", "RUB"),
        JSONObject().put("account_id", accountId).put("currency", "RUB")
    )
    var last: IOException? = null
    for (body in attempts) {
        try {
            return tinkoffSandboxPostAsync(token, "GetSandboxPortfolio", body)
        } catch (e: IOException) {
            last = e
        }
    }
    throw last ?: IOException("GetSandboxPortfolio: неизвестная ошибка")
}

private fun quotationUnitsToDouble(o: JSONObject): Double? {
    val nano = o.optLong("nano", o.optLong("Nano", 0L))
    val raw = o.opt("units") ?: o.opt("Units")
    val units = when (raw) {
        null -> 0.0
        is Number -> raw.toDouble()
        is String -> raw.trim().toDoubleOrNull() ?: return null
        else -> return null
    }
    return units + nano / 1_000_000_000.0
}

/** Short rub summary for UI; null if structure differs. */
internal fun formatSandboxPortfolioTotalRub(portfolioJson: JSONObject): String? {
    val total = portfolioJson.optJSONObject("totalAmountPortfolio")
        ?: portfolioJson.optJSONObject("total_amount_portfolio")
        ?: return null
    val v = quotationUnitsToDouble(total) ?: return null
    return String.format(java.util.Locale.US, "%.2f ₽", v)
}
