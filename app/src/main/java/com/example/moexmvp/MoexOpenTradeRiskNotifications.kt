package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** Интервал повторного push, пока открытая сделка остаётся в красной зоне риска. */
internal const val OPEN_TRADE_RED_RISK_REMINDER_INTERVAL_MS = 15 * 60 * 1000L

private const val OPEN_TRADE_RISK_NOTIFY_PREFS = "moex_open_trade_risk_notify"
private const val PREF_RED_RISK_STATES_JSON = "red_risk_states_json"

internal enum class OpenTradeRedRiskNotifyKind {
    Entered,
    Reminder,
    Exited,
}

internal data class OpenTradeRedRiskNotifyState(
    val inRedZone: Boolean = false,
    val lastReminderAtMillis: Long = 0L,
)

internal data class OpenTradeRedRiskNotifyAction(
    val kind: OpenTradeRedRiskNotifyKind,
    val tradeId: String,
    val tradeDisplayId: String,
    val assessment: StrategyTestTradeRiskAssessment?,
)

/**
 * «Красная» зона риска — высокий и критический уровень (тёмно-красная подсветка строки).
 * Умеренный (жёлтый) уровень push не шлёт.
 */
internal fun isOpenTradeRedRiskZone(assessment: StrategyTestTradeRiskAssessment): Boolean =
    assessment.level >= StrategyTestTradeRiskLevel.High

internal fun planOpenTradeRedRiskNotifications(
    openGroups: List<PortfolioTradeGroupRow>,
    assessments: List<StrategyTestTradeRiskAssessment>,
    previousStates: Map<String, OpenTradeRedRiskNotifyState>,
    nowMillis: Long,
    reminderIntervalMs: Long = OPEN_TRADE_RED_RISK_REMINDER_INTERVAL_MS,
): Pair<List<OpenTradeRedRiskNotifyAction>, Map<String, OpenTradeRedRiskNotifyState>> {
    val actions = mutableListOf<OpenTradeRedRiskNotifyAction>()
    val nextStates = linkedMapOf<String, OpenTradeRedRiskNotifyState>()
    val openTradeIds = openGroups.map { it.tradeId }.toSet()

    openGroups.forEachIndexed { index, group ->
        if (!group.isOpen) return@forEachIndexed
        val assessment = assessments.getOrNull(index) ?: return@forEachIndexed
        val tradeId = group.tradeId
        val prev = previousStates[tradeId] ?: OpenTradeRedRiskNotifyState()
        val isRed = isOpenTradeRedRiskZone(assessment)
        when {
            isRed && !prev.inRedZone -> {
                actions += OpenTradeRedRiskNotifyAction(
                    kind = OpenTradeRedRiskNotifyKind.Entered,
                    tradeId = tradeId,
                    tradeDisplayId = group.tradeDisplayId,
                    assessment = assessment,
                )
                nextStates[tradeId] = OpenTradeRedRiskNotifyState(
                    inRedZone = true,
                    lastReminderAtMillis = nowMillis,
                )
            }

            isRed && prev.inRedZone -> {
                if (nowMillis - prev.lastReminderAtMillis >= reminderIntervalMs) {
                    actions += OpenTradeRedRiskNotifyAction(
                        kind = OpenTradeRedRiskNotifyKind.Reminder,
                        tradeId = tradeId,
                        tradeDisplayId = group.tradeDisplayId,
                        assessment = assessment,
                    )
                    nextStates[tradeId] = prev.copy(lastReminderAtMillis = nowMillis)
                } else {
                    nextStates[tradeId] = prev
                }
            }

            !isRed && prev.inRedZone -> {
                actions += OpenTradeRedRiskNotifyAction(
                    kind = OpenTradeRedRiskNotifyKind.Exited,
                    tradeId = tradeId,
                    tradeDisplayId = group.tradeDisplayId,
                    assessment = assessment,
                )
            }
        }
    }

    for ((tradeId, prev) in previousStates) {
        if (tradeId !in openTradeIds && prev.inRedZone) {
            actions += OpenTradeRedRiskNotifyAction(
                kind = OpenTradeRedRiskNotifyKind.Exited,
                tradeId = tradeId,
                tradeDisplayId = tradeId,
                assessment = null,
            )
        }
    }

    return actions to nextStates
}

