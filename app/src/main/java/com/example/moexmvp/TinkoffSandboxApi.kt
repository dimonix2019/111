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

private val jsonMediaUtf8 = "application/json; charset=utf-8".toMediaType()

/** Strip accidental "Bearer " prefix, whitespace and non-ASCII junk (invalid in Authorization header). */
internal fun normalizeInvestToken(raw: String): String {
    var t = raw.trim()
    if (t.startsWith("Bearer ", ignoreCase = true)) {
        t = t.substring(7).trim()
    }
    t = t.replace(Regex("""[\s\u00A0\u1680\u2000-\u200B\u202F\u205F\u3000\uFEFF]+"""), "")
    return buildString(t.length) {
        for (ch in t) {
            if (ch.code in 33..126 && ch != '"' && ch != '\\') append(ch)
        }
    }
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
        if (norm.isEmpty()) {
            throw IOException(
                "Пустой или недопустимый токен API. Вставьте токен из личного кабинета Т‑Инвест без пробелов и спецсимволов."
            )
        }
        var lastFailure: IOException? = null
        for (prefix in prefixes) {
            val url = "$prefix/$method"
            val req = try {
                Request.Builder()
                    .url(url)
                    .post(bodyJson.toRequestBody(jsonMediaUtf8))
                    .header("Authorization", "Bearer $norm")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "MOEX-MVP-Android")
                    .build()
            } catch (e: IllegalArgumentException) {
                throw IOException(
                    "Недопустимые символы в токене API. Скопируйте токен заново из личного кабинета Т‑Инвест (только латиница и цифры).",
                    e
                )
            }
            try {
                httpClient.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        lastFailure =
                            IOException("${extractApiErrorMessage(resp.code, text)} · $url")
                    } else {
                        return@withContext text
                    }
                }
            } catch (e: IOException) {
                lastFailure = e
            }
        }
        throw lastFailure ?: IOException("T‑Invest REST: нет доступных хостов")
    }

private suspend fun tinkoffSandboxPostRaw(token: String, method: String, bodyJson: String): String =
    tinkoffSbxPostRaw(TINVEST_SANDBOX_REST_PREFIXES, token, method, bodyJson)

private suspend fun tinkoffInstrumentsPostRaw(token: String, method: String, bodyJson: String): String =
    tinkoffSbxPostRaw(TINVEST_SANDBOX_INSTRUMENTS_PREFIXES, token, method, bodyJson)

private suspend fun tinkoffProdOrdersPostRaw(token: String, method: String, bodyJson: String): String =
    tinkoffSbxPostRaw(TINVEST_PROD_ORDERS_PREFIXES, token, method, bodyJson)

private suspend fun tinkoffProdInstrumentsPostRaw(token: String, method: String, bodyJson: String): String =
    tinkoffSbxPostRaw(TINVEST_PROD_INSTRUMENTS_PREFIXES, token, method, bodyJson)

private suspend fun tinkoffProdOperationsPostRaw(token: String, method: String, bodyJson: String): String =
    tinkoffSbxPostRaw(TINVEST_PROD_OPERATIONS_PREFIXES, token, method, bodyJson)

private suspend fun tinkoffProdUsersPostRaw(token: String, method: String, bodyJson: String): String =
    tinkoffSbxPostRaw(TINVEST_PROD_USERS_PREFIXES, token, method, bodyJson)

internal suspend fun tinkoffInstrumentsPostAsync(token: String, method: String, body: JSONObject): JSONObject {
    val raw = tinkoffInstrumentsPostRaw(token, method, body.toString())
    return parseJsonObjectOrEmpty(raw)
}

internal suspend fun tinkoffSandboxPostAsync(token: String, method: String, body: JSONObject): JSONObject {
    val raw = tinkoffSandboxPostRaw(token, method, body.toString())
    return parseJsonObjectOrEmpty(raw)
}

internal suspend fun tinkoffProdOrdersPostAsync(token: String, method: String, body: JSONObject): JSONObject {
    val raw = tinkoffProdOrdersPostRaw(token, method, body.toString())
    return parseJsonObjectOrEmpty(raw)
}

internal suspend fun tinkoffProdInstrumentsPostAsync(token: String, method: String, body: JSONObject): JSONObject {
    val raw = tinkoffProdInstrumentsPostRaw(token, method, body.toString())
    return parseJsonObjectOrEmpty(raw)
}

internal suspend fun tinkoffProdOperationsPostAsync(token: String, method: String, body: JSONObject): JSONObject {
    val raw = tinkoffProdOperationsPostRaw(token, method, body.toString())
    return parseJsonObjectOrEmpty(raw)
}

