package com.example.moexmvp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "moex_notification_log"
private const val PREF_JSON = "notification_log_json"

/** Максимум записей; старые отбрасываются (FIFO по времени записи). */
internal const val MAX_PUSH_NOTIFICATION_LOG_ENTRIES = 2000

internal object PushNotificationLogSkipReason {
    const val POST_NOTIFICATIONS_DENIED = "POST_NOTIFICATIONS_DENIED"
    const val DUPLICATE_WITHIN_WINDOW = "DUPLICATE_WITHIN_WINDOW"
}

/**
 * Локальный журнал попыток показа уведомлений (все вызовы [showPushNotification]).
 * Для тестирования: сравнение с журналом сигналов, воспроизведение сценариев.
 */
internal data class PushNotificationLogEntry(
    val wallTimestampMillis: Long,
    val title: String,
    val body: String,
    /** true — уведомление реально отправлено в NotificationManager. */
    val posted: Boolean,
    /** null если posted; иначе код из [PushNotificationLogSkipReason]. */
    val skipReason: String?,
    val virtualTapSignalType: String?,
    val virtualTapZ: Double?,
    val virtualTapBarTimestampMillis: Long?,
    /** Android notification id (только при posted). */
    val notificationId: Int? = null,
    /** Связка с исполнением спрэда на демо (см. [spreadLegPushCorrelationTag]). */
    val correlationTag: String? = null
)

private val pushNotificationLogLock = Any()

internal fun appendPushNotificationLogEntry(context: Context, entry: PushNotificationLogEntry) {
    val app = context.applicationContext
    synchronized(pushNotificationLogLock) {
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_JSON, null).orEmpty()
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list += it }
        }
        list += entryToJson(entry)
        val trimmed = if (list.size <= MAX_PUSH_NOTIFICATION_LOG_ENTRIES) {
            list
        } else {
            list.takeLast(MAX_PUSH_NOTIFICATION_LOG_ENTRIES)
        }
        val out = JSONArray()
        trimmed.forEach { out.put(it) }
        prefs.edit().putString(PREF_JSON, out.toString()).apply()
    }
}

internal fun loadPushNotificationLog(context: Context): List<PushNotificationLogEntry> {
    synchronized(pushNotificationLogLock) {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_JSON, null)
            .orEmpty()
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                entryFromJson(o)?.let { add(it) }
            }
        }
    }
}

internal fun clearPushNotificationLog(context: Context) {
    synchronized(pushNotificationLogLock) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_JSON)
            .apply()
    }
}

private fun entryToJson(e: PushNotificationLogEntry): JSONObject =
    JSONObject().apply {
        put("wallTimestampMillis", e.wallTimestampMillis)
        put("title", e.title)
        put("body", e.body)
        put("posted", e.posted)
        e.skipReason?.let { put("skipReason", it) }
        e.virtualTapSignalType?.let { put("virtualTapSignalType", it) }
        e.virtualTapZ?.let { put("virtualTapZ", it) }
        e.virtualTapBarTimestampMillis?.let { put("virtualTapBarTimestampMillis", it) }
        e.notificationId?.let { put("notificationId", it) }
        e.correlationTag?.let { put("correlationTag", it) }
    }

private fun entryFromJson(o: JSONObject): PushNotificationLogEntry? =
    try {
        PushNotificationLogEntry(
            wallTimestampMillis = o.optLong("wallTimestampMillis", 0L),
            title = o.optString("title"),
            body = o.optString("body"),
            posted = o.optBoolean("posted", false),
            skipReason = o.optString("skipReason", "").takeIf { it.isNotEmpty() },
            virtualTapSignalType = o.optString("virtualTapSignalType", "").takeIf { it.isNotEmpty() },
            virtualTapZ = if (o.has("virtualTapZ")) o.optDouble("virtualTapZ", 0.0) else null,
            virtualTapBarTimestampMillis = if (o.has("virtualTapBarTimestampMillis")) {
                o.optLong("virtualTapBarTimestampMillis", 0L)
            } else {
                null
            },
            notificationId = if (o.has("notificationId")) o.optInt("notificationId", 0) else null,
            correlationTag = o.optString("correlationTag", "").takeIf { it.isNotEmpty() }
        )
    } catch (_: Exception) {
        null
    }

/** Для юнит-тестов сериализации. */
internal fun pushNotificationLogEntrySerializationRoundTrip(e: PushNotificationLogEntry): PushNotificationLogEntry? =
    entryFromJson(entryToJson(e))
