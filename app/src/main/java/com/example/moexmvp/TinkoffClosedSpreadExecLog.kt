package com.example.moexmvp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val PREFS = "moex_prod_closed_spread_exec"
private const val KEY_HISTORY_JSON = "closed_spread_exec_history_json"
private const val MAX_HISTORY = 60

/** Закрытая сделка на Prod с PnL ног с GetPortfolio (expectedYield) на момент выхода. */
internal data class ProdClosedSpreadExecRecord(
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
    val quantityLots: Int,
    val executionNotionalRub: Double,
    val exitTimestampMillis: Long,
    val exitZScore: Double,
    val longLegYieldRub: Double,
    val shortLegYieldRub: Double,
    /** Чистый реализованный PnL = Δ денег на счёте (Prod). */
    val realizedNetRub: Double? = null,
    val entryPortfolioCashRub: Double? = null,
    val exitPortfolioCashRub: Double? = null,
    val operationsCommissionRub: Double = 0.0,
    val closedAtMillis: Long = System.currentTimeMillis(),
)

internal object TinkoffClosedSpreadExecLog {

    private val lock = Any()

    fun recordClose(
        context: Context,
        execution: SandboxSpreadExecUi,
        brokerPnl: SpreadLegBrokerPnl,
        exitTimestampMillis: Long,
        exitZScore: Double,
        realizedNetRub: Double? = null,
        entryPortfolioCashRub: Double? = null,
        exitPortfolioCashRub: Double? = null,
        operationsCommissionRub: Double = 0.0,
    ): ProdClosedSpreadExecRecord {
        val record = ProdClosedSpreadExecRecord(
            tradeId = execution.tradeId,
            signalType = execution.signalType,
            zScore = execution.zScore,
            barTimestampMillis = execution.barTimestampMillis,
            executedAtMillis = execution.executedAtMillis,
            entrySpreadPercent = execution.entrySpreadPercent,
            source = execution.source,
            directionLabel = execution.directionLabel,
            entryTimeMsk = execution.entryTimeMsk,
            longLegTicker = execution.longLegTicker,
            shortLegTicker = execution.shortLegTicker,
            longLegSideRu = execution.longLegSideRu,
            shortLegSideRu = execution.shortLegSideRu,
            volumeText = execution.volumeText,
            confirmLabel = execution.confirmLabel,
            correlationTag = execution.correlationTag,
            quantityLots = execution.quantityLots,
            executionNotionalRub = execution.executionNotionalRub,
            exitTimestampMillis = exitTimestampMillis,
            exitZScore = exitZScore,
            longLegYieldRub = brokerPnl.longLegYieldRub,
            shortLegYieldRub = brokerPnl.shortLegYieldRub,
            realizedNetRub = realizedNetRub,
            entryPortfolioCashRub = entryPortfolioCashRub,
            exitPortfolioCashRub = exitPortfolioCashRub,
            operationsCommissionRub = operationsCommissionRub,
        )
        val app = context.applicationContext
        synchronized(lock) {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val list = loadRecentUnsafe(prefs).toMutableList()
            val dupIndex = list.indexOfLast {
                it.tradeId == record.tradeId ||
                    (it.signalType == record.signalType &&
                        it.barTimestampMillis == record.barTimestampMillis &&
                        it.source == record.source)
            }
            if (dupIndex >= 0) {
                list[dupIndex] = record
            } else {
                list += record
            }
            prefs.edit()
                .putString(KEY_HISTORY_JSON, encodeHistory(list.takeLast(MAX_HISTORY)).toString())
                .commit()
        }
        return record
    }

