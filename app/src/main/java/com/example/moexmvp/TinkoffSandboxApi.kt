package com.example.moexmvp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val jsonMedia = "application/json; charset=utf-8".toMediaType()

private suspend fun tinkoffSandboxPostRaw(token: String, method: String, bodyJson: String): String =
    withContext(Dispatchers.IO) {
        val url = "$TINVEST_SANDBOX_REST_PREFIX/$method"
        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(jsonMedia))
            .header("Authorization", "Bearer $token")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: $text")
            }
            text
        }
    }

/** POST to sandbox REST; returns parsed JSON body on HTTP 2xx. */
internal suspend fun tinkoffSandboxPostAsync(token: String, method: String, body: JSONObject): JSONObject {
    val raw = tinkoffSandboxPostRaw(token, method, body.toString())
    return JSONObject(raw)
}

private fun JSONObject.firstNonBlankString(vararg keys: String): String? {
    for (k in keys) {
        val v = optString(k, "").trim()
        if (v.isNotEmpty()) return v
    }
    return null
}

private fun extractAccountId(root: JSONObject): String? {
    root.firstNonBlankString("accountId", "account_id")?.let { return it }
    val nestedKeys = arrayOf("account", "sandboxAccount", "openSandboxAccountResponse")
    for (k in nestedKeys) {
        root.optJSONObject(k)?.let { extractAccountId(it)?.let { return it } }
    }
    return null
}

internal suspend fun tinkoffOpenSandboxAccount(token: String, name: String): String {
    val root = tinkoffSandboxPostAsync(token, "OpenSandboxAccount", JSONObject().put("name", name))
    return extractAccountId(root) ?: throw IOException("Нет accountId в ответе: $root")
}

internal data class SandboxAccountRow(val id: String, val name: String)

internal suspend fun tinkoffGetSandboxAccounts(token: String): List<SandboxAccountRow> {
    val body = JSONObject().put("status", "ACCOUNT_STATUS_ALL")
    val root = tinkoffSandboxPostAsync(token, "GetSandboxAccounts", body)
    val arr: JSONArray = root.optJSONArray("accounts")
        ?: root.optJSONArray("Accounts")
        ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.firstNonBlankString("id", "accountId", "account_id") ?: continue
            val name = o.firstNonBlankString("name", "Name").orEmpty()
            add(SandboxAccountRow(id = id, name = name))
        }
    }
}

internal suspend fun tinkoffSandboxPayIn(token: String, accountId: String, unitsRub: Long): JSONObject {
    val amount = JSONObject()
        .put("currency", "RUB")
        .put("units", unitsRub.toString())
        .put("nano", 0)
    val body = JSONObject()
        .put("accountId", accountId)
        .put("amount", amount)
    return tinkoffSandboxPostAsync(token, "SandboxPayIn", body)
}
