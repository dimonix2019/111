package com.example.moexmvp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Пометка цели заявки (вход / откат / flatten), видна в логе ответов биржи. */
internal class TinkoffOrderPurpose(
    val value: String,
) : AbstractCoroutineContextElement(TinkoffOrderPurpose) {
    companion object Key : CoroutineContext.Key<TinkoffOrderPurpose>
}

internal data class BrokerExchangeReply(
    val atMillis: Long,
    val method: String,
    val purpose: String,
    val ticker: String,
    val direction: String,
    val lots: Int,
    val ok: Boolean,
    val httpCode: Int,
    val apiCode: String,
    val message: String,
    val orderId: String,
    val execStatus: String,
    val executedLots: Int,
    val bodySnippet: String,
)

internal data class TinkoffOrderRequestSummary(
    val ticker: String,
    val direction: String,
    val lots: Int,
    val instrumentId: String,
)

/** Application context для HTTP-слоя T‑Invest (без прокидывания Context в каждый Post). */
internal object MoexAppHolder {
    @Volatile
    var app: Context? = null
        private set

    fun attach(context: Context) {
        app = context.applicationContext
    }
}

/**
 * Ответы биржи/брокера (PostOrder, GetMaxLots): кольцо в prefs + журнал событий.
 * Пишется всегда — не зависит от тумблера «Запись в журнал».
 */
internal object BrokerExchangeReplyLog {
    private const val PREFS = "broker_exchange_reply_log"
    private const val KEY_JSON = "replies_json_v1"
    private const val MAX_RECORDS = 80
    private val memory = Collections.synchronizedList(mutableListOf<BrokerExchangeReply>())

    fun recordHttp(
        method: String,
        requestJson: String,
        httpCode: Int,
        responseText: String,
        ok: Boolean,
        purpose: String,
        context: Context? = MoexAppHolder.app,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        val request = runCatching { JSONObject(requestJson) }.getOrElse { JSONObject() }
        val reply = summarizeTinkoffOrderHttpReply(
            method = method,
            request = request,
            httpCode = httpCode,
            responseText = responseText,
            ok = ok,
            purpose = purpose,
            atMillis = atMillis,
        )
        record(reply, context)
    }

    fun record(reply: BrokerExchangeReply, context: Context? = MoexAppHolder.app) {
        synchronized(memory) {
            memory += reply
            while (memory.size > MAX_RECORDS) {
                memory.removeAt(0)
            }
        }
        val app = context?.applicationContext
        if (app != null) {
            persist(app, reply)
            MoexDiagnostics.logExchangeReply(app, formatBrokerExchangeReplyLine(reply))
        }
    }

    fun loadRecent(context: Context?, limit: Int = MAX_RECORDS): List<BrokerExchangeReply> {
        val app = context?.applicationContext
        if (app != null) {
            val fromPrefs = loadFromPrefs(app)
            if (fromPrefs.isNotEmpty()) return fromPrefs.takeLast(limit)
        }
        synchronized(memory) {
            return memory.toList().takeLast(limit)
        }
    }

    fun formatForTradeTab(context: Context?, limit: Int = 16): String {
        val lines = loadRecent(context, limit)
            .asReversed()
            .map { formatBrokerExchangeReplyLine(it) }
        return if (lines.isEmpty()) {
            "Пока нет ответов биржи. Откройте или закройте пару — сюда попадут PostOrder и GetMaxLots."
        } else {
            lines.joinToString("\n")
        }
    }

