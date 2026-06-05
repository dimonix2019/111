package com.example.moexmvp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val PUSH_CHANNEL_ID = "moex_push_channel"
internal const val PUSH_TOPIC = "moex_updates"
internal const val PUSH_LOG_TAG = "MoexPush"
/** PendingIntent → MainActivity: восстановить карточку «Принять» по данным из уведомления. */
internal const val EXTRA_TAP_RESTORE_VIRTUAL_TRADE = "moex_tap_restore_virtual_trade"
internal const val EXTRA_TAP_SIGNAL_TYPE = "moex_tap_signal_type"
internal const val EXTRA_TAP_Z = "moex_tap_z"
internal const val EXTRA_TAP_TS = "moex_tap_ts"
internal const val EXTRA_TAP_OPEN_APP_UPDATE = "moex_tap_open_app_update"
internal const val EXTRA_TAP_APP_UPDATE_VERSION_CODE = "moex_tap_app_update_version_code"
internal const val EXTRA_TAP_APP_UPDATE_VERSION_NAME = "moex_tap_app_update_version_name"
internal const val EXTRA_TAP_APP_UPDATE_APK_URL = "moex_tap_app_update_apk_url"

internal data class VirtualTradeTapIntent(
    val signalType: StrategySignalType,
    val zScore: Double,
    val timestampMillis: Long
)
private const val PUSH_DEDUP_PREFS_NAME = "moex_push_dedup"
private const val PREF_PUSH_LAST_SIGNATURE = "push_last_signature"
private const val PREF_PUSH_LAST_TIMESTAMP_MS = "push_last_timestamp_ms"
private const val PUSH_DEDUP_WINDOW_MS = 10_000L
private const val SIGNAL_EVENTS_PREFS_NAME = "moex_signal_events"
private const val PREF_SIGNAL_EVENTS_JSON = "strategy_signal_events_json"
private const val PREF_SIGNAL_LAST_WALL_MS = "strategy_signal_last_journal_wall_ms"
private const val PREF_SIGNAL_LAST_TYPE = "strategy_signal_last_journal_type"
private const val MAX_SIGNAL_EVENTS = 800
private val pushDedupLock = Any()
private val signalEventsLock = Any()

internal enum class StrategySignalType {
    EnterLong,
    EnterShort,
    ExitLong,
    ExitShort
}

internal data class StrategySignalEvent(
    /** Время 15м бара сигнала. */
    val timestampMillis: Long,
    val signalType: StrategySignalType,
    val zScore: Double,
    /** Когда запись попала в журнал (wall clock, МСК в UI). */
    val receivedAtMillis: Long = timestampMillis
)

internal fun spreadLegPushCorrelationTag(barTimestampMillis: Long, signalType: StrategySignalType): String =
    "spreadLeg|${barTimestampMillis}|${signalType.name}"

/** Устаревший ID (до еженедельной нумерации): тип + ts бара 15м. */
internal fun legacyStrategySignalDisplayId(barTimestampMillis: Long, signalType: StrategySignalType): String {
    val code = when (signalType) {
        StrategySignalType.EnterLong -> "EL"
        StrategySignalType.EnterShort -> "ES"
        StrategySignalType.ExitLong -> "XL"
        StrategySignalType.ExitShort -> "XS"
    }
    return "$code-$barTimestampMillis"
}

internal data class StrategySignalDisplay(
    /** Полный ID: «3 long 2026-06-01 14:30». */
    val signalId: String,
    /** Короткий ID сделки: «3 long». */
    val tradeDisplayId: String,
    val barTimeMsk: String,
    val receivedTimeMsk: String
)

internal fun strategySignalDisplay(
    event: StrategySignalEvent,
    journalEvents: List<StrategySignalEvent> = listOf(event),
): StrategySignalDisplay {
    val index = buildWeeklySignalNumberIndex(journalEvents)
    val weeklyLabel = weeklyStrategySignalLabel(index, event)
    val weeklyTradeId = if (event.signalType == StrategySignalType.EnterLong ||
        event.signalType == StrategySignalType.EnterShort
    ) {
        weeklyTradeDisplayId(index, event.timestampMillis, event.signalType)
    } else {
        null
    }
    val legacyId = legacyStrategySignalDisplayId(event.timestampMillis, event.signalType)
    return StrategySignalDisplay(
        signalId = weeklyLabel ?: legacyId,
        tradeDisplayId = weeklyTradeId ?: legacyId,
        barTimeMsk = formatPortfolioExecutionTableMsk(event.timestampMillis),
        receivedTimeMsk = formatMessageReceivedAtMsk(event.receivedAtMillis)
    )
}

