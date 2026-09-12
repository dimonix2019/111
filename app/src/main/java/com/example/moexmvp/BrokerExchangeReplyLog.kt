package com.example.moexmvp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** По умолчанию лог биржи включён (ответы PostOrder нужны после сбоя Short). */
internal const val EXCHANGE_LOG_WRITING_DEFAULT = true

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
 * Ответы биржи/брокера (PostOrder, GetMaxLots): кольцо в prefs.
 * Тумблер «Настройки → Лог биржи»; в UI только последние [SETTINGS_LOG_UI_TAIL].
 */
internal object BrokerExchangeReplyLog {
    private const val PREFS = "broker_exchange_reply_log"
    private const val KEY_JSON = "replies_json_v1"
    private const val KEY_WRITING = "writing_enabled"
    private const val MAX_RECORDS = 500
    private const val TAG = "MoexExchangeLog"
    private val memory = Collections.synchronizedList(mutableListOf<BrokerExchangeReply>())
    private val zone = ZoneId.of("Europe/Moscow")
    private val stampFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val exportTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isWritingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WRITING, EXCHANGE_LOG_WRITING_DEFAULT)

    fun setWritingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WRITING, enabled).apply()
    }

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
        val line = formatBrokerExchangeReplyLine(reply)
        Log.i(TAG, line)
        val app = context?.applicationContext
        if (app != null && !isWritingEnabled(app)) return
        synchronized(memory) {
            memory += reply
            while (memory.size > MAX_RECORDS) {
                memory.removeAt(0)
            }
        }
        if (app != null) persist(app, reply)
    }

    fun lineCount(context: Context): Int = loadRecent(context, MAX_RECORDS).size

    fun formatForDisplay(context: Context, tail: Int = SETTINGS_LOG_UI_TAIL): String {
        val lines = loadRecent(context, if (tail <= 0) MAX_RECORDS else tail)
            .map { formatBrokerExchangeReplyLine(it) }
        if (lines.isEmpty()) {
            return "Журнал биржи пуст. Откройте или закройте пару — сюда попадут PostOrder и GetMaxLots."
        }
        return lines.joinToString("\n")
    }

    fun exportText(context: Context): String {
        val replies = loadRecent(context, MAX_RECORDS)
        return buildString {
            append("MOEX MVP exchange log\n")
            append("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("device=${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("exported=${Instant.now().atZone(zone).format(exportTimeFmt)}\n")
            append("records=${replies.size}\n")
            append("\n--- exchange (${replies.size}) ---\n")
            replies.forEach { append(formatBrokerExchangeReplyLine(it)).append('\n') }
        }
    }

    fun exportFileName(): String {
        val stamp = Instant.now().atZone(zone).format(stampFmt)
        return "moex-exchange-log-v${BuildConfig.VERSION_NAME}-$stamp.txt"
    }

    fun copyToClipboard(context: Context): Boolean {
        val text = exportText(context)
        if (text.isBlank()) return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("MOEX MVP exchange log", text))
        return true
    }

    fun shareExport(context: Context) {
        val text = exportText(context)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, exportFileName())
        }
        context.startActivity(Intent.createChooser(send, "Экспорт лога биржи"))
    }

    fun saveExportToDownloads(context: Context): String? {
        val app = context.applicationContext
        val fileName = exportFileName()
        val content = exportText(context)
        if (content.isBlank()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            } ?: return null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Загрузки/$fileName"
        }.getOrNull()
    }

    fun writeExportToUri(context: Context, uri: Uri): Boolean = runCatching {
        val content = exportText(context)
        if (content.isBlank()) return false
        context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(content.toByteArray(Charsets.UTF_8))
        } ?: return false
        true
    }.getOrElse { false }

    fun shareExportFile(context: Context): Boolean {
        val app = context.applicationContext
        val prepared = runCatching {
            val out = File(app.cacheDir, exportFileName())
            out.writeText(exportText(app))
            out
        }.getOrNull() ?: return false
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", prepared)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, exportFileName())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(app.contentResolver, "exchange log", uri)
        }
        context.startActivity(Intent.createChooser(send, "Отправить файл лога биржи"))
        return true
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(memory) { memory.clear() }
        prefs(app).edit().remove(KEY_JSON).apply()
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