internal suspend fun tinkoffProdUsersPostAsync(token: String, method: String, body: JSONObject): JSONObject {
    val raw = tinkoffProdUsersPostRaw(token, method, body.toString())
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

/** BFS по вложенным объектам: accountId иногда глубже одного уровня обёртки. */
private fun deepFindSandboxAccountId(root: JSONObject): String? {
    val q = ArrayDeque<JSONObject>()
    q.add(root)
    var steps = 0
    while (q.isNotEmpty() && steps++ < 256) {
        val o = q.removeFirst()
        o.optString("accountId", "").trim().takeIf { it.isNotEmpty() }?.let { return it }
        o.optString("account_id", "").trim().takeIf { it.isNotEmpty() }?.let { return it }
        val it = o.keys()
        while (it.hasNext()) {
            when (val v = o.opt(it.next())) {
                is JSONObject -> q.add(v)
                is JSONArray -> for (i in 0 until v.length()) {
                    v.optJSONObject(i)?.let { q.add(it) }
                }
            }
        }
    }
    return null
}

private fun resolveOpenSandboxAccountId(raw: JSONObject): String? {
    val variants = listOf(
        raw,
        unwrapJsonByKeys(raw, listOf("openSandboxAccountResponse", "open_sandbox_account_response")),
        unwrapJsonByKeys(raw, listOf("result")),
        unwrapJsonByKeys(raw, listOf("payload")),
        unwrapJsonByKeys(raw, listOf("data")),
        unwrapJsonByKeys(raw, listOf("response"))
    )
    for (v in variants) {
        deepFindSandboxAccountId(v)?.let { return it }
        extractAccountId(v)?.let { return it }
    }
    return null
}

private fun unwrapJsonByKeys(root: JSONObject, keys: Iterable<String>): JSONObject {
    var o = root
    for (w in keys) {
        o.optJSONObject(w)?.let { inner -> o = inner }
    }
    return o
}

private fun unwrapGetAccountsEnvelope(root: JSONObject): JSONObject =
    unwrapJsonByKeys(root, listOf("getAccountsResponse", "get_accounts_response"))

internal suspend fun tinkoffOpenSandboxAccount(token: String, name: String): String {
    val nameAsArray = JSONArray().put(name)
    val bodies = listOf(
        JSONObject().put("name", name),
        JSONObject().put("Name", name),
        JSONObject().put("name", nameAsArray),
        JSONObject()
    )
    var last: IOException? = null
    for (body in bodies) {
        try {
            val raw = tinkoffSandboxPostAsync(token, "OpenSandboxAccount", body)
            resolveOpenSandboxAccountId(raw)?.let { return it }
        } catch (e: IOException) {
            last = e
        }
    }
    throw last ?: IOException("OpenSandboxAccount: не удалось открыть счёт")
}

internal suspend fun tinkoffCloseSandboxAccount(token: String, accountId: String): JSONObject {
    var last: IOException? = null
    for (body in listOf(
        JSONObject().put("accountId", accountId),
        JSONObject().put("account_id", accountId)
    )) {
        try {
            return tinkoffSandboxPostAsync(token, "CloseSandboxAccount", body)
        } catch (e: IOException) {
            last = e
        }
    }
    throw last ?: IOException("CloseSandboxAccount: неизвестная ошибка")
}

/** Закрывает указанный демо-счёт на стороне T‑Invest и открывает новый пустой (тот же токен). */
internal suspend fun tinkoffCloseAndOpenSandboxAccount(token: String, accountIdToClose: String): String {
    tinkoffCloseSandboxAccount(token, accountIdToClose)
    return tinkoffOpenSandboxAccount(token, "MOEX MVP sandbox")
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
    fun parse(root: JSONObject): List<SandboxAccountRow> =
        parseSandboxAccounts(unwrapGetAccountsEnvelope(root))
    var root = tinkoffSandboxPostAsync(token, "GetSandboxAccounts", JSONObject())
    var rows = parse(root)
    if (rows.isEmpty()) {
        root = tinkoffSandboxPostAsync(
            token,
            "GetSandboxAccounts",
            JSONObject().put("status", "ACCOUNT_STATUS_ALL")
        )
        rows = parse(root)
    }
    return rows
}

internal suspend fun tinkoffGetProdAccounts(token: String): List<SandboxAccountRow> {
    fun parse(root: JSONObject): List<SandboxAccountRow> =
        parseSandboxAccounts(unwrapGetAccountsEnvelope(root))
    var root = tinkoffProdUsersPostAsync(token, "GetAccounts", JSONObject())
    var rows = parse(root)
    if (rows.isEmpty()) {
        root = tinkoffProdUsersPostAsync(
            token,
            "GetAccounts",
            JSONObject().put("status", "ACCOUNT_STATUS_ALL")
        )
        rows = parse(root)
    }
    return rows
}

internal suspend fun tinkoffGetAccounts(
    mode: TinkoffExecutionMode,
    token: String,
): List<SandboxAccountRow> = when (mode) {
    TinkoffExecutionMode.Sandbox -> tinkoffGetSandboxAccounts(token)
    TinkoffExecutionMode.Prod -> tinkoffGetProdAccounts(token)
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

private fun isLikelyFigi(instrumentId: String): Boolean =
    instrumentId.trim().uppercase(Locale.US).startsWith("BBG")

private fun postMarketBodyCamel(
    accountId: String,
    instrumentId: String,
    direction: String,
    quantityLots: Int,
    orderId: String,
    orderType: String = "ORDER_TYPE_MARKET",
    includePrice: Boolean = true
): JSONObject {
    val price = JSONObject()
        .put("units", "0")
        .put("nano", 0)
    return JSONObject().apply {
        put("accountId", accountId)
        put("instrumentId", instrumentId)
        put("quantity", quantityLots.toString())
        put("direction", direction)
        put("orderDirection", direction)
        put("orderType", orderType)
        put("orderId", orderId)
        if (includePrice) put("price", price)
        put("timeInForce", "TIME_IN_FORCE_DAY")
        put("confirmMarginTrade", true)
    }
}

private fun postMarketBodySnake(
    accountId: String,
    instrumentId: String,
    direction: String,
    quantityLots: Int,
    orderId: String,
    orderType: String = "ORDER_TYPE_MARKET",
    includePrice: Boolean = true
): JSONObject {
    val price = JSONObject()
        .put("units", "0")
        .put("nano", 0)
    return JSONObject().apply {
        put("account_id", accountId)
        put("instrument_id", instrumentId)
        put("quantity", quantityLots.toString())
        put("direction", direction)
        put("order_direction", direction)
        put("order_type", orderType)
        put("order_id", orderId)
        if (includePrice) put("price", price)
        put("time_in_force", "TIME_IN_FORCE_DAY")
        put("confirm_margin_trade", true)
    }
}

private fun postMarketBodyCamelFigi(
    accountId: String,
    figi: String,
    direction: String,
    quantityLots: Int,
    orderId: String,
    orderType: String = "ORDER_TYPE_MARKET",
    includePrice: Boolean = true
): JSONObject {
    val price = JSONObject()
        .put("units", "0")
        .put("nano", 0)
    return JSONObject().apply {
        put("accountId", accountId)
        put("figi", figi)
        put("quantity", quantityLots.toString())
        put("direction", direction)
        put("orderDirection", direction)
        put("orderType", orderType)
        put("orderId", orderId)
        if (includePrice) put("price", price)
        put("timeInForce", "TIME_IN_FORCE_DAY")
        put("confirmMarginTrade", true)
    }
}

private fun postMarketBodySnakeFigi(
    accountId: String,
    figi: String,
    direction: String,
    quantityLots: Int,
    orderId: String,
    orderType: String = "ORDER_TYPE_MARKET",
    includePrice: Boolean = true
): JSONObject {
    val price = JSONObject()
        .put("units", "0")
        .put("nano", 0)
    return JSONObject().apply {
        put("account_id", accountId)
        put("figi", figi)
        put("quantity", quantityLots.toString())
        put("direction", direction)
        put("order_direction", direction)
        put("order_type", orderType)
        put("order_id", orderId)
        if (includePrice) put("price", price)
        put("time_in_force", "TIME_IN_FORCE_DAY")
        put("confirm_margin_trade", true)
    }
}

private fun postMarketBodyCamelNoPrice(
    accountId: String,
    instrumentId: String,
    direction: String,
    quantityLots: Int,
    orderId: String,
    orderType: String = "ORDER_TYPE_MARKET"
): JSONObject = postMarketBodyCamel(
    accountId, instrumentId, direction, quantityLots, orderId,
    orderType = orderType, includePrice = false
)

private fun postMarketBodySnakeNoPrice(
    accountId: String,
    instrumentId: String,
    direction: String,
    quantityLots: Int,
    orderId: String,
    orderType: String = "ORDER_TYPE_MARKET"
): JSONObject = postMarketBodySnake(
    accountId, instrumentId, direction, quantityLots, orderId,
    orderType = orderType, includePrice = false
)

/** One market order on the sandbox account (1 lot by default). */
internal suspend fun tinkoffPostSandboxMarketOrder(
    token: String,
    accountId: String,
    instrumentId: String,
    orderDirection: String,
    quantityLots: Int = 1
): JSONObject {
    var last: IOException? = null
    val oid = { UUID.randomUUID().toString() }
    val figi = isLikelyFigi(instrumentId)
    val bodies = buildList {
        // Сначала snake_case: на REST часто корректнее читается direction (camel иногда давал «всегда покупка»).
        add { postMarketBodySnake(accountId, instrumentId, orderDirection, quantityLots, oid()) }
        add { postMarketBodyCamel(accountId, instrumentId, orderDirection, quantityLots, oid()) }
        add { postMarketBodySnakeNoPrice(accountId, instrumentId, orderDirection, quantityLots, oid()) }
        add { postMarketBodyCamelNoPrice(accountId, instrumentId, orderDirection, quantityLots, oid()) }
        add { postMarketBodySnakeNoPrice(accountId, instrumentId, orderDirection, quantityLots, oid(), "ORDER_TYPE_BESTPRICE") }
        add { postMarketBodyCamelNoPrice(accountId, instrumentId, orderDirection, quantityLots, oid(), "ORDER_TYPE_BESTPRICE") }
        if (figi) {
            add { postMarketBodySnakeFigi(accountId, instrumentId, orderDirection, quantityLots, oid()) }
            add { postMarketBodyCamelFigi(accountId, instrumentId, orderDirection, quantityLots, oid()) }
            add { postMarketBodySnakeFigi(accountId, instrumentId, orderDirection, quantityLots, oid(), "ORDER_TYPE_BESTPRICE", false) }
            add { postMarketBodyCamelFigi(accountId, instrumentId, orderDirection, quantityLots, oid(), "ORDER_TYPE_BESTPRICE", false) }
        }
    }
    for (factory in bodies) {
        try {
            return tinkoffSandboxPostAsync(token, "PostSandboxOrder", factory())
        } catch (e: IOException) {
            last = e
        }
    }
    throw last ?: IOException("PostSandboxOrder: неизвестная ошибка")
}

/** One market order on the prod account (1 lot by default). */
internal suspend fun tinkoffPostProdMarketOrder(
    token: String,
    accountId: String,
    instrumentId: String,
    orderDirection: String,
    quantityLots: Int = 1
): JSONObject {
    var last: IOException? = null
    val oid = { UUID.randomUUID().toString() }
    val figi = isLikelyFigi(instrumentId)
    val bodies = buildList {
        add { postMarketBodySnake(accountId, instrumentId, orderDirection, quantityLots, oid()) }
        add { postMarketBodyCamel(accountId, instrumentId, orderDirection, quantityLots, oid()) }
        add { postMarketBodySnakeNoPrice(accountId, instrumentId, orderDirection, quantityLots, oid()) }
        add { postMarketBodyCamelNoPrice(accountId, instrumentId, orderDirection, quantityLots, oid()) }
        add { postMarketBodySnakeNoPrice(accountId, instrumentId, orderDirection, quantityLots, oid(), "ORDER_TYPE_BESTPRICE") }
        add { postMarketBodyCamelNoPrice(accountId, instrumentId, orderDirection, quantityLots, oid(), "ORDER_TYPE_BESTPRICE") }
        if (figi) {
            add { postMarketBodySnakeFigi(accountId, instrumentId, orderDirection, quantityLots, oid()) }
            add { postMarketBodyCamelFigi(accountId, instrumentId, orderDirection, quantityLots, oid()) }
            add { postMarketBodySnakeFigi(accountId, instrumentId, orderDirection, quantityLots, oid(), "ORDER_TYPE_BESTPRICE", false) }
            add { postMarketBodyCamelFigi(accountId, instrumentId, orderDirection, quantityLots, oid(), "ORDER_TYPE_BESTPRICE", false) }
        }
    }
    for (factory in bodies) {
        try {
            return tinkoffProdOrdersPostAsync(token, "PostOrder", factory())
        } catch (e: IOException) {
            last = e
        }
    }
    throw last ?: IOException("PostOrder: неизвестная ошибка")
}

internal suspend fun tinkoffPostMarketOrder(
    mode: TinkoffExecutionMode,
    token: String,
    accountId: String,
    instrumentId: String,
    orderDirection: String,
    quantityLots: Int = 1,
): JSONObject = when (mode) {
    TinkoffExecutionMode.Sandbox ->
        tinkoffPostSandboxMarketOrder(token, accountId, instrumentId, orderDirection, quantityLots)
    TinkoffExecutionMode.Prod ->
        tinkoffPostProdMarketOrder(token, accountId, instrumentId, orderDirection, quantityLots)
}

/**
 * REST PostOrder expects [instrument_id] as FIGI or instrument UID, not always `TICKER_TQBR`.
 * Resolves via InstrumentsService/FindInstrument on the sandbox host.
 */
internal suspend fun tinkoffResolveShareInstrumentId(token: String, ticker: String): String {
    val want = ticker.trim().uppercase(Locale.US)
    fun parseMatches(root: JSONObject): List<JSONObject> {
        val arr = root.optJSONArray("instruments")
            ?: root.optJSONArray("Instruments")
            ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val raw = arr.optJSONObject(i) ?: continue
                val o = raw.optJSONObject("instrument") ?: raw
                val t = o.optString("ticker", o.optString("Ticker", "")).trim().uppercase(Locale.US)
                if (t == want) add(o)
            }
        }
    }
    fun pickId(o: JSONObject): String? = o.firstNonBlankString(
        "instrumentUid", "instrument_uid", "uid",
        "figi", "FIGI"
    )

    val roots = listOf(
        tinkoffInstrumentsPostAsync(
            token,
            "FindInstrument",
            JSONObject()
                .put("query", want)
                .put("instrumentKind", "INSTRUMENT_TYPE_SHARE")
                .put("apiTradeAvailableFlag", true)
        ),
        runCatching {
            tinkoffInstrumentsPostAsync(
                token,
                "FindInstrument",
                JSONObject()
                    .put("query", want)
                    .put("instrumentKind", "INSTRUMENT_TYPE_UNSPECIFIED")
            )
        }.getOrElse { JSONObject() }
    )

    val allMatches = roots.flatMap { parseMatches(it) }.distinctBy { it.toString() }
    if (allMatches.isEmpty()) {
        val hint = roots.joinToString(" | ") { it.toString().take(200) }
        throw IOException("FindInstrument: тикер $want не найден · $hint")
    }
    val tqbr = allMatches.firstOrNull {
        it.optString("classCode", it.optString("class_code", "")).equals("TQBR", ignoreCase = true)
    }
    val chosen = tqbr ?: allMatches.first()
    return pickId(chosen)
        ?: throw IOException("FindInstrument: нет uid/figi для $want · ${chosen.toString().take(400)}")
}