    private fun persist(app: Context, reply: BrokerExchangeReply) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = loadFromPrefs(app).toMutableList()
        list += reply
        prefs.edit()
            .putString(KEY_JSON, encodeReplies(list.takeLast(MAX_RECORDS)).toString())
            .apply()
    }

    private fun loadFromPrefs(app: Context): List<BrokerExchangeReply> {
        val raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return buildList {
            for (i in 0 until arr.length()) {
                parseReply(arr.optJSONObject(i) ?: continue)?.let { add(it) }
            }
        }
    }

    private fun encodeReplies(replies: List<BrokerExchangeReply>): JSONArray {
        val arr = JSONArray()
        replies.forEach { arr.put(encodeReply(it)) }
        return arr
    }

    private fun encodeReply(r: BrokerExchangeReply): JSONObject =
        JSONObject()
            .put("atMillis", r.atMillis)
            .put("method", r.method)
            .put("purpose", r.purpose)
            .put("ticker", r.ticker)
            .put("direction", r.direction)
            .put("lots", r.lots)
            .put("ok", r.ok)
            .put("httpCode", r.httpCode)
            .put("apiCode", r.apiCode)
            .put("message", r.message)
            .put("orderId", r.orderId)
            .put("execStatus", r.execStatus)
            .put("executedLots", r.executedLots)
            .put("bodySnippet", r.bodySnippet)

    private fun parseReply(o: JSONObject): BrokerExchangeReply? {
        if (!o.has("method")) return null
        return BrokerExchangeReply(
            atMillis = o.optLong("atMillis"),
            method = o.optString("method"),
            purpose = o.optString("purpose"),
            ticker = o.optString("ticker"),
            direction = o.optString("direction"),
            lots = o.optInt("lots", 0),
            ok = o.optBoolean("ok", false),
            httpCode = o.optInt("httpCode", 0),
            apiCode = o.optString("apiCode"),
            message = o.optString("message"),
            orderId = o.optString("orderId"),
            execStatus = o.optString("execStatus"),
            executedLots = o.optInt("executedLots", 0),
            bodySnippet = o.optString("bodySnippet"),
        )
    }
}

private val BROKER_REPLY_ZONE = ZoneId.of("Europe/Moscow")
private val BROKER_REPLY_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

internal fun formatBrokerExchangeReplyLine(r: BrokerExchangeReply): String {
    val time = Instant.ofEpochMilli(r.atMillis).atZone(BROKER_REPLY_ZONE).format(BROKER_REPLY_TIME)
    val purpose = r.purpose.trim().takeIf { it.isNotEmpty() }?.let { "[$it] " }.orEmpty()
    val inst = buildString {
        append(r.ticker.ifBlank { "—" })
        if (r.direction.isNotBlank()) append(" ").append(r.direction)
        if (r.lots > 0) append(" ×").append(r.lots)
    }
    val result = if (r.ok) "OK" else "FAIL"
    val http = if (r.httpCode > 0) " HTTP ${r.httpCode}" else ""
    val code = r.apiCode.trim().takeIf { it.isNotEmpty() }?.let { " · code=$it" }.orEmpty()
    val status = r.execStatus.trim().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
    val oid = r.orderId.trim().takeIf { it.isNotEmpty() }?.let { " · oid=$it" }.orEmpty()
    val execLots = if (r.executedLots > 0) " · exec=${r.executedLots}" else ""
    val msg = r.message.trim().takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
    return "$time $purpose${r.method} $inst $result$http$code$status$oid$execLots$msg"
}

internal fun tickerFromTinkoffInstrumentId(id: String): String {
    val u = id.trim().uppercase(Locale.US)
    if (u.isEmpty()) return ""
    if (u == TINKOFF_MOEX_TATNP_FIGI || u.contains("TATNP")) return "TATNP"
    if (u == TINKOFF_MOEX_TATN_FIGI || u.contains("TATN")) return "TATN"
    return u.take(20)
}

internal fun directionFromTinkoffOrder(raw: String): String {
    val u = raw.trim().uppercase(Locale.US)
    return when {
        u.contains("BUY") -> "BUY"
        u.contains("SELL") -> "SELL"
        else -> raw.trim()
    }
}

internal fun jsonFirstNonBlankString(o: JSONObject, vararg keys: String): String {
    for (k in keys) {
        val v = o.optString(k, "").trim()
        if (v.isNotEmpty() && v != "null") return v
    }
    return ""
}