internal fun strategySignalDisplay(
    barTimestampMillis: Long,
    signalType: StrategySignalType,
    receivedAtMillis: Long = barTimestampMillis,
    journalEvents: List<StrategySignalEvent> = emptyList(),
): StrategySignalDisplay {
    val ev = journalEvents.lastOrNull {
        it.timestampMillis == barTimestampMillis && it.signalType == signalType
    } ?: StrategySignalEvent(
        timestampMillis = barTimestampMillis,
        signalType = signalType,
        zScore = 0.0,
        receivedAtMillis = receivedAtMillis,
    )
    return strategySignalDisplay(ev, journalEvents.ifEmpty { listOf(ev) })
}

/** Поля для таблицы сделок: ID сигнала входа, бар 15м, время записи в журнал. */
internal fun entrySignalDisplayFields(
    journalEvents: List<StrategySignalEvent>,
    barTimestampMillis: Long,
    signalType: StrategySignalType,
    fallbackReceivedAtMillis: Long = barTimestampMillis
): Triple<String, String, String> {
    val ev = journalEvents.lastOrNull {
        it.timestampMillis == barTimestampMillis && it.signalType == signalType
    }
    val d = if (ev != null) {
        strategySignalDisplay(ev, journalEvents)
    } else {
        strategySignalDisplay(barTimestampMillis, signalType, fallbackReceivedAtMillis, journalEvents)
    }
    return Triple(d.signalId, d.barTimeMsk, d.receivedTimeMsk)
}

/** Короткий ID сделки для таблицы портфеля: «3 long». */
internal fun entryTradeDisplayId(
    journalEvents: List<StrategySignalEvent>,
    barTimestampMillis: Long,
    signalType: StrategySignalType,
    fallbackReceivedAtMillis: Long = barTimestampMillis,
): String {
    val ev = journalEvents.lastOrNull {
        it.timestampMillis == barTimestampMillis && it.signalType == signalType
    }
    val d = if (ev != null) {
        strategySignalDisplay(ev, journalEvents)
    } else {
        strategySignalDisplay(barTimestampMillis, signalType, fallbackReceivedAtMillis, journalEvents)
    }
    return d.tradeDisplayId
}

/** Заголовок push для сигнала Z (как в шторке). */
internal fun strategySignalPushTitle(signalType: StrategySignalType): String = when (signalType) {
    StrategySignalType.EnterLong -> "Вход: LONG TATN / SHORT TATNP"
    StrategySignalType.EnterShort -> "Вход: LONG TATNP / SHORT TATN"
    StrategySignalType.ExitLong -> "Выход: закрыть LONG TATN / SHORT TATNP"
    StrategySignalType.ExitShort -> "Выход: закрыть LONG TATNP / SHORT TATN"
}

/** Тело push без строки «Получено: …» (она показывается отдельно). */
internal fun strategySignalPushBody(
    signalType: StrategySignalType,
    zScore: Double,
    entryThreshold: Double,
    exitThreshold: Double,
): String = when (signalType) {
    StrategySignalType.EnterLong -> String.format(
        Locale.US,
        "Z <= -%.1f (текущий Z=%.2f)",
        entryThreshold,
        zScore
    )
    StrategySignalType.EnterShort -> String.format(
        Locale.US,
        "Z >= +%.1f (текущий Z=%.2f)",
        entryThreshold,
        zScore
    )
    StrategySignalType.ExitLong -> String.format(
        Locale.US,
        "Z >= -%.1f (текущий Z=%.2f)",
        exitThreshold,
        zScore
    )
    StrategySignalType.ExitShort -> String.format(
        Locale.US,
        "Z <= +%.1f (текущий Z=%.2f)",
        exitThreshold,
        zScore
    )
}

internal fun strategySignalPushBodyForDisplay(body: String): String =
    body.lineSequence()
        .filterNot { messageBodyHasReceivedTimeLine(it) }
        .joinToString("\n")
        .trim()

/** Связка записи журнала сигналов с локальным логом push. */
internal fun findPushLogForStrategySignal(
    event: StrategySignalEvent,
    pushLog: List<PushNotificationLogEntry>,
): PushNotificationLogEntry? {
    pushLog.asReversed().firstOrNull { entry ->
        entry.virtualTapBarTimestampMillis == event.timestampMillis &&
            entry.virtualTapSignalType == event.signalType.name
    }?.let { return it }
    val title = strategySignalPushTitle(event.signalType)
    return pushLog.asReversed().firstOrNull { entry ->
        entry.title == title &&
            kotlin.math.abs(entry.wallTimestampMillis - event.receivedAtMillis) <= 120_000L
    }
}

internal data class StrategySignalJournalPushView(
    val receivedAtMillis: Long,
    val signalId: String,
    val pushIdText: String,
    val title: String,
    val messageBody: String,
    val pushStatusText: String?,
    val pushPosted: Boolean?,
)

