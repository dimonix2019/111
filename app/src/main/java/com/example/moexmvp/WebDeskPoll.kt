package com.example.moexmvp

import android.content.Context

internal const val WEB_DESK_POLL_MS = 35_000L
private const val WEB_DESK_PUSH_BASE_ID = 42_100

/** Whether a live_events row should produce a phone push. */
internal fun webDeskEventShouldNotify(event: WebDeskEvent): Boolean {
    val lvl = event.level.lowercase()
    if (lvl == "signal" || lvl == "error") return true
    val m = event.message
    if (m.startsWith("AUTO", ignoreCase = true)) return true
    if (m.contains("AUTO fail", ignoreCase = true)) return true
    if (m.contains("AUTO вход", ignoreCase = true) || m.contains("AUTO выход", ignoreCase = true)) {
        return true
    }
    // "AUTO вход LONG · …" logged as "{source} вход"
    if (m.startsWith("AUTO ", ignoreCase = true)) return true
    return false
}

/**
 * Poll strategy-web /api/live/status; push on new signal/AUTO events and open-trade changes.
 * Seeds last event id on first run to avoid flooding.
 */
internal suspend fun pollWebDeskAndNotify(context: Context) {
    val app = context.applicationContext
    if (!WebDeskPrefs.isMonitorEnabled(app)) return
    if (WebDeskPrefs.normalizedBaseUrl(app) == null) return

    val snap = WebDeskApi.fetchStatus(app).getOrElse {
        MoexDiagnostics.log(app, "web_desk", "poll fail: ${it.message}")
        return
    }

    val lastId = WebDeskPrefs.lastEventId(app)
    val newestId = snap.events.maxOfOrNull { it.id } ?: 0L
    if (lastId <= 0L) {
        // First poll after enable: seed cursor, no spam.
        if (newestId > 0L) WebDeskPrefs.setLastEventId(app, newestId)
        syncOpenTradeStateSilent(app, snap)
        return
    }

    val fresh = snap.events
        .filter { it.id > lastId }
        .sortedBy { it.id }
    for (ev in fresh) {
        if (!webDeskEventShouldNotify(ev)) continue
        val title = when (ev.level.lowercase()) {
            "signal" -> "Web: сигнал"
            "error" -> "Web: ошибка"
            else -> "Web desk"
        }
        showPushNotification(
            app,
            title = title,
            body = ev.message.take(240),
            notificationId = WEB_DESK_PUSH_BASE_ID + (ev.id % 800).toInt(),
            skipDuplicateCheck = true,
            correlationTag = "web_desk_ev_${ev.id}",
        )
    }
    if (newestId > lastId) {
        WebDeskPrefs.setLastEventId(app, newestId)
    }

    notifyOpenTradeChange(app, snap)
}

private fun syncOpenTradeStateSilent(app: Context, snap: WebDeskStatusSnapshot) {
    val openId = snap.openId
    if (openId != null) {
        WebDeskPrefs.setHadOpen(app, true)
        WebDeskPrefs.setLastOpenId(app, openId)
    } else {
        WebDeskPrefs.setHadOpen(app, false)
        WebDeskPrefs.setLastOpenId(app, 0L)
    }
}

private fun notifyOpenTradeChange(app: Context, snap: WebDeskStatusSnapshot) {
    val hadOpen = WebDeskPrefs.hadOpen(app)
    val lastOpenId = WebDeskPrefs.lastOpenId(app)
    val openId = snap.openId
    when {
        openId != null && (!hadOpen || openId != lastOpenId) -> {
            val dir = snap.openDirection ?: "?"
            val t = snap.openEntryTime ?: ""
            showPushNotification(
                app,
                title = "Web: открыта позиция",
                body = "$dir · $t".trim(),
                notificationId = WEB_DESK_PUSH_BASE_ID + 900,
                skipDuplicateCheck = true,
                correlationTag = "web_desk_open_$openId",
            )
            WebDeskPrefs.setHadOpen(app, true)
            WebDeskPrefs.setLastOpenId(app, openId)
        }
        openId == null && hadOpen -> {
            showPushNotification(
                app,
                title = "Web: позиция закрыта",
                body = "На web desk нет открытой сделки",
                notificationId = WEB_DESK_PUSH_BASE_ID + 901,
                skipDuplicateCheck = true,
                correlationTag = "web_desk_close_$lastOpenId",
            )
            WebDeskPrefs.setHadOpen(app, false)
            WebDeskPrefs.setLastOpenId(app, 0L)
        }
    }
}