internal suspend fun loadOpenPortfolioTradeGroupsForRiskMonitor(
    context: Context,
    points: List<DataPoint>,
): List<PortfolioTradeGroupRow> = withContext(Dispatchers.IO) {
    if (points.size < 2) return@withContext emptyList()
    val eventsAll = loadStrategySignalEvents(
        context = context,
        fromTimestampMillis = points.first().timestampMillis,
    )
    val ledger = loadPortfolioExecutionLedger(context)
    val ledgerIncludeAuto = TinkoffSandboxStorage.isPortfolioLedgerIncludeAuto(context)
    val pushLog = loadPushNotificationLog(context)
    val sandboxRaw = TinkoffSandboxSpreadExecLog.loadRecent(context)
    val (_, opensAfterJournalClose) = buildClosedRowsFromSandboxOpensAndJournalExits(
        openExecutions = sandboxRaw,
        allJournalEvents = eventsAll,
        points = points,
        ledger = ledger,
        pushLog = pushLog,
        notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
        leverage = 7.0,
        commissionPercentPerSide = 0.04,
        portfolioLedgerIncludeAuto = ledgerIncludeAuto,
    )
    val modeFiltered = filterSandboxExecutionsByPortfolioMode(
        opensAfterJournalClose,
        ledgerIncludeAuto,
    )
    val enriched = enrichOpenExecutionsForBackgroundMonitor(
        context = context,
        executions = modeFiltered,
        points = points,
    )
    filterSandboxExecutionsForTradesTable(enriched, autoOnly = false)
        .asReversed()
        .map { it.toTradeGroup() }
}

internal suspend fun processOpenTradeRedRiskNotifications(
    context: Context,
    points: List<DataPoint>,
    entryThreshold: Double,
    nowMillis: Long = System.currentTimeMillis(),
) {
    if (points.size < 2) return
    val openGroups = loadOpenPortfolioTradeGroupsForRiskMonitor(context, points)
    val assessments = buildPortfolioTradeGroupRiskAssessments(
        groups = openGroups,
        entryThreshold = entryThreshold,
        nowMillis = nowMillis,
    )
    val previousStates = loadOpenTradeRedRiskNotifyStates(context)
    val (actions, nextStates) = planOpenTradeRedRiskNotifications(
        openGroups = openGroups,
        assessments = assessments,
        previousStates = previousStates,
        nowMillis = nowMillis,
    )
    if (actions.isNotEmpty()) {
        dispatchOpenTradeRedRiskNotifications(context, actions)
    }
    saveOpenTradeRedRiskNotifyStates(context, nextStates)
}