internal fun buildStrategySignalJournalPushView(
    event: StrategySignalEvent,
    pushLog: List<PushNotificationLogEntry>,
    entryThreshold: Double,
    exitThreshold: Double,
    allJournalEvents: List<StrategySignalEvent> = listOf(event),
): StrategySignalJournalPushView {
    val sig = strategySignalDisplay(event, allJournalEvents)
    val push = findPushLogForStrategySignal(event, pushLog)
    val title = push?.title ?: strategySignalPushTitle(event.signalType)
    val rawBody = push?.body ?: strategySignalPushBody(
        event.signalType,
        event.zScore,
        entryThreshold,
        exitThreshold,
    )
    val messageBody = strategySignalPushBodyForDisplay(rawBody)
    val (statusText, posted) = when {
        push == null -> "Push не отправлен" to false
        push.posted -> "Показано в шторке" to true
        push.skipReason == PushNotificationLogSkipReason.DUPLICATE_WITHIN_WINDOW ->
            "Пропущено (дубликат)" to false
        push.skipReason == PushNotificationLogSkipReason.POST_NOTIFICATIONS_DENIED ->
            "Пропущено (нет разрешения)" to false
        push.skipReason != null -> "Пропущено (${push.skipReason})" to false
        else -> "Не показано" to false
    }
    return StrategySignalJournalPushView(
        receivedAtMillis = event.receivedAtMillis,
        signalId = sig.signalId,
        pushIdText = push?.let { formatPushNotificationIdDisplay(it) } ?: "—",
        title = title,
        messageBody = messageBody,
        pushStatusText = statusText,
        pushPosted = posted,
    )
}

internal fun createPushNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
        PUSH_CHANNEL_ID,
        "MOEX Push",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "Push notifications for MOEX updates"
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    manager?.createNotificationChannel(channel)
}

internal fun showPushNotification(
    context: Context,
    title: String,
    body: String,
    notificationId: Int = System.currentTimeMillis().toInt(),
    virtualTradeTap: VirtualTradeTapIntent? = null,
    appUpdateTap: AppRemoteUpdate? = null,
    skipDuplicateCheck: Boolean = false,
    correlationTag: String? = null
): Boolean {
    val app = context.applicationContext
    val receivedAtMillis = System.currentTimeMillis()
    val displayBody = ensureMessageBodyHasReceivedTime(body, receivedAtMillis)
    fun trace(posted: Boolean, skipReason: String?, nid: Int? = null) {
        appendPushNotificationLogEntry(
            app,
            PushNotificationLogEntry(
                wallTimestampMillis = receivedAtMillis,
                title = title,
                body = displayBody,
                posted = posted,
                skipReason = skipReason,
                virtualTapSignalType = virtualTradeTap?.signalType?.name,
                virtualTapZ = virtualTradeTap?.zScore,
                virtualTapBarTimestampMillis = virtualTradeTap?.timestampMillis,
                notificationId = if (posted) nid else null,
                correlationTag = correlationTag
            )
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(PUSH_LOG_TAG, "Skipping notification: POST_NOTIFICATIONS is not granted")
            trace(false, PushNotificationLogSkipReason.POST_NOTIFICATIONS_DENIED, null)
            return false
        }
    }
    if (!skipDuplicateCheck && shouldSkipDuplicatePush(context, title, body)) {
        Log.d(PUSH_LOG_TAG, "Skipping duplicate notification: $title | $displayBody")
        trace(false, PushNotificationLogSkipReason.DUPLICATE_WITHIN_WINDOW, null)
        return false
    }

    createPushNotificationChannel(context)
    val intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (virtualTradeTap != null) {
            putExtra(EXTRA_TAP_RESTORE_VIRTUAL_TRADE, true)
            putExtra(EXTRA_TAP_SIGNAL_TYPE, virtualTradeTap.signalType.name)
            putExtra(EXTRA_TAP_Z, virtualTradeTap.zScore)
            putExtra(EXTRA_TAP_TS, virtualTradeTap.timestampMillis)
        }
        if (appUpdateTap != null) {
            putExtra(EXTRA_TAP_OPEN_APP_UPDATE, true)
            putExtra(EXTRA_TAP_APP_UPDATE_VERSION_CODE, appUpdateTap.versionCode)
            putExtra(EXTRA_TAP_APP_UPDATE_VERSION_NAME, appUpdateTap.versionName)
            putExtra(EXTRA_TAP_APP_UPDATE_APK_URL, appUpdateTap.apkDownloadUrl)
        }
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        notificationId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, PUSH_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(displayBody)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .build()

    NotificationManagerCompat.from(context).notify(notificationId, notification)
    trace(true, null, notificationId)
    return true
}