internal suspend fun tinkoffResolveShareInstrumentIdProd(token: String, ticker: String): String {
    val want = ticker.trim().uppercase(Locale.US)
    fun parseMatches(root: JSONObject): List<JSONObject> {
        val arr = root.optJSONArray("instruments")
            ?: root.optJSONArray("Instruments")
            ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                val raw = arr.optJSONObject(i) ?: continue
                val o = raw.optJSONObject("instrument") ?: raw
                val t = o.optString("ticker", o.optString("Ticker", "")).trim().uppercase(Locale.US)
                if (t == want) add(o)
            }
        }
    }
    fun pickId(o: JSONObject): String? = o.firstNonBlankString(
        "instrumentUid", "instrument_uid", "uid",
        "figi", "FIGI"
    )
    val roots = listOf(
        tinkoffProdInstrumentsPostAsync(
            token,
            "FindInstrument",
            JSONObject()
                .put("query", want)
                .put("instrumentKind", "INSTRUMENT_TYPE_SHARE")
                .put("apiTradeAvailableFlag", true)
        ),
        runCatching {
            tinkoffProdInstrumentsPostAsync(
                token,
                "FindInstrument",
                JSONObject()
                    .put("query", want)
                    .put("instrumentKind", "INSTRUMENT_TYPE_UNSPECIFIED")
            )
        }.getOrElse { JSONObject() }
    )
    val allMatches = roots.flatMap { parseMatches(it) }.distinctBy { it.toString() }
    if (allMatches.isEmpty()) {
        val hint = roots.joinToString(" | ") { it.toString().take(200) }
        throw IOException("FindInstrument(prod): тикер $want не найден · $hint")
    }
    val tqbr = allMatches.firstOrNull {
        it.optString("classCode", it.optString("class_code", "")).equals("TQBR", ignoreCase = true)
    }
    val chosen = tqbr ?: allMatches.first()
    return pickId(chosen)
        ?: throw IOException("FindInstrument(prod): нет uid/figi для $want · ${chosen.toString().take(400)}")
}