internal fun dispatchOpenTradeRedRiskNotifications(
    context: Context,
    actions: List<OpenTradeRedRiskNotifyAction>,
) {
    for (action in actions) {
        when (action.kind) {
            OpenTradeRedRiskNotifyKind.Entered ->
                showOpenTradeRedRiskPushNotification(
                    context = context,
                    title = "Риск: красная зона",
                    body = buildOpenTradeRedRiskEnteredBody(action),
                    notificationId = openTradeRedRiskNotificationId(action.tradeId, "enter"),
                    correlationTag = openTradeRedRiskCorrelationTag(action.tradeId, "enter"),
                )

            OpenTradeRedRiskNotifyKind.Reminder ->
                showOpenTradeRedRiskPushNotification(
                    context = context,
                    title = "Риск: всё ещё красная зона",
                    body = buildOpenTradeRedRiskReminderBody(action),
                    notificationId = (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
                    correlationTag = openTradeRedRiskCorrelationTag(action.tradeId, "remind"),
                )

            OpenTradeRedRiskNotifyKind.Exited ->
                showOpenTradeRedRiskPushNotification(
                    context = context,
                    title = "Риск: выход из красной зоны",
                    body = buildOpenTradeRedRiskExitedBody(action),
                    notificationId = openTradeRedRiskNotificationId(action.tradeId, "exit"),
                    correlationTag = openTradeRedRiskCorrelationTag(action.tradeId, "exit"),
                )
        }
    }
}

private fun showOpenTradeRedRiskPushNotification(
    context: Context,
    title: String,
    body: String,
    notificationId: Int,
    correlationTag: String,
) {
    showPushNotification(
        context = context,
        title = title,
        body = body,
        notificationId = notificationId,
        correlationTag = correlationTag,
    )
}

internal fun buildOpenTradeRedRiskEnteredBody(action: OpenTradeRedRiskNotifyAction): String {
    val assessment = action.assessment ?: return "${action.tradeDisplayId}: красная зона риска"
    return String.format(
        Locale.US,
        "%s · %s · %d баллов · %s",
        action.tradeDisplayId,
        strategyTestTradeRiskLevelLabel(assessment.level),
        assessment.score,
        formatStrategyTestTradeRiskFlags(assessment),
    )
}

internal fun buildOpenTradeRedRiskReminderBody(action: OpenTradeRedRiskNotifyAction): String {
    val assessment = action.assessment ?: return "${action.tradeDisplayId}: всё ещё красная зона"
    return String.format(
        Locale.US,
        "%s · %d баллов · %s",
        action.tradeDisplayId,
        assessment.score,
        formatStrategyTestTradeRiskFlags(assessment),
    )
}

internal fun buildOpenTradeRedRiskExitedBody(action: OpenTradeRedRiskNotifyAction): String {
    val assessment = action.assessment
    return if (assessment != null) {
        String.format(
            Locale.US,
            "%s · сейчас %s (%d баллов)",
            action.tradeDisplayId,
            strategyTestTradeRiskLevelLabel(assessment.level),
            assessment.score,
        )
    } else {
        String.format(Locale.US, "%s · сделка закрыта", action.tradeDisplayId)
    }
}

internal fun openTradeRedRiskCorrelationTag(tradeId: String, kind: String): String =
    "openTradeRedRisk|$kind|$tradeId"

internal fun openTradeRedRiskNotificationId(tradeId: String, kind: String): Int =
    ("openTradeRedRisk|$kind|$tradeId").hashCode()

internal fun loadOpenTradeRedRiskNotifyStates(context: Context): Map<String, OpenTradeRedRiskNotifyState> {
    val raw = context.getSharedPreferences(OPEN_TRADE_RISK_NOTIFY_PREFS, Context.MODE_PRIVATE)
        .getString(PREF_RED_RISK_STATES_JSON, null)
        ?: return emptyMap()
    return runCatching { decodeOpenTradeRedRiskNotifyStates(raw) }.getOrDefault(emptyMap())
}

internal fun saveOpenTradeRedRiskNotifyStates(
    context: Context,
    states: Map<String, OpenTradeRedRiskNotifyState>,
) {
    context.getSharedPreferences(OPEN_TRADE_RISK_NOTIFY_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_RED_RISK_STATES_JSON, encodeOpenTradeRedRiskNotifyStates(states))
        .apply()
}

internal fun encodeOpenTradeRedRiskNotifyStates(
    states: Map<String, OpenTradeRedRiskNotifyState>,
): String {
    val root = JSONObject()
    for ((tradeId, state) in states) {
        root.put(
            tradeId,
            JSONObject()
                .put("inRedZone", state.inRedZone)
                .put("lastReminderAtMillis", state.lastReminderAtMillis),
        )
    }
    return root.toString()
}

internal fun decodeOpenTradeRedRiskNotifyStates(raw: String): Map<String, OpenTradeRedRiskNotifyState> {
    val root = JSONObject(raw)
    val out = linkedMapOf<String, OpenTradeRedRiskNotifyState>()
    for (key in root.keys()) {
        val o = root.optJSONObject(key) ?: continue
        out[key] = OpenTradeRedRiskNotifyState(
            inRedZone = o.optBoolean("inRedZone", false),
            lastReminderAtMillis = o.optLong("lastReminderAtMillis", 0L),
        )
    }
    return out
}