internal fun sandboxTradeOpenedPushCorrelationTag(
    barTimestampMillis: Long,
    signalType: StrategySignalType,
): String = "tradeOpened|${barTimestampMillis}|${signalType.name}"

/** Текст push «Сделка открыта» — все поля сделки и номер сигнала. */
internal fun buildSandboxTradeOpenedNotificationBody(
    execution: SandboxSpreadExecUi,
    journalEvents: List<StrategySignalEvent>,
    notionalRub: Double,
    leverage: Double,
    portfolioTotalRub: String? = null,
    portfolioCashRub: String? = null,
    openedAtMillis: Long = System.currentTimeMillis(),
): String {
    val sig = strategySignalDisplay(
        barTimestampMillis = execution.barTimestampMillis,
        signalType = execution.signalType,
        receivedAtMillis = execution.executedAtMillis,
        journalEvents = journalEvents,
    )
    val tradeId = entryTradeDisplayId(
        journalEvents = journalEvents,
        barTimestampMillis = execution.barTimestampMillis,
        signalType = execution.signalType,
        fallbackReceivedAtMillis = execution.executedAtMillis,
    )
    return buildString {
        append(formatMessageReceivedLine(openedAtMillis))
        append('\n')
        append("Сигнал: ")
        append(compactPortfolioTableDateLabel(sig.signalId))
        append('\n')
        append("Сделка: ")
        append(tradeId)
        append('\n')
        append("ID: ")
        append(execution.tradeId)
        append('\n')
        append("Направление: ")
        append(execution.directionLabel)
        append('\n')
        append(
            String.format(
                Locale.US,
                "Z вход: %.2f · спрэд вход: %.2f%%",
                execution.zScore,
                execution.entrySpreadPercent,
            )
        )
        append('\n')
        append("Бар сигнала: ")
        append(compactPortfolioTableDateLabel(execution.entrySignalBarTimeMsk))
        append('\n')
        append("Получен: ")
        append(compactPortfolioTableDateLabel(execution.entrySignalReceivedMsk))
        append('\n')
        append("Вход: ")
        append(compactPortfolioTableDateLabel(execution.entryTimeMsk))
        append('\n')
        append("Подтв.: ")
        append(execution.confirmLabel)
        append('\n')
        append(
            String.format(
                Locale.US,
                "Номинал: %.0f ₽ · плечо ×%.1f · объём: %s",
                notionalRub,
                leverage,
                execution.volumeText,
            )
        )
        execution.legs.forEachIndexed { index, leg ->
            append('\n')
            append("Нога ")
            append(index + 1)
            append(": ")
            append(leg.ticker)
            append(" · ")
            append(leg.sideRu)
            if (leg.orderBrief.isNotBlank() && leg.orderBrief != "—") {
                append(" · ")
                append(leg.orderBrief)
            }
        }
        portfolioTotalRub?.let {
            append('\n')
            append("Баланс портфеля: ")
            append(it)
        }
        portfolioCashRub?.let {
            append('\n')
            append("Деньги (₽): ")
            append(it)
        }
    }
}

internal fun sandboxTradeClosedPushCorrelationTag(
    barTimestampMillis: Long,
    signalType: StrategySignalType,
): String = "tradeClosed|${barTimestampMillis}|${signalType.name}"