internal suspend fun tinkoffResolveShareInstrumentId(
    mode: TinkoffExecutionMode,
    token: String,
    ticker: String,
): String = when (mode) {
    TinkoffExecutionMode.Sandbox -> tinkoffResolveShareInstrumentId(token, ticker)
    TinkoffExecutionMode.Prod -> tinkoffResolveShareInstrumentIdProd(token, ticker)
}

/**
 * Одна рыночная тестовая заявка по TATN (1 лот по умолчанию) — для проверки песочницы и баланса.
 */
internal suspend fun tinkoffSandboxPostTestSingleLegOrder(
    token: String,
    accountId: String,
    buy: Boolean,
    quantityLots: Int = 1
): JSONObject {
    val inst = try {
        tinkoffResolveShareInstrumentId(token, "TATN")
    } catch (_: Exception) {
        TINKOFF_MOEX_TATN_INSTRUMENT_ID
    }
    val dir = if (buy) "ORDER_DIRECTION_BUY" else "ORDER_DIRECTION_SELL"
    return tinkoffPostSandboxMarketOrder(token, accountId, inst, dir, quantityLots)
}

internal fun formatPostSandboxOrderBrief(root: JSONObject): String {
    fun peel(o: JSONObject): JSONObject {
        var x = o
        for (k in listOf(
            "postOrderResponse", "post_order_response",
            "postSandboxOrderResponse", "post_sandbox_order_response",
            "generateOrdersResponse", "generate_orders_response"
        )) {
            x.optJSONObject(k)?.let { x = it }
        }
        return x
    }
    val o = peel(root)
    val id = o.firstNonBlankString("orderId", "order_id").orEmpty()
    val st = o.firstNonBlankString(
        "executionReportStatus", "execution_report_status",
        "orderState", "order_state"
    ).orEmpty()
    return when {
        id.isNotEmpty() && st.isNotEmpty() -> "$id · $st"
        id.isNotEmpty() -> id
        else -> o.toString().take(180)
    }
}

