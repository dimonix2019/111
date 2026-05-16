package com.example.moexmvp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val PREFS = "moex_sandbox_spread_exec"
private const val KEY_HISTORY_JSON = "spread_exec_history_json"
private const val KEY_JSON_LEGACY = "last_spread_exec_json"
private const val MAX_HISTORY = 30

/** @deprecated Старые записи журнала с Z=999; новые тесты пишут рыночный Z. */
internal const val PORTFOLIO_TEST_SIGNAL_Z_MARKER = 999.0

internal data class SandboxSpreadOrderLegUi(
    val ticker: String,
    val sideRu: String,
    val orderBrief: String
)

/** Одна сделка на демо = два ордера (ноги спрэда), те же поля что в таблице закрытых сделок. */
internal data class SandboxSpreadExecUi(
    val tradeId: String,
    val signalType: StrategySignalType,
    val zScore: Double,
    val barTimestampMillis: Long,
    val executedAtMillis: Long,
    val entrySpreadPercent: Double,
    val source: PortfolioExecSource,
    val directionLabel: String,
    val entryTimeMsk: String,
    val longLegTicker: String,
    val shortLegTicker: String,
    val longLegSideRu: String,
    val shortLegSideRu: String,
    val volumeText: String,
    val confirmLabel: String,
    val correlationTag: String,
    val notificationIdsText: String,
    val legs: List<SandboxSpreadOrderLegUi>,
    /** Текущий Z при пересчёте портфеля (колонка Zвых для открытой сделки). */
    val exitZDisplay: Double = Double.NaN,
    val legLongPnlSplitRubApprox: Double = Double.NaN,
    val legShortPnlSplitRubApprox: Double = Double.NaN,
    val netPnlRubApprox: Double = Double.NaN
) {
    fun toTradeGroup(): PortfolioTradeGroupRow {
        val orderRows = if (legs.size >= 2) {
            listOf(
                PortfolioOrderTableRow(
                    orderIndex = 1,
                    legRole = "Long",
                    ticker = legs[0].ticker,
                    sideRu = legs[0].sideRu,
                    orderBrief = legs[0].orderBrief,
                    volumeText = "1 лот"
                ),
                PortfolioOrderTableRow(
                    orderIndex = 2,
                    legRole = "Short",
                    ticker = legs[1].ticker,
                    sideRu = legs[1].sideRu,
                    orderBrief = legs[1].orderBrief,
                    volumeText = "1 лот"
                )
            )
        } else {
            legs.mapIndexed { index, leg ->
                PortfolioOrderTableRow(
                    orderIndex = index + 1,
                    legRole = if (index == 0) "Long" else "Short",
                    ticker = leg.ticker,
                    sideRu = leg.sideRu,
                    orderBrief = leg.orderBrief,
                    volumeText = "1 лот"
                )
            }
        }
        return PortfolioTradeGroupRow(
            tradeId = tradeId,
            directionLabel = directionLabel,
            entryTimeMsk = entryTimeMsk,
            exitTimeMsk = "—",
            volumeText = volumeText,
            confirmLabel = confirmLabel,
            entryZ = zScore,
            exitZ = exitZDisplay,
            notificationIdsText = notificationIdsText,
            legLongPnlSplitRubApprox = legLongPnlSplitRubApprox,
            legShortPnlSplitRubApprox = legShortPnlSplitRubApprox,
            netPnlRubApprox = netPnlRubApprox,
            orders = orderRows,
            isOpen = true
        )
    }
}

internal object TinkoffSandboxSpreadExecLog {

