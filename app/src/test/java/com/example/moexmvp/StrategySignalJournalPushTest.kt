package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategySignalJournalPushTest {

    @Test
    fun strategySignalPushBody_matchesEnterLongFormat() {
        val body = strategySignalPushBody(
            StrategySignalType.EnterLong,
            zScore = -1.25,
            entryThreshold = 0.8,
            exitThreshold = 0.7,
        )
        assertEquals("Z <= -0.8 (текущий Z=-1.25)", body)
    }

    @Test
    fun buildStrategySignalJournalPushView_usesPushLogWhenMatched() {
        val barTs = java.time.LocalDate.of(2026, 6, 1)
            .atTime(10, 15)
            .atZone(java.time.ZoneId.of("Europe/Moscow"))
            .toInstant()
            .toEpochMilli()
        val received = barTs + 5_000L
        val event = StrategySignalEvent(
            timestampMillis = barTs,
            signalType = StrategySignalType.EnterLong,
            zScore = -1.1,
            receivedAtMillis = received,
        )
        val push = PushNotificationLogEntry(
            wallTimestampMillis = received,
            title = "Вход: LONG TATN / SHORT TATNP",
            body = formatMessageReceivedLine(received) + "\nZ <= -0.8 (текущий Z=-1.10)",
            posted = true,
            skipReason = null,
            virtualTapSignalType = "EnterLong",
            virtualTapZ = -1.1,
            virtualTapBarTimestampMillis = barTs,
            notificationId = 42_001,
            correlationTag = null,
        )
        val view = buildStrategySignalJournalPushView(
            event = event,
            pushLog = listOf(push),
            entryThreshold = 0.8,
            exitThreshold = 0.7,
            allJournalEvents = listOf(event),
        )
        assertEquals("1 long 2026-06-01 10:15", view.signalId)
        assertEquals("42001", view.pushIdText)
        assertEquals("Вход: LONG TATN / SHORT TATNP", view.title)
        assertTrue(view.messageBody.contains("текущий Z=-1.10"))
        assertEquals("Показано в шторке", view.pushStatusText)
    }
}
