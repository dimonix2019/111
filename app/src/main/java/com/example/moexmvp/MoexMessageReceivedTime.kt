package com.example.moexmvp

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val messageReceivedAtFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Europe/Moscow"))

private const val RECEIVED_PREFIX = "Получено: "

/** Время получения сообщения (МСК), для push, журнала и карточки «Принять». */
internal fun formatMessageReceivedAtMsk(receivedAtMillis: Long): String =
    messageReceivedAtFormatter.format(Instant.ofEpochMilli(receivedAtMillis))

/** Первая строка тела уведомления / сообщения — когда оно получено приложением. */
internal fun formatMessageReceivedLine(receivedAtMillis: Long): String =
    "$RECEIVED_PREFIX${formatMessageReceivedAtMsk(receivedAtMillis)} (МСК)"

internal fun messageBodyHasReceivedTimeLine(body: String): Boolean =
    body.lineSequence().any { line ->
        line.startsWith(RECEIVED_PREFIX) || line.startsWith("Получено ")
    }

/** Добавляет строку «Получено: …» в начало, если её ещё нет (идемпотентно). */
internal fun ensureMessageBodyHasReceivedTime(body: String, receivedAtMillis: Long): String {
    if (messageBodyHasReceivedTimeLine(body)) return body
    return formatMessageReceivedLine(receivedAtMillis) + "\n" + body.trimStart()
}