internal fun summarizeTinkoffOrderRequest(body: JSONObject): TinkoffOrderRequestSummary {
    val inst = jsonFirstNonBlankString(
        body,
        "instrumentId", "instrument_id", "figi", "FIGI",
    )
    val dir = directionFromTinkoffOrder(
        jsonFirstNonBlankString(body, "direction", "orderDirection", "order_direction"),
    )
    val lots = when {
        body.optInt("quantity", 0) > 0 -> body.optInt("quantity")
        body.optInt("quantityLots", 0) > 0 -> body.optInt("quantityLots")
        body.optInt("quantity_lots", 0) > 0 -> body.optInt("quantity_lots")
        else -> 0
    }
    return TinkoffOrderRequestSummary(
        ticker = tickerFromTinkoffInstrumentId(inst),
        direction = dir,
        lots = lots,
        instrumentId = inst,
    )
}

internal fun extractTinkoffApiErrorParts(httpCode: Int, body: String): Pair<String, String> {
    val o = runCatching { JSONObject(body) }.getOrNull()
        ?: return "" to body.trim().replace("\n", " ").take(280)
    val msg = sequenceOf(
        o.optString("message"),
        o.optJSONObject("status")?.optString("message").orEmpty(),
        o.optString("description"),
        o.optString("error"),
        o.optJSONObject("error")?.optString("message").orEmpty(),
        o.optJSONObject("details")?.optString("message").orEmpty(),
    ).firstOrNull { it.isNotBlank() }.orEmpty()
    val code = o.opt("code")?.toString()?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
    val snippet = body.trim().replace("\n", " ").take(280)
    val text = when {
        msg.isNotBlank() -> msg
        snippet.isNotEmpty() -> snippet
        else -> "HTTP $httpCode"
    }
    return code to text
}

internal fun summarizeTinkoffOrderHttpReply(
    method: String,
    request: JSONObject,
    httpCode: Int,
    responseText: String,
    ok: Boolean,
    purpose: String = "",
    atMillis: Long = System.currentTimeMillis(),
): BrokerExchangeReply {
    val req = summarizeTinkoffOrderRequest(request)
    val response = runCatching { JSONObject(responseText) }.getOrElse { JSONObject() }
    val fill = runCatching { parsePostOrderFill(response) }.getOrNull()
    val maxLots = if (method.contains("MaxLots", ignoreCase = true) && ok) {
        val parsed = parseTinkoffMaxLots(response)
        "buy=${parsed.maxBuy} sell=${parsed.maxSell} " +
            "(own ${parsed.buyOwn}/${parsed.sellOwn} · margin ${parsed.buyMargin}/${parsed.sellMargin})"
    } else {
        ""
    }
    val (apiCode, errMsg) = if (ok) "" to "" else extractTinkoffApiErrorParts(httpCode, responseText)
    val message = when {
        maxLots.isNotEmpty() -> maxLots
        ok -> {
            val px = fill?.executedPriceRub?.let { "%.3f ₽".format(Locale.US, it) }.orEmpty()
            px
        }
        else -> errMsg
    }
    val snippet = responseText.trim().replace("\n", " ").take(400)
    return BrokerExchangeReply(
        atMillis = atMillis,
        method = method,
        purpose = purpose,
        ticker = req.ticker,
        direction = req.direction,
        lots = req.lots,
        ok = ok,
        httpCode = httpCode,
        apiCode = apiCode,
        message = message,
        orderId = fill?.orderId.orEmpty(),
        execStatus = jsonFirstNonBlankString(
            unwrapOrderResponse(response),
            "executionReportStatus",
            "execution_report_status",
            "orderState",
            "order_state",
        ),
        executedLots = fill?.executedLots ?: 0,
        bodySnippet = snippet,
    )
}

private fun unwrapOrderResponse(root: JSONObject): JSONObject {
    var o = root
    for (k in listOf(
        "postOrderResponse", "post_order_response",
        "postSandboxOrderResponse", "post_sandbox_order_response",
        "getMaxLotsResponse", "get_max_lots_response",
    )) {
        o.optJSONObject(k)?.let { o = it }
    }
    return o
}

internal val TINVEST_ORDER_LOG_METHODS = setOf(
    "PostOrder",
    "PostSandboxOrder",
    "GetMaxLots",
    "CancelOrder",
)