internal data class SandboxLegOrderResult(
    val ticker: String,
    val sideRu: String,
    val orderJson: JSONObject,
    val portfolioTotalRub: String?,
    val portfolioCashRub: String?,
    val completedAtMillis: Long
)

/**
 * Две рыночные заявки по 1 лоту; для песочницы после каждой ноги читаем портфель,
 * для прода строка портфеля опциональна.
 */
internal suspend fun tinkoffSandboxExecuteSpreadEntryDetailed(
    token: String,
    accountId: String,
    signalType: StrategySignalType
): List<SandboxLegOrderResult> =
    tinkoffExecuteSpreadEntryDetailed(
        mode = TinkoffExecutionMode.Sandbox,
        token = token,
        accountId = accountId,
        signalType = signalType,
    )

internal suspend fun tinkoffProdExecuteSpreadEntryDetailed(
    token: String,
    accountId: String,
    signalType: StrategySignalType,
): List<SandboxLegOrderResult> {
    return tinkoffExecuteSpreadEntryDetailed(
        mode = TinkoffExecutionMode.Prod,
        token = token,
        accountId = accountId,
        signalType = signalType,
    )
}

internal suspend fun tinkoffExecuteSpreadEntryDetailed(
    mode: TinkoffExecutionMode,
    token: String,
    accountId: String,
    signalType: StrategySignalType,
    quantityLots: Int = 1,
): List<SandboxLegOrderResult> {
    val qty = quantityLots.coerceAtLeast(1)
    val tatnId = runCatching { tinkoffResolveShareInstrumentId(mode, token, "TATN") }
        .getOrDefault(TINKOFF_MOEX_TATN_INSTRUMENT_ID)
    val tatnpId = runCatching { tinkoffResolveShareInstrumentId(mode, token, "TATNP") }
        .getOrDefault(TINKOFF_MOEX_TATNP_INSTRUMENT_ID)
    val buy = "ORDER_DIRECTION_BUY"
    val sell = "ORDER_DIRECTION_SELL"
    suspend fun postLeg(ticker: String, instId: String, dir: String, buyLeg: Boolean): SandboxLegOrderResult {
        val order = tinkoffPostMarketOrder(mode, token, accountId, instId, dir, qty)
        val pf = runCatching { tinkoffGetPortfolio(mode, token, accountId) }.getOrNull()
        return SandboxLegOrderResult(
            ticker = ticker,
            sideRu = spreadLegSideRu(buyLeg, qty),
            orderJson = order,
            portfolioTotalRub = pf?.let { formatSandboxPortfolioTotalRub(it) },
            portfolioCashRub = pf?.let { formatSandboxCashRub(it) },
            completedAtMillis = System.currentTimeMillis()
        )
    }
    return when (signalType) {
        StrategySignalType.EnterLong -> listOf(
            postLeg("TATN", tatnId, buy, buyLeg = true),
            postLeg("TATNP", tatnpId, sell, buyLeg = false)
        )
        StrategySignalType.EnterShort -> listOf(
            postLeg("TATNP", tatnpId, buy, buyLeg = true),
            postLeg("TATN", tatnId, sell, buyLeg = false)
        )
        else -> throw IOException("Только EnterLong / EnterShort")
    }
}