    fun loadRecent(context: Context, limit: Int = MAX_HISTORY): List<ProdClosedSpreadExecRecord> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return loadRecentUnsafe(prefs).takeLast(limit)
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HISTORY_JSON)
            .apply()
    }

    private fun loadRecentUnsafe(prefs: android.content.SharedPreferences): List<ProdClosedSpreadExecRecord> {
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        return buildList {
            for (i in 0 until arr.length()) {
                parseRecord(arr.optJSONObject(i) ?: continue)?.let { add(it) }
            }
        }
    }

    private fun encodeHistory(entries: List<ProdClosedSpreadExecRecord>): JSONArray {
        val arr = JSONArray()
        entries.forEach { arr.put(encodeRecord(it)) }
        return arr
    }

    private fun encodeRecord(r: ProdClosedSpreadExecRecord): JSONObject =
        JSONObject()
            .put("tradeId", r.tradeId)
            .put("signalType", r.signalType.name)
            .put("zScore", r.zScore)
            .put("barTimestampMillis", r.barTimestampMillis)
            .put("executedAtMillis", r.executedAtMillis)
            .put("entrySpreadPercent", r.entrySpreadPercent)
            .put("source", r.source.name)
            .put("directionLabel", r.directionLabel)
            .put("entryTimeMsk", r.entryTimeMsk)
            .put("longLegTicker", r.longLegTicker)
            .put("shortLegTicker", r.shortLegTicker)
            .put("longLegSideRu", r.longLegSideRu)
            .put("shortLegSideRu", r.shortLegSideRu)
            .put("volumeText", r.volumeText)
            .put("confirmLabel", r.confirmLabel)
            .put("correlationTag", r.correlationTag)
            .put("quantityLots", r.quantityLots)
            .put("executionNotionalRub", r.executionNotionalRub)
            .put("exitTimestampMillis", r.exitTimestampMillis)
            .put("exitZScore", r.exitZScore)
            .put("longLegYieldRub", r.longLegYieldRub)
            .put("shortLegYieldRub", r.shortLegYieldRub)
            .put("realizedNetRub", r.realizedNetRub ?: JSONObject.NULL)
            .put("entryPortfolioCashRub", r.entryPortfolioCashRub ?: JSONObject.NULL)
            .put("exitPortfolioCashRub", r.exitPortfolioCashRub ?: JSONObject.NULL)
            .put("operationsCommissionRub", r.operationsCommissionRub)
            .put("closedAtMillis", r.closedAtMillis)

    private fun parseRecord(o: JSONObject): ProdClosedSpreadExecRecord? {
        val type = runCatching { StrategySignalType.valueOf(o.optString("signalType")) }.getOrNull()
            ?: return null
        if (type != StrategySignalType.EnterLong && type != StrategySignalType.EnterShort) return null
        val src = runCatching { PortfolioExecSource.valueOf(o.optString("source")) }.getOrNull()
            ?: PortfolioExecSource.MANUAL
        val barTs = o.optLong("barTimestampMillis", 0L)
        return ProdClosedSpreadExecRecord(
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
            entryTimeMsk = o.optString("entryTimeMsk"),
            longLegTicker = o.optString("longLegTicker", "TATN"),
            shortLegTicker = o.optString("shortLegTicker", "TATNP"),
            longLegSideRu = o.optString("longLegSideRu", ""),
            shortLegSideRu = o.optString("shortLegSideRu", ""),
            volumeText = o.optString("volumeText", spreadVolumeText(o.optInt("quantityLots", 1))),
            confirmLabel = o.optString("confirmLabel", if (src == PortfolioExecSource.AUTO) "авто" else "ручное"),
            correlationTag = o.optString("correlationTag"),
            quantityLots = o.optInt("quantityLots", 1).coerceAtLeast(1),
            executionNotionalRub = o.optDouble("executionNotionalRub", 0.0),
            exitTimestampMillis = o.optLong("exitTimestampMillis", 0L),
            exitZScore = o.optDouble("exitZScore", 0.0),
            longLegYieldRub = o.optDouble("longLegYieldRub", 0.0),
            shortLegYieldRub = o.optDouble("shortLegYieldRub", 0.0),
            realizedNetRub = o.optNullableDouble("realizedNetRub"),
            entryPortfolioCashRub = o.optNullableDouble("entryPortfolioCashRub"),
            exitPortfolioCashRub = o.optNullableDouble("exitPortfolioCashRub"),
            operationsCommissionRub = o.optDouble("operationsCommissionRub", 0.0),
            closedAtMillis = o.optLong("closedAtMillis", System.currentTimeMillis()),
        )
    }
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key)
}

internal fun computeProdClosedTradePnl(
    record: ProdClosedSpreadExecRecord,
    commissionPercentPerSide: Double,
): SandboxClosedTradePnl =
    computeProdClosedTradePnlFromBroker(
        executionNotionalRub = record.executionNotionalRub,
        entryTimeMsk = record.entryTimeMsk,
        exitTimestampMillis = record.exitTimestampMillis,
        longLegYieldRub = record.longLegYieldRub,
        shortLegYieldRub = record.shortLegYieldRub,
        commissionPercentPerSide = commissionPercentPerSide,
        realizedNetRub = record.realizedNetRub,
        operationsCommissionRub = record.operationsCommissionRub,
    )

internal fun computeProdClosedTradePnlFromBroker(
    execution: SandboxSpreadExecUi,
    brokerPnl: SpreadLegBrokerPnl,
    exitTimestampMillis: Long,
    commissionPercentPerSide: Double,
): SandboxClosedTradePnl = computeProdClosedTradePnlFromBroker(
    executionNotionalRub = execution.executionNotionalRub,
    entryTimeMsk = execution.entryTimeMsk,
    exitTimestampMillis = exitTimestampMillis,
    longLegYieldRub = brokerPnl.longLegYieldRub,
    shortLegYieldRub = brokerPnl.shortLegYieldRub,
    commissionPercentPerSide = commissionPercentPerSide,
)