/** Текст push «Сделка закрыта» — сигнал выхода, PnL сделки и общий PnL счёта. */
internal fun buildSandboxTradeClosedNotificationBody(
    execution: SandboxSpreadExecUi,
    exitSignalType: StrategySignalType,
    exitBarTimestampMillis: Long,
    exitZScore: Double,
    tradePnl: SandboxClosedTradePnl,
    accountTotalPnlRub: Double,
    journalEvents: List<StrategySignalEvent>,
    notionalRub: Double,
    leverage: Double,
    portfolioTotalRub: String? = null,
    portfolioCashRub: String? = null,
    closedAtMillis: Long = System.currentTimeMillis(),
): String {
    val entryTradeId = entryTradeDisplayId(
        journalEvents = journalEvents,
        barTimestampMillis = execution.barTimestampMillis,
        signalType = execution.signalType,
        fallbackReceivedAtMillis = execution.executedAtMillis,
    )
    val exitSig = strategySignalDisplay(
        barTimestampMillis = exitBarTimestampMillis,
        signalType = exitSignalType,
        receivedAtMillis = exitBarTimestampMillis,
        journalEvents = journalEvents,
    )
    val exitTimeMsk = formatPortfolioExecutionTableMsk(exitBarTimestampMillis)
    return buildString {
        append(formatMessageReceivedLine(closedAtMillis))
        append('\n')
        append("Сигнал выхода: ")
        append(compactPortfolioTableDateLabel(exitSig.signalId))
        append('\n')
        append("Сделка: ")
        append(entryTradeId)
        append('\n')
        append("ID: ")
        append(execution.tradeId)
        append('\n')
        append(
            String.format(
                Locale.US,
                "Z вход: %.2f · Z выход: %.2f",
                execution.zScore,
                exitZScore,
            )
        )
        append('\n')
        append(
            String.format(
                Locale.US,
                "Спрэд вход: %.2f%% · выход: %.2f%%",
                execution.entrySpreadPercent,
                tradePnl.exitSpreadPercent,
            )
        )
        append('\n')
        append("Вход: ")
        append(compactPortfolioTableDateLabel(execution.entryTimeMsk))
        append(" · Выход: ")
        append(compactPortfolioTableDateLabel(exitTimeMsk))
        append('\n')
        append("PnL сделки: ")
        append(formatRubSigned(tradePnl.netRub))
        append('\n')
        append(
            String.format(
                Locale.US,
                "  валовый %s · комис. %s · оверн. %s",
                formatRubSigned(tradePnl.grossRub),
                formatRubSigned(-kotlin.math.abs(tradePnl.commissionRub)),
                formatRubSigned(-kotlin.math.abs(tradePnl.overnightRub)),
            )
        )
        append('\n')
        append("Общий PnL счёта: ")
        append(formatRubSigned(accountTotalPnlRub))
        append('\n')
        append(
            String.format(
                Locale.US,
                "Номинал: %.0f ₽ · плечо ×%.1f",
                notionalRub,
                leverage,
            )
        )
        portfolioTotalRub?.let {
            append('\n')
            append("Баланс счёта: ")
            append(it)
        }
        portfolioCashRub?.let {
            append('\n')
            append("Деньги (₽): ")
            append(it)
        }
    }
}

/** Одно уведомление после закрытия спрэд-сделки на демо. */
internal fun notifySandboxTradeClosed(
    context: Context,
    execution: SandboxSpreadExecUi,
    exitSignalType: StrategySignalType,
    exitBarTimestampMillis: Long,
    exitZScore: Double,
    tradePnl: SandboxClosedTradePnl,
    accountTotalPnlRub: Double,
    notionalRub: Double = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
    leverage: Double,
    portfolioTotalRub: String? = null,
    portfolioCashRub: String? = null,
) {
    val app = context.applicationContext
    val journal = loadStrategySignalEvents(app)
    val entryTradeId = entryTradeDisplayId(
        journalEvents = journal,
        barTimestampMillis = execution.barTimestampMillis,
        signalType = execution.signalType,
        fallbackReceivedAtMillis = execution.executedAtMillis,
    )
    val body = buildSandboxTradeClosedNotificationBody(
        execution = execution,
        exitSignalType = exitSignalType,
        exitBarTimestampMillis = exitBarTimestampMillis,
        exitZScore = exitZScore,
        tradePnl = tradePnl,
        accountTotalPnlRub = accountTotalPnlRub,
        journalEvents = journal,
        notionalRub = notionalRub,
        leverage = leverage,
        portfolioTotalRub = portfolioTotalRub,
        portfolioCashRub = portfolioCashRub,
    )
    showPushNotification(
        context = app,
        title = "Сделка закрыта · $entryTradeId · ${formatRubSigned(tradePnl.netRub)}",
        body = body,
        virtualTradeTap = null,
        skipDuplicateCheck = true,
        correlationTag = sandboxTradeClosedPushCorrelationTag(exitBarTimestampMillis, exitSignalType),
    )
}

/** Одно уведомление после открытия спрэд-сделки на демо (вместо push по ногам на входе). */
internal fun notifySandboxTradeOpened(
    context: Context,
    execution: SandboxSpreadExecUi,
    notionalRub: Double = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
    leverage: Double,
    portfolioTotalRub: String? = null,
    portfolioCashRub: String? = null,
) {
    val app = context.applicationContext
    val journal = loadStrategySignalEvents(app)
    val tradeId = entryTradeDisplayId(
        journalEvents = journal,
        barTimestampMillis = execution.barTimestampMillis,
        signalType = execution.signalType,
        fallbackReceivedAtMillis = execution.executedAtMillis,
    )
    val body = buildSandboxTradeOpenedNotificationBody(
        execution = execution,
        journalEvents = journal,
        notionalRub = notionalRub,
        leverage = leverage,
        portfolioTotalRub = portfolioTotalRub,
        portfolioCashRub = portfolioCashRub,
    )
    showPushNotification(
        context = app,
        title = "Сделка открыта · $tradeId",
        body = body,
        virtualTradeTap = null,
        skipDuplicateCheck = true,
        correlationTag = sandboxTradeOpenedPushCorrelationTag(
            execution.barTimestampMillis,
            execution.signalType,
        ),
    )
}