/** Закрытие спрэд-входа: обратные две рыночные заявки по 1 лоту. */
internal suspend fun tinkoffSandboxExecuteSpreadExitDetailed(
    token: String,
    accountId: String,
    openedWithEntrySignal: StrategySignalType
): List<SandboxLegOrderResult> =
    tinkoffExecuteSpreadExitDetailed(
        mode = TinkoffExecutionMode.Sandbox,
        token = token,
        accountId = accountId,
        openedWithEntrySignal = openedWithEntrySignal,
    )

internal suspend fun tinkoffProdExecuteSpreadExitDetailed(
    token: String,
    accountId: String,
    openedWithEntrySignal: StrategySignalType,
): List<SandboxLegOrderResult> {
    return tinkoffExecuteSpreadExitDetailed(
        mode = TinkoffExecutionMode.Prod,
        token = token,
        accountId = accountId,
        openedWithEntrySignal = openedWithEntrySignal,
    )
}

internal suspend fun tinkoffExecuteSpreadExitDetailed(
    mode: TinkoffExecutionMode,
    token: String,
    accountId: String,
    openedWithEntrySignal: StrategySignalType,
    quantityLots: Int = 1,
): List<SandboxLegOrderResult> {
    val qty = quantityLots.coerceAtLeast(1)
    val tatnId = runCatching { tinkoffResolveShareInstrumentId(mode, token, "TATN") }
        .getOrDefault(TINKOFF_MOEX_TATN_INSTRUMENT_ID)
    val tatnpId = runCatching { tinkoffResolveShareInstrumentId(mode, token, "TATNP") }
        .getOrDefault(TINKOFF_MOEX_TATNP_INSTRUMENT_ID)
    val buy = "ORDER_DIRECTION_BUY"
    val sell = "ORDER_DIRECTION_SELL"
    suspend fun postLeg(ticker: String, instId: String, dir: String, buyLeg: Boolean): SandboxLegOrderResult {
        val order = tinkoffPostMarketOrder(mode, token, accountId, instId, dir, qty)
        val pf = runCatching { tinkoffGetPortfolio(mode, token, accountId) }.getOrNull()
        return SandboxLegOrderResult(
            ticker = ticker,
            sideRu = spreadLegSideRu(buyLeg, qty),
            orderJson = order,
            portfolioTotalRub = pf?.let { formatSandboxPortfolioTotalRub(it) },
            portfolioCashRub = pf?.let { formatSandboxCashRub(it) },
            completedAtMillis = System.currentTimeMillis()
        )
    }
    return when (openedWithEntrySignal) {
        StrategySignalType.EnterLong -> listOf(
            postLeg("TATN", tatnId, sell, buyLeg = false),
            postLeg("TATNP", tatnpId, buy, buyLeg = true)
        )
        StrategySignalType.EnterShort -> listOf(
            postLeg("TATNP", tatnpId, sell, buyLeg = false),
            postLeg("TATN", tatnId, buy, buyLeg = true)
        )
        StrategySignalType.ExitLong, StrategySignalType.ExitShort ->
            throw IOException("Укажите тип входа EnterLong или EnterShort")
    }
}

