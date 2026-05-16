package com.example.moexmvp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "moex_sandbox_spread_exec"
private const val KEY_HISTORY_JSON = "spread_exec_history_json"
private const val KEY_JSON_LEGACY = "last_spread_exec_json"
private const val MAX_HISTORY = 30

internal data class SandboxSpreadOrderLegUi(
    val ticker: String,
    val sideRu: String,
    val orderBrief: String
)

/** Одна сделка на демо = два ордера (ноги спрэда). */
internal data class SandboxSpreadExecUi(
    val signalType: StrategySignalType,
    val zScore: Double,
    val barTimestampMillis: Long,
    val executedAtMillis: Long,
    val source: PortfolioExecSource,
    val legs: List<SandboxSpreadOrderLegUi>
) {
    val tradeTitleRu: String
        get() = when (signalType) {
            StrategySignalType.EnterLong -> "Сделка LONG TATN / SHORT TATNP"
            StrategySignalType.EnterShort -> "Сделка LONG TATNP / SHORT TATN"
            else -> "Сделка"
        }

    val sourceLabelRu: String
        get() = when (source) {
            PortfolioExecSource.MANUAL -> "ручное"
            PortfolioExecSource.AUTO -> "авто демо"
        }
}

internal object TinkoffSandboxSpreadExecLog {

    fun recordFromLegs(
        context: Context,
        signalType: StrategySignalType,
        zScore: Double,
        barTimestampMillis: Long,
        executedAtMillis: Long,
        source: PortfolioExecSource,
        legs: List<SandboxLegOrderResult>
    ) {
        if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return
        val legUi = legs.map { leg ->
            SandboxSpreadOrderLegUi(
                ticker = leg.ticker,
                sideRu = leg.sideRu,
                orderBrief = formatPostSandboxOrderBrief(leg.orderJson)
            )
        }
        if (legUi.isEmpty()) {
            recordTemplate(context, signalType, zScore, barTimestampMillis, executedAtMillis, source)
            return
        }
        appendExecution(
            context,
            SandboxSpreadExecUi(
                signalType = signalType,
                zScore = zScore,
                barTimestampMillis = barTimestampMillis,
                executedAtMillis = executedAtMillis,
                source = source,
                legs = legUi
            )
        )
    }

    /** Без ответа API — только шаблон ног (не вызывать после успешного PostOrder). */
    fun recordTemplate(
        context: Context,
        signalType: StrategySignalType,
        zScore: Double,
        barTimestampMillis: Long,
        executedAtMillis: Long,
        source: PortfolioExecSource
    ) {
        if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return
        val legs = templateLegUi(signalType)
        appendExecution(
            context,
            SandboxSpreadExecUi(
                signalType = signalType,
                zScore = zScore,
                barTimestampMillis = barTimestampMillis,
                executedAtMillis = executedAtMillis,
                source = source,
                legs = legs
            )
        )
    }

    @Deprecated("Use recordFromLegs", ReplaceWith("recordFromLegs(context, signalType, zScore, barTimestampMillis, executedAtMillis, source, legs)"))
    fun record(context: Context, signalType: StrategySignalType, zScore: Double, timestampMillis: Long) {
        recordTemplate(
            context,
            signalType,
            zScore,
            barTimestampMillis = timestampMillis,
            executedAtMillis = timestampMillis,
            source = PortfolioExecSource.MANUAL
        )
    }

    fun loadRecent(context: Context, limit: Int = MAX_HISTORY): List<SandboxSpreadExecUi> {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return buildList {
            for (i in 0 until arr.length()) {
                parseEntry(arr.optJSONObject(i) ?: continue)?.let { add(it) }
            }
        }.takeLast(limit)
    }

