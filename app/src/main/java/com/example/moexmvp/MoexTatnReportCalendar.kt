package com.example.moexmvp

import java.time.LocalDate

/**
 * Календарные дни публикации отчётности ПАО «Татнефть» (IFRS, ориентиры по датам раскрытия).
 * Для бэктеста: блокируем новые входы в день отчёта и накануне; опционально закрываем открытые ноги.
 */
internal val TATN_REPORT_PUBLICATION_DAYS_MSK: Set<LocalDate> = setOf(
    LocalDate.of(2025, 5, 28),
    LocalDate.of(2025, 7, 31),
    LocalDate.of(2025, 8, 29),
    LocalDate.of(2025, 11, 28),
    LocalDate.of(2026, 4, 28),
    LocalDate.of(2026, 5, 15)
)

/** День отчёта и предыдущий торговый день — без новых входов. */
internal fun isTatnReportBlackoutDay(day: LocalDate): Boolean {
    if (day in TATN_REPORT_PUBLICATION_DAYS_MSK) return true
    return day.plusDays(1) in TATN_REPORT_PUBLICATION_DAYS_MSK
}

internal fun isTatnReportPublicationDay(day: LocalDate): Boolean =
    day in TATN_REPORT_PUBLICATION_DAYS_MSK