/**
 * Вход в Z‑спрэд на песочнице: **ровно две** рыночные заявки по 1 лоту — одна **покупка**, одна **продажа**
 * на разных тикерах (TATN и TATNP). Это не «два сигнала Long+Short подряд», а одна спрэд‑позиция.
 *
 * - **EnterLong** (Z ушёл вниз): LONG TATN + SHORT TATNP → buy TATN, sell TATNP.
 * - **EnterShort** (Z ушёл вверх): LONG TATNP + SHORT TATN → buy TATNP, sell TATN.
 */
internal suspend fun tinkoffSandboxExecuteSpreadEntry(
    token: String,
    accountId: String,
    signalType: StrategySignalType
) {
    tinkoffSandboxExecuteSpreadEntryDetailed(token, accountId, signalType)
}

private fun unwrapSandboxPortfolioJson(root: JSONObject): JSONObject =
    unwrapJsonByKeys(
        root,
        listOf(
            "getSandboxPortfolioResponse",
            "get_sandbox_portfolio_response",
            "getPortfolioResponse",
            "get_portfolio_response",
            "portfolio",
            "Portfolio"
        )
    )

internal suspend fun tinkoffGetSandboxPortfolio(token: String, accountId: String): JSONObject {
    val attempts = listOf(
        JSONObject().put("accountId", accountId.trim()),
        JSONObject().put("account_id", accountId.trim()),
        JSONObject().put("accountId", accountId.trim()).put("currency", "RUB"),
        JSONObject().put("account_id", accountId.trim()).put("currency", "RUB")
    )
    var last: IOException? = null
    for (body in attempts) {
        try {
            val raw = tinkoffSandboxPostAsync(token, "GetSandboxPortfolio", body)
            return unwrapSandboxPortfolioJson(raw)
        } catch (e: IOException) {
            last = e
        }
    }
    throw last ?: IOException("GetSandboxPortfolio: неизвестная ошибка")
}

