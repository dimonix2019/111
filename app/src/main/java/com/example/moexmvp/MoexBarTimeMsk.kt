package com.example.moexmvp

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal val moexMskZone: ZoneId = ZoneId.of("Europe/Moscow")

/** Основная сессия MOEX TQBR: закрываем открытые ноги до вечернего клиринга (~18:50). */
internal val MOEX_EVENING_CLEARING_CUTOFF_MSK: LocalTime = LocalTime.of(18, 45)

internal fun parseBarLocalDateMsk(tradeDateLabel: String): LocalDate? {
    val trimmed = tradeDateLabel.trim()
    if (trimmed.length >= 10) {
        runCatching { return LocalDate.parse(trimmed.take(10)) }.getOrNull()
    }
    return runCatching {
        LocalDateTime.parse(trimmed, portfolio15mLabelFormatter).toLocalDate()
    }.getOrNull()
}

internal fun parseBarLocalTimeMsk(tradeDateLabel: String): LocalTime? {
    val trimmed = tradeDateLabel.trim()
    if (trimmed.length >= 16) {
        runCatching {
            return LocalDateTime.parse(trimmed.take(16), portfolio15mLabelFormatter).toLocalTime()
        }.getOrNull()
    }
    return null
}

internal fun isAtOrAfterMskCutoff(tradeDateLabel: String, cutoff: LocalTime): Boolean {
    val time = parseBarLocalTimeMsk(tradeDateLabel) ?: return false
    return !time.isBefore(cutoff)
}
