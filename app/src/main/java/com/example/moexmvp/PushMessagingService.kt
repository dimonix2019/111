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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val PUSH_CHANNEL_ID = "moex_push_channel"
internal const val PUSH_TOPIC = "moex_updates"
internal const val PUSH_LOG_TAG = "MoexPush"
/** PendingIntent → MainActivity: восстановить карточку «Принять» по данным из уведомления. */
internal const val EXTRA_TAP_RESTORE_VIRTUAL_TRADE = "moex_tap_restore_virtual_trade"
internal const val EXTRA_TAP_SIGNAL_TYPE = "moex_tap_signal_type"
internal const val EXTRA_TAP_Z = "moex_tap_z"
internal const val EXTRA_TAP_TS = "moex_tap_ts"

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

private val sandboxLegNotifyTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Europe/Moscow"))

internal enum class StrategySignalType {
    EnterLong,
    EnterShort,
    ExitLong,
    ExitShort
}

internal data class StrategySignalEvent(
    val timestampMillis: Long,
    val signalType: StrategySignalType,
    val zScore: Double
)

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
    skipDuplicateCheck: Boolean = false
): Boolean {
    val app = context.applicationContext
    fun trace(posted: Boolean, skipReason: String?) {
        appendPushNotificationLogEntry(
            app,
            PushNotificationLogEntry(
                wallTimestampMillis = System.currentTimeMillis(),
                title = title,
                body = body,
                posted = posted,
                skipReason = skipReason,
                virtualTapSignalType = virtualTradeTap?.signalType?.name,
                virtualTapZ = virtualTradeTap?.zScore,
                virtualTapBarTimestampMillis = virtualTradeTap?.timestampMillis
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
            trace(false, PushNotificationLogSkipReason.POST_NOTIFICATIONS_DENIED)
            return false
        }
    }
    if (!skipDuplicateCheck && shouldSkipDuplicatePush(context, title, body)) {
        Log.d(PUSH_LOG_TAG, "Skipping duplicate notification: $title | $body")
        trace(false, PushNotificationLogSkipReason.DUPLICATE_WITHIN_WINDOW)
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
        .setContentText(body)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .build()

    NotificationManagerCompat.from(context).notify(notificationId, notification)
    trace(true, null)
    return true
}

/** Отдельное уведомление по каждой ноге спрэда после PostSandboxOrder (тест песочницы). */
internal fun notifySandboxSpreadLegExecutionResults(
    context: Context,
    legs: List<SandboxLegOrderResult>,
    notionalRub: Double,
    leverage: Double
) {
    val app = context.applicationContext
    legs.forEachIndexed { index, leg ->
        val msk = sandboxLegNotifyTimeFormatter.format(Instant.ofEpochMilli(leg.completedAtMillis))
        val brief = formatPostSandboxOrderBrief(leg.orderJson)
        val body = buildString {
            append(msk)
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
            skipDuplicateCheck = true
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

/** Вызывать при старте/resume Activity: из PendingIntent уведомления восстановить карточку входа. */
internal fun applyVirtualTradeTapIntent(context: Context, intent: Intent?) {
    if (intent == null) return
    if (!intent.getBooleanExtra(EXTRA_TAP_RESTORE_VIRTUAL_TRADE, false)) return
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
            events += StrategySignalEvent(
                timestampMillis = item.optLong("timestampMillis", 0L),
                signalType = type,
                zScore = item.optDouble("zScore", 0.0)
            )
        }
        events += StrategySignalEvent(
            timestampMillis = timestampMillis,
            signalType = signalType,
            zScore = zScore
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
            )
        }
        prefs.edit()
            .putString(PREF_SIGNAL_EVENTS_JSON, output.toString())
            .putLong(PREF_SIGNAL_LAST_WALL_MS, nowWall)
            .putString(PREF_SIGNAL_LAST_TYPE, signalType.name)
            .apply()
    }
    if (savePendingVirtualTradeIfEntry &&
        !TinkoffSandboxStorage.isSandboxEntryAuto(context) &&
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
                        zScore = item.optDouble("zScore", 0.0)
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
    TinkoffSandboxSpreadExecLog.clear(app)
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