    fun load(context: Context): SandboxSpreadExecUi? = loadRecent(context, 1).lastOrNull()

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HISTORY_JSON)
            .remove(KEY_JSON_LEGACY)
            .apply()
    }

    private fun appendExecution(context: Context, entry: SandboxSpreadExecUi) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)
        val list = loadRecent(app, MAX_HISTORY).toMutableList()
        val dup = list.lastOrNull()?.let { last ->
            last.signalType == entry.signalType &&
                last.barTimestampMillis == entry.barTimestampMillis &&
                last.source == entry.source
        } == true
        if (!dup) {
            list += entry
        } else {
            list[list.lastIndex] = entry
        }
        val trimmed = list.takeLast(MAX_HISTORY)
        prefs.edit()
            .putString(KEY_HISTORY_JSON, encodeHistory(trimmed).toString())
            .remove(KEY_JSON_LEGACY)
            .apply()
    }

    private fun migrateLegacyIfNeeded(prefs: android.content.SharedPreferences) {
        if (prefs.contains(KEY_HISTORY_JSON)) return
        val legacy = prefs.getString(KEY_JSON_LEGACY, null) ?: return
        val o = runCatching { JSONObject(legacy) }.getOrNull() ?: return
        val type = runCatching { StrategySignalType.valueOf(o.optString("signalType")) }.getOrNull()
            ?: return
        if (type != StrategySignalType.EnterLong && type != StrategySignalType.EnterShort) return
        val legsRu = o.optString("legsRu", "")
        val legs = legsRu.lines().filter { it.isNotBlank() }.map { line ->
            SandboxSpreadOrderLegUi("?", line.trim(), "—")
        }
        val entry = SandboxSpreadExecUi(
            signalType = type,
            zScore = o.optDouble("zScore", 0.0),
            barTimestampMillis = o.optLong("timestampMillis", 0L),
            executedAtMillis = o.optLong("timestampMillis", 0L),
            source = PortfolioExecSource.MANUAL,
            legs = legs.ifEmpty { templateLegUi(type) }
        )
        prefs.edit()
            .putString(KEY_HISTORY_JSON, encodeHistory(listOf(entry)).toString())
            .remove(KEY_JSON_LEGACY)
            .apply()
    }

    private fun templateLegUi(signalType: StrategySignalType): List<SandboxSpreadOrderLegUi> = when (signalType) {
        StrategySignalType.EnterLong -> listOf(
            SandboxSpreadOrderLegUi("TATN", "покупка 1 лот", "—"),
            SandboxSpreadOrderLegUi("TATNP", "продажа 1 лот", "—")
        )
        StrategySignalType.EnterShort -> listOf(
            SandboxSpreadOrderLegUi("TATNP", "покупка 1 лот", "—"),
            SandboxSpreadOrderLegUi("TATN", "продажа 1 лот", "—")
        )
        else -> emptyList()
    }

    private fun encodeHistory(entries: List<SandboxSpreadExecUi>): JSONArray {
        val arr = JSONArray()
        entries.forEach { e -> arr.put(encodeEntry(e)) }
        return arr
    }

    private fun encodeEntry(e: SandboxSpreadExecUi): JSONObject {
        val legsArr = JSONArray()
        e.legs.forEach { leg ->
            legsArr.put(
                JSONObject()
                    .put("ticker", leg.ticker)
                    .put("sideRu", leg.sideRu)
                    .put("orderBrief", leg.orderBrief)
            )
        }
        return JSONObject()
            .put("signalType", e.signalType.name)
            .put("zScore", e.zScore)
            .put("barTimestampMillis", e.barTimestampMillis)
            .put("executedAtMillis", e.executedAtMillis)
            .put("source", e.source.name)
            .put("legs", legsArr)
    }

    private fun parseEntry(o: JSONObject): SandboxSpreadExecUi? {
        val type = runCatching { StrategySignalType.valueOf(o.optString("signalType")) }.getOrNull()
            ?: return null
        if (type != StrategySignalType.EnterLong && type != StrategySignalType.EnterShort) return null
        val src = runCatching { PortfolioExecSource.valueOf(o.optString("source")) }.getOrNull()
            ?: PortfolioExecSource.MANUAL
        val legsArr = o.optJSONArray("legs")
        val legs = if (legsArr != null && legsArr.length() > 0) {
            buildList {
                for (i in 0 until legsArr.length()) {
                    val leg = legsArr.optJSONObject(i) ?: continue
                    add(
                        SandboxSpreadOrderLegUi(
                            ticker = leg.optString("ticker", "?"),
                            sideRu = leg.optString("sideRu", ""),
                            orderBrief = leg.optString("orderBrief", "—")
                        )
                    )
                }
            }
        } else {
            templateLegUi(type)
        }
        val barTs = o.optLong("barTimestampMillis", o.optLong("timestampMillis", 0L))
        return SandboxSpreadExecUi(
            signalType = type,
            zScore = o.optDouble("zScore", 0.0),
            barTimestampMillis = barTs,
            executedAtMillis = o.optLong("executedAtMillis", barTs),
            source = src,
            legs = legs
        )
    }
}