/** Отдельное уведомление по каждой ноге спрэда после PostSandboxOrder (тест песочницы). */
internal fun notifySandboxSpreadLegExecutionResults(
    context: Context,
    legs: List<SandboxLegOrderResult>,
    notionalRub: Double,
    leverage: Double,
    correlationTag: String?
) {
    val app = context.applicationContext
    legs.forEachIndexed { index, leg ->
        val execMsk = formatMessageReceivedAtMsk(leg.completedAtMillis)
        val brief = formatPostSandboxOrderBrief(leg.orderJson)
        val body = buildString {
            append("Исполнено: ")
            append(execMsk)
            append(" (МСК)\n")
            append("Тикер: ")
            append(leg.ticker)
            append(" · ")
            append(leg.sideRu)
            append("\nЗаявка: ")
            append(brief)
            append("\n")
            append(
                String.format(
                    Locale.US,
                    "Сумма (тест, номинал стратегии): %.0f ₽ · плечо ×%.1f · лоты: 1\n",
                    notionalRub,
                    leverage
                )
            )
            leg.portfolioTotalRub?.let { append("Баланс портфеля после ноги: $it\n") }
            leg.portfolioCashRub?.let { append("Деньги (₽) после ноги: $it") }
        }
        val nid = kotlin.math.abs((System.nanoTime() xor (index * 49999L)).toInt()) % 1_000_000_000
        showPushNotification(
            context = app,
            title = "Песочница · нога ${index + 1}/${legs.size}: ${leg.ticker}",
            body = body,
            notificationId = nid,
            virtualTradeTap = null,
            skipDuplicateCheck = true,
            correlationTag = correlationTag
        )
    }
}

private fun shouldSkipDuplicatePush(context: Context, title: String, body: String): Boolean {
    val signature = "$title|$body"
    val nowMs = System.currentTimeMillis()
    synchronized(pushDedupLock) {
        val prefs = context.getSharedPreferences(PUSH_DEDUP_PREFS_NAME, Context.MODE_PRIVATE)
        val lastSignature = prefs.getString(PREF_PUSH_LAST_SIGNATURE, null)
        val lastTimestampMs = prefs.getLong(PREF_PUSH_LAST_TIMESTAMP_MS, 0L)
        val isDuplicate = signature == lastSignature && (nowMs - lastTimestampMs) in 0 until PUSH_DEDUP_WINDOW_MS
        if (!isDuplicate) {
            prefs.edit()
                .putString(PREF_PUSH_LAST_SIGNATURE, signature)
                .putLong(PREF_PUSH_LAST_TIMESTAMP_MS, nowMs)
                .apply()
        }
        return isDuplicate
    }
}

internal fun showSpreadCrossPushNotification(context: Context, spreadPercent: Double) {
    val body = String.format(Locale.US, "Spread crossed above 5%%: %.2f%%", spreadPercent)
    showPushNotification(
        context = context,
        title = "MOEX alert",
        body = body
    )
}

internal fun showZScoreCrossPushNotification(
    context: Context,
    zScore: Double,
    level: Double,
    direction: String
) {
    val body = String.format(
        Locale.US,
        "Z-score crossed %.1f %s: %.2f",
        level,
        direction,
        zScore
    )
    showPushNotification(
        context = context,
        title = "MOEX Z-score alert",
        body = body
    )
}

internal fun showZStrategySignalPushNotification(
    context: Context,
    title: String,
    body: String,
    virtualTradeTap: VirtualTradeTapIntent? = null
): Boolean {
    return showPushNotification(
        context = context,
        title = title,
        body = body,
        virtualTradeTap = virtualTradeTap
    )
}

internal fun showAppUpdatePushNotification(context: Context, update: AppRemoteUpdate): Boolean {
    val notes = changelogSummaryForBuild(update.versionName)?.lineSequence()?.firstOrNull()?.trim()
    val body = buildString {
        append("Сборка ${update.versionName} (${update.versionCode}). У вас ")
        append(BuildConfig.VERSION_NAME)
        append(" (")
        append(BuildConfig.VERSION_CODE)
        append(").")
        if (!notes.isNullOrBlank()) {
            append("\n")
            append(notes)
        }
    }
    return showPushNotification(
        context = context,
        title = "Доступно обновление MOEX MVP",
        body = body,
        notificationId = APP_UPDATE_PUSH_NOTIFICATION_ID,
        appUpdateTap = update,
        skipDuplicateCheck = true,
        correlationTag = "appUpdate|${update.versionCode}"
    )
}

