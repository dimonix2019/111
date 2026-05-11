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
    val o = runCatching { JSONObject(body) }.getOrNull() ?: return body.ifBlank { "HTTP $httpCode" }
    val msg = sequenceOf(
        o.optString("message"),
        o.optString("description"),
        o.optString("error"),
        o.optJSONObject("error")?.optString("message") ?: ""
    ).firstOrNull { it.isNotBlank() }
    val code = o.opt("code")?.toString()?.takeIf { it.isNotBlank() }
    return buildString {
        append("HTTP $httpCode")
        if (code != null) append(" · code=").append(code)
        if (!msg.isNullOrBlank()) append(": ").append(msg)
        else if (body.isNotBlank() && body.length < 800) append(": ").append(body)
    }
}

private suspend fun tinkoffSandboxPostRaw(token: String, method: String, bodyJson: String): String =
    withContext(Dispatchers.IO) {
        val norm = normalizeInvestToken(token)
        val url = "$TINVEST_SANDBOX_REST_PREFIX/$method"
        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(jsonMedia))
            .header("Authorization", "Bearer $norm")
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException(extractApiErrorMessage(resp.code, text))
            }
            text
        }
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
        .put("units", unitsRub.toString())
        .put("nano", 0)
    return JSONObject()
        .put("accountId", accountId)
        .put("amount", amount)
}

private fun payInBodySnake(accountId: String, unitsRub: Long): JSONObject {
    val amount = JSONObject()
        .put("currency", "rub")
        .put("units", unitsRub.toString())
        .put("nano", 0)
    return JSONObject()
        .put("account_id", accountId)
        .put("amount", amount)
}

internal suspend fun tinkoffSandboxPayIn(token: String, accountId: String, unitsRub: Long): JSONObject {
    var last: IOException? = null
    for (body in listOf(payInBodyCamel(accountId, unitsRub), payInBodySnake(accountId, unitsRub))) {
        try {
            return tinkoffSandboxPostAsync(token, "SandboxPayIn", body)
        } catch (e: IOException) {
            last = e
        }
    }
    throw last ?: IOException("SandboxPayIn: неизвестная ошибка")
}