internal fun computeProdClosedTradePnlFromBroker(
    executionNotionalRub: Double,
    entryTimeMsk: String,
    exitTimestampMillis: Long,
    longLegYieldRub: Double,
    shortLegYieldRub: Double,
    commissionPercentPerSide: Double,
    realizedNetRub: Double? = null,
    operationsCommissionRub: Double = 0.0,
): SandboxClosedTradePnl {
    if (realizedNetRub != null) {
        val comm = operationsCommissionRub.takeIf { it > 0.0 }
            ?: 0.0
        return SandboxClosedTradePnl(
            grossRub = realizedNetRub + comm,
            commissionRub = comm,
            overnightRub = 0.0,
            netRub = realizedNetRub,
            exitSpreadPercent = 0.0,
        )
    }
    val entryDate = portfolioDateLabelFromMskTableTime(entryTimeMsk)
    val exitDate = portfolioDateLabelFromMskTableTime(
        formatPortfolioExecutionTableMsk(exitTimestampMillis)
    )
    val notionalRub = executionNotionalRub.takeIf { it > 0.0 }
        ?: DEFAULT_PORTFOLIO_NOTIONAL_RUB
    val grossRub = longLegYieldRub + shortLegYieldRub
    val (commissionRub, overnightRub) = portfolioTradeCommissionAndOvernightRub(
        notionalRub = notionalRub,
        leverage = 1.0,
        commissionPercentPerSide = commissionPercentPerSide,
        entryDateLabel = entryDate,
        exitDateLabel = exitDate,
        includeExitCommission = true,
    )
    return SandboxClosedTradePnl(
        grossRub = grossRub,
        commissionRub = commissionRub,
        overnightRub = overnightRub,
        netRub = grossRub - commissionRub - overnightRub,
        exitSpreadPercent = 0.0,
    )
}

internal fun buildClosedRowsFromProdBrokerLog(
    records: List<ProdClosedSpreadExecRecord>,
    journalEvents: List<StrategySignalEvent>,
    pushLog: List<PushNotificationLogEntry>,
    commissionPercentPerSide: Double,
): List<PortfolioConfirmedTradeTableRow> {
    if (records.isEmpty()) return emptyList()
    return records.mapIndexed { index, record ->
        val pnl = computeProdClosedTradePnl(record, commissionPercentPerSide)
        val tag = record.correlationTag.ifBlank {
            spreadLegPushCorrelationTag(record.barTimestampMillis, record.signalType)
        }
        val (sigId, sigBar, sigRecv) = entrySignalDisplayFields(
            journalEvents = journalEvents,
            barTimestampMillis = record.barTimestampMillis,
            signalType = record.signalType,
            fallbackReceivedAtMillis = record.executedAtMillis,
        )
        val tradeDisplayId = entryTradeDisplayId(
            journalEvents = journalEvents,
            barTimestampMillis = record.barTimestampMillis,
            signalType = record.signalType,
            fallbackReceivedAtMillis = record.executedAtMillis,
        )
        PortfolioConfirmedTradeTableRow(
            tradeId = "T-P%03d".format(Locale.US, index + 1),
            tradeDisplayId = tradeDisplayId,
            directionLabel = record.directionLabel,
            entryTimeMsk = record.entryTimeMsk,
            exitTimeMsk = formatPortfolioExecutionTableMsk(record.exitTimestampMillis),
            longLegTicker = record.longLegTicker,
            shortLegTicker = record.shortLegTicker,
            longLegSideRu = record.longLegSideRu,
            shortLegSideRu = record.shortLegSideRu,
            volumeText = record.volumeText,
            confirmLabel = record.confirmLabel,
            entryZ = record.zScore,
            exitZ = record.exitZScore,
            notificationIdsText = formatPushIdsForCorrelation(pushLog, tag),
            legLongPnlSplitRubApprox = record.longLegYieldRub,
            legShortPnlSplitRubApprox = record.shortLegYieldRub,
            grossPnlRubApprox = pnl.grossRub,
            netPnlRubApprox = pnl.netRub,
            commissionRubApprox = pnl.commissionRub,
            overnightRubApprox = pnl.overnightRub,
            entrySignalId = sigId,
            entrySignalBarTimeMsk = sigBar,
            entrySignalReceivedMsk = sigRecv,
        )
    }
}