/** По тапу на push об обновлении — открыть диалог установки в MainActivity. */
internal fun parseAppUpdateFromTapIntent(intent: Intent?): AppRemoteUpdate? {
    if (intent == null || !intent.getBooleanExtra(EXTRA_TAP_OPEN_APP_UPDATE, false)) return null
    val versionCode = intent.getIntExtra(EXTRA_TAP_APP_UPDATE_VERSION_CODE, -1)
    if (versionCode <= 0) return null
    val versionName = intent.getStringExtra(EXTRA_TAP_APP_UPDATE_VERSION_NAME)?.trim().orEmpty()
        .ifBlank { "?" }
    val apkUrl = intent.getStringExtra(EXTRA_TAP_APP_UPDATE_APK_URL)?.trim().orEmpty()
        .ifBlank { APK_DOWNLOAD_DIRECT_URL }
    intent.removeExtra(EXTRA_TAP_OPEN_APP_UPDATE)
    intent.removeExtra(EXTRA_TAP_APP_UPDATE_VERSION_CODE)
    intent.removeExtra(EXTRA_TAP_APP_UPDATE_VERSION_NAME)
    intent.removeExtra(EXTRA_TAP_APP_UPDATE_APK_URL)
    return AppRemoteUpdate(versionCode, versionName, apkUrl)
}

/** Вызывать при старте/resume Activity: из PendingIntent уведомления восстановить карточку входа. */
internal fun applyVirtualTradeTapIntent(context: Context, intent: Intent?) {
    if (intent == null) return
    if (!intent.getBooleanExtra(EXTRA_TAP_RESTORE_VIRTUAL_TRADE, false)) return
    if (TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context)) {
        intent.removeExtra(EXTRA_TAP_RESTORE_VIRTUAL_TRADE)
        intent.removeExtra(EXTRA_TAP_SIGNAL_TYPE)
        intent.removeExtra(EXTRA_TAP_Z)
        intent.removeExtra(EXTRA_TAP_TS)
        return
    }
    val typeName = intent.getStringExtra(EXTRA_TAP_SIGNAL_TYPE) ?: return
    val type = runCatching { StrategySignalType.valueOf(typeName) }.getOrNull()
    if (type == null) {
        intent.removeExtra(EXTRA_TAP_RESTORE_VIRTUAL_TRADE)
        intent.removeExtra(EXTRA_TAP_SIGNAL_TYPE)
        intent.removeExtra(EXTRA_TAP_Z)
        intent.removeExtra(EXTRA_TAP_TS)
        return
    }
    if (type != StrategySignalType.EnterLong && type != StrategySignalType.EnterShort) {
        intent.removeExtra(EXTRA_TAP_RESTORE_VIRTUAL_TRADE)
        intent.removeExtra(EXTRA_TAP_SIGNAL_TYPE)
        intent.removeExtra(EXTRA_TAP_Z)
        intent.removeExtra(EXTRA_TAP_TS)
        return
    }
    val z = intent.getDoubleExtra(EXTRA_TAP_Z, 0.0)
    val ts = intent.getLongExtra(EXTRA_TAP_TS, System.currentTimeMillis())
    savePendingVirtualTradeProposal(context, type, z, ts)
    intent.removeExtra(EXTRA_TAP_RESTORE_VIRTUAL_TRADE)
    intent.removeExtra(EXTRA_TAP_SIGNAL_TYPE)
    intent.removeExtra(EXTRA_TAP_Z)
    intent.removeExtra(EXTRA_TAP_TS)
}