    fun recordFromLegs(
        context: Context,
        signalType: StrategySignalType,
        zScore: Double,
        barTimestampMillis: Long,
        executedAtMillis: Long,
        entrySpreadPercent: Double,
        source: PortfolioExecSource,
        legs: List<SandboxLegOrderResult>,
        fromTestButton: Boolean = false
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
            recordTemplate(
                context,
                signalType,
                zScore,
                barTimestampMillis,
                executedAtMillis,
                entrySpreadPercent,
                source,
                fromTestButton
            )
            return
        }
        appendExecution(
            context,
            buildEntry(
                context,
                signalType,
                zScore,
                barTimestampMillis,
                executedAtMillis,
                entrySpreadPercent,
                source,
                legUi,
                fromTestButton
            )
        )
    }

    fun recordTemplate(
        context: Context,
        signalType: StrategySignalType,
        zScore: Double,
        barTimestampMillis: Long,
        executedAtMillis: Long,
        entrySpreadPercent: Double,
        source: PortfolioExecSource,
        fromTestButton: Boolean = false
    ) {
        if (signalType != StrategySignalType.EnterLong && signalType != StrategySignalType.EnterShort) return
        appendExecution(
            context,
            buildEntry(
                context,
                signalType,
                zScore,
                barTimestampMillis,
                executedAtMillis,
                entrySpreadPercent,
                source,
                templateLegUi(signalType),
                fromTestButton
            )
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

    fun enrichForDisplay(
        context: Context,
        executions: List<SandboxSpreadExecUi>,
        points: List<DataPoint> = emptyList(),
        notionalRub: Double = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
        leverage: Double = 7.0,
        commissionPercentPerSide: Double = 0.04
    ): List<SandboxSpreadExecUi> {
        if (executions.isEmpty()) return executions
        val pushLog = loadPushNotificationLog(context)
        val withPush = executions.map { exec ->
            if (exec.correlationTag.isBlank()) exec
            else {
                val ids = formatPushIdsForCorrelation(pushLog, exec.correlationTag)
                if (ids == exec.notificationIdsText) exec else exec.copy(notificationIdsText = ids)
            }
        }
        return enrichOpenSandboxExecutions(
            withPush,
            points,
            notionalRub,
            leverage,
            commissionPercentPerSide
        )
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HISTORY_JSON)
            .remove(KEY_JSON_LEGACY)
            .apply()
    }

    private fun buildEntry(
        context: Context,
        signalType: StrategySignalType,
        zScore: Double,
        barTimestampMillis: Long,
        executedAtMillis: Long,
        entrySpreadPercent: Double,
        source: PortfolioExecSource,
        legUi: List<SandboxSpreadOrderLegUi>,
        fromTestButton: Boolean
    ): SandboxSpreadExecUi {
        val app = context.applicationContext
        val spread = legSpreadDisplayForEntry(signalType)
        val tag = spreadLegPushCorrelationTag(barTimestampMillis, signalType)
        val pushLog = loadPushNotificationLog(app)
        val seq = loadRecent(app, MAX_HISTORY).size + 1
        val confirm = when (source) {
            PortfolioExecSource.AUTO -> "авто"
            PortfolioExecSource.MANUAL -> "ручное"
        } + if (fromTestButton) " · тест" else ""
        return SandboxSpreadExecUi(
            tradeId = "D-%03d".format(Locale.US, seq),
            signalType = signalType,
            zScore = zScore,
            barTimestampMillis = barTimestampMillis,
            executedAtMillis = executedAtMillis,
            entrySpreadPercent = entrySpreadPercent,
            source = source,
            directionLabel = if (signalType == StrategySignalType.EnterShort) "short" else "long",
            entryTimeMsk = formatPortfolioExecutionTableMsk(executedAtMillis),
            longLegTicker = spread.longTicker,
            shortLegTicker = spread.shortTicker,
            longLegSideRu = spread.longSideRu,
            shortLegSideRu = spread.shortSideRu,
            volumeText = "1+1 лот",
            confirmLabel = confirm,
            correlationTag = tag,
            notificationIdsText = formatPushIdsForCorrelation(pushLog, tag),
            legs = legUi
        )
    }

    private fun appendExecution(context: Context, entry: SandboxSpreadExecUi) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateLegacyIfNeeded(prefs)
        val list = loadRecent(app, MAX_HISTORY).toMutableList()
        val dupIndex = list.indexOfLast {
            it.signalType == entry.signalType &&
                it.barTimestampMillis == entry.barTimestampMillis &&
                it.source == entry.source
        }
        if (dupIndex >= 0) {
            list[dupIndex] = entry.copy(tradeId = list[dupIndex].tradeId)
        } else {
            list += entry
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
        val barTs = o.optLong("timestampMillis", 0L)
        val z = o.optDouble("zScore", 0.0)
        val spread = legSpreadDisplayForEntry(type)
        val entry = SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = type,
            zScore = z,
            barTimestampMillis = barTs,
            executedAtMillis = barTs,
            entrySpreadPercent = 0.0,
            source = PortfolioExecSource.MANUAL,
            directionLabel = if (type == StrategySignalType.EnterShort) "short" else "long",
            entryTimeMsk = formatPortfolioExecutionTableMsk(barTs),
            longLegTicker = spread.longTicker,
            shortLegTicker = spread.shortTicker,
            longLegSideRu = spread.longSideRu,
            shortLegSideRu = spread.shortSideRu,
            volumeText = "1+1 лот",
            confirmLabel = "ручное",
            correlationTag = spreadLegPushCorrelationTag(barTs, type),
            notificationIdsText = "—",
            legs = templateLegUi(type)
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
            .put("tradeId", e.tradeId)
            .put("signalType", e.signalType.name)
            .put("zScore", e.zScore)
            .put("barTimestampMillis", e.barTimestampMillis)
            .put("executedAtMillis", e.executedAtMillis)
            .put("entrySpreadPercent", e.entrySpreadPercent)
            .put("source", e.source.name)
            .put("directionLabel", e.directionLabel)
            .put("entryTimeMsk", e.entryTimeMsk)
            .put("longLegTicker", e.longLegTicker)
            .put("shortLegTicker", e.shortLegTicker)
            .put("longLegSideRu", e.longLegSideRu)
            .put("shortLegSideRu", e.shortLegSideRu)
            .put("volumeText", e.volumeText)
            .put("confirmLabel", e.confirmLabel)
            .put("correlationTag", e.correlationTag)
            .put("notificationIdsText", e.notificationIdsText)
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
        val spread = legSpreadDisplayForEntry(type)
        val tag = o.optString("correlationTag").ifBlank {
            spreadLegPushCorrelationTag(barTs, type)
        }
        return SandboxSpreadExecUi(
            tradeId = o.optString("tradeId", "D-000"),
            signalType = type,
            zScore = o.optDouble("zScore", 0.0),
            barTimestampMillis = barTs,
            executedAtMillis = o.optLong("executedAtMillis", barTs),
            entrySpreadPercent = o.optDouble("entrySpreadPercent", 0.0),
            source = src,
            directionLabel = o.optString("directionLabel").ifBlank {
                if (type == StrategySignalType.EnterShort) "short" else "long"
            },
            entryTimeMsk = o.optString("entryTimeMsk").ifBlank {
                formatPortfolioExecutionTableMsk(o.optLong("executedAtMillis", barTs))
            },
            longLegTicker = o.optString("longLegTicker", spread.longTicker),
            shortLegTicker = o.optString("shortLegTicker", spread.shortTicker),
            longLegSideRu = o.optString("longLegSideRu", spread.longSideRu),
            shortLegSideRu = o.optString("shortLegSideRu", spread.shortSideRu),
            volumeText = o.optString("volumeText", "1+1 лот"),
            confirmLabel = o.optString("confirmLabel", if (src == PortfolioExecSource.AUTO) "авто" else "ручное"),
            correlationTag = tag,
            notificationIdsText = o.optString("notificationIdsText", "—"),
            legs = legs
        )
    }
}