private fun unwrapProdPortfolioJson(root: JSONObject): JSONObject =
    unwrapJsonByKeys(
        root,
        listOf(
            "getPortfolioResponse",
            "get_portfolio_response",
            "portfolio",
            "Portfolio",
        )
    )

internal suspend fun tinkoffGetProdPortfolio(token: String, accountId: String): JSONObject {
    val attempts = listOf(
        JSONObject().put("accountId", accountId.trim()),
        JSONObject().put("account_id", accountId.trim()),
        JSONObject().put("accountId", accountId.trim()).put("currency", "RUB"),
        JSONObject().put("account_id", accountId.trim()).put("currency", "RUB"),
    )
    var last: IOException? = null
    for (body in attempts) {
        try {
            val raw = tinkoffProdOperationsPostAsync(token, "GetPortfolio", body)
            return unwrapProdPortfolioJson(raw)
        } catch (e: IOException) {
            last = e
        }
    }
    throw last ?: IOException("GetPortfolio(prod): неизвестная ошибка")
}

internal suspend fun tinkoffGetPortfolio(
    mode: TinkoffExecutionMode,
    token: String,
    accountId: String,
): JSONObject = when (mode) {
    TinkoffExecutionMode.Sandbox -> tinkoffGetSandboxPortfolio(token, accountId)
    TinkoffExecutionMode.Prod -> tinkoffGetProdPortfolio(token, accountId)
}

internal fun quotationUnitsToDouble(o: JSONObject): Double? {
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
    fun findTotal(o: JSONObject?, depth: Int): JSONObject? {
        if (o == null || depth > 6) return null
        o.optJSONObject("totalAmountPortfolio")?.let { return it }
        o.optJSONObject("total_amount_portfolio")?.let { return it }
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            findTotal(o.optJSONObject(k), depth + 1)?.let { return it }
        }
        return null
    }
    val total = findTotal(portfolioJson, 0) ?: return null
    val v = quotationUnitsToDouble(total) ?: return null
    return String.format(java.util.Locale.US, "%.2f ₽", v)
}

internal fun findPortfolioMoneyQuotation(portfolioJson: JSONObject): JSONObject? {
    val keys = listOf("totalAmountCurrencies", "total_amount_currencies")
    fun find(o: JSONObject?, depth: Int): JSONObject? {
        if (o == null || depth > 6) return null
        for (k in keys) {
            o.optJSONObject(k)?.let { return it }
        }
        val it = o.keys()
        while (it.hasNext()) {
            find(o.optJSONObject(it.next()), depth + 1)?.let { return it }
        }
        return null
    }
    return find(portfolioJson, 0)
}

/** Денежная часть портфеля в ₽ (удобно видеть рост после продажи). */
internal fun formatSandboxCashRub(portfolioJson: JSONObject): String? {
    val q = findPortfolioMoneyQuotation(portfolioJson) ?: return null
    val v = quotationUnitsToDouble(q) ?: return null
    return String.format(java.util.Locale.US, "%.2f ₽", v)
}

/** Две строки: всего по портфелю и деньги в ₽ (если API отдал). */
internal fun formatSandboxPortfolioLinesForUi(portfolioJson: JSONObject): String {
    val lines = buildList {
        formatSandboxPortfolioTotalRub(portfolioJson)?.let { add("Всего (портфель): $it") }
        formatSandboxCashRub(portfolioJson)?.let { add("Деньги (₽): $it") }
    }
    if (lines.isNotEmpty()) return lines.joinToString("\n")
    return formatSandboxPortfolioTotalRub(portfolioJson)
        ?: "Портфель (сырой): ${portfolioJson.toString().take(400)}"
}