internal fun recordStrategySignalEvent(
    context: Context,
    signalType: StrategySignalType,
    zScore: Double,
    timestampMillis: Long,
    /** When true, skip 25s wall dedup (e.g. sandbox «Принять» right after the same signal was logged). */
    skipJournalWallDedup: Boolean = false,
    /** When false, do not refresh pending virtual-trade card (used after sandbox execution). */
    savePendingVirtualTradeIfEntry: Boolean = true
) {
    synchronized(signalEventsLock) signalLock@{
        val prefs = context.getSharedPreferences(SIGNAL_EVENTS_PREFS_NAME, Context.MODE_PRIVATE)
        val nowWall = System.currentTimeMillis()
        if (!skipJournalWallDedup) {
            val prevWall = prefs.getLong(PREF_SIGNAL_LAST_WALL_MS, 0L)
            val prevType = prefs.getString(PREF_SIGNAL_LAST_TYPE, null)
            if (prevWall > 0L && prevType == signalType.name &&
                (nowWall - prevWall) <= STRATEGY_SIGNAL_JOURNAL_DEDUP_WALL_MS
            ) {
                // Дубликат строки в журнал не пишем, но ниже всё равно обновим pending-карточку для входа.
                return@signalLock
            }
        }
        val existing = prefs.getString(PREF_SIGNAL_EVENTS_JSON, null).orEmpty()
        val source = runCatching { JSONArray(existing) }.getOrElse { JSONArray() }
        val events = mutableListOf<StrategySignalEvent>()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val typeName = item.optString("signalType")
            val type = runCatching { StrategySignalType.valueOf(typeName) }.getOrNull() ?: continue
            val barTs = item.optLong("timestampMillis", 0L)
            events += StrategySignalEvent(
                timestampMillis = barTs,
                signalType = type,
                zScore = item.optDouble("zScore", 0.0),
                receivedAtMillis = item.optLong("receivedAtMillis", barTs)
            )
        }
        val receivedAt = System.currentTimeMillis()
        events += StrategySignalEvent(
            timestampMillis = timestampMillis,
            signalType = signalType,
            zScore = zScore,
            receivedAtMillis = receivedAt
        )
        val trimmed = events
            .sortedBy { it.timestampMillis }
            .takeLast(MAX_SIGNAL_EVENTS)
        val output = JSONArray()
        trimmed.forEach { event ->
            output.put(
                JSONObject()
                    .put("timestampMillis", event.timestampMillis)
                    .put("signalType", event.signalType.name)
                    .put("zScore", event.zScore)
                    .put("receivedAtMillis", event.receivedAtMillis)
            )
        }
        prefs.edit()
            .putString(PREF_SIGNAL_EVENTS_JSON, output.toString())
            .putLong(PREF_SIGNAL_LAST_WALL_MS, nowWall)
            .putString(PREF_SIGNAL_LAST_TYPE, signalType.name)
            .apply()
    }
    if (savePendingVirtualTradeIfEntry &&
        !TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context) &&
        (signalType == StrategySignalType.EnterLong || signalType == StrategySignalType.EnterShort)
    ) {
        savePendingVirtualTradeProposal(
            context = context,
            signalType = signalType,
            zScore = zScore,
            timestampMillis = timestampMillis
        )
    }
}

internal fun loadStrategySignalEvents(
    context: Context,
    fromTimestampMillis: Long? = null
): List<StrategySignalEvent> {
    synchronized(signalEventsLock) {
        val raw = context.getSharedPreferences(SIGNAL_EVENTS_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_SIGNAL_EVENTS_JSON, null)
            .orEmpty()
        val source = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                val typeName = item.optString("signalType")
                val type = runCatching { StrategySignalType.valueOf(typeName) }.getOrNull() ?: continue
                val timestampMillis = item.optLong("timestampMillis", 0L)
                if (fromTimestampMillis != null && timestampMillis < fromTimestampMillis) continue
                add(
                    StrategySignalEvent(
                        timestampMillis = timestampMillis,
                        signalType = type,
                        zScore = item.optDouble("zScore", 0.0),
                        receivedAtMillis = item.optLong("receivedAtMillis", timestampMillis)
                    )
                )
            }
        }.sortedBy { it.timestampMillis }
    }
}

/**
 * Журнал сигналов (вход/выход), дедуп-ключи, карточка «Принять», последняя запись «Принять» в портфеле,
 * сохранённая позиция Z → FLAT. Не трогает токен/счёт песочницы и динамические пороги.
 */
internal fun clearStrategySignalJournalAndLocalStrategyState(context: Context) {
    val app = context.applicationContext
    synchronized(signalEventsLock) {
        app.getSharedPreferences(SIGNAL_EVENTS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_SIGNAL_EVENTS_JSON)
            .remove(PREF_SIGNAL_LAST_WALL_MS)
            .remove(PREF_SIGNAL_LAST_TYPE)
            .apply()
    }
    clearVirtualTradeProposalPrefs(app)
    saveStrategyPosition(app, ZStrategyPosition.Flat)
    clearConsumed15mStrategySignalEdge(app)
    clearSandboxAutoSpreadDedup(app)
    clearPortfolioExecutionLedger(app)
    TinkoffSandboxSpreadExecLog.clear(app)
    SandboxAccountPnlLedger.clear(app)
}

internal fun showDynamicZThresholdsPushNotification(
    context: Context,
    entry: Double,
    exit: Double,
    dateText: String
) {
    val body = String.format(
        Locale.US,
        "Daily Z thresholds %s: entry %.1f / exit %.1f",
        dateText,
        entry,
        exit
    )
    showPushNotification(
        context = context,
        title = "MOEX Z-score setup",
        body = body
    )
}

class PushMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(PUSH_LOG_TAG, "Refreshed FCM token: $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title ?: "MOEX update"
        val body = message.notification?.body ?: "Новые данные по TATN/TATNP"
        showPushNotification(
            context = this,
            title = title,
            body = body
        )
    }
}
