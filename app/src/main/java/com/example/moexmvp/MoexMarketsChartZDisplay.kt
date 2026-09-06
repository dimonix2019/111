package com.example.moexmvp

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** MOEX TATN/TATNP: утренняя сессия с 06:45 МСК. */
internal val MOEX_M15_SESSION_OPEN_HOUR = 6
internal val MOEX_M15_SESSION_OPEN_MINUTE = 45

internal fun m15SessionOpenMillis(day: LocalDate, zone: ZoneId = moexZoneId): Long =
    day.atTime(MOEX_M15_SESSION_OPEN_HOUR, MOEX_M15_SESSION_OPEN_MINUTE)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

/** 1D: только текущая торговая сессия (с 06:45), без вчера → нет overnight-дыры на time-axis. */
internal fun filterM15PointsForMarketsOneDay(
    points: List<DataPoint>,
    zone: ZoneId = moexZoneId,
): List<DataPoint> {
    if (points.isEmpty()) return points
    val today = LocalDate.now(zone)
    val todayStart = m15SessionOpenMillis(today, zone)
    val todayBars = points.filter { it.timestampMillis >= todayStart }
    if (todayBars.isNotEmpty()) return ensureAscendingM15Points(todayBars)
    val yesterdayStart = m15SessionOpenMillis(today.minusDays(1), zone)
    return ensureAscendingM15Points(points.filter { it.timestampMillis >= yesterdayStart })
}

/** Сколько 15м слотов прошло с открытия сессии (включая формирующий). */
internal fun m15ExpectedTodayBarsSoFar(
    now: ZonedDateTime = ZonedDateTime.now(moexZoneId),
    zone: ZoneId = moexZoneId,
): Int {
    val sessionStart = java.time.Instant.ofEpochMilli(m15SessionOpenMillis(now.toLocalDate(), zone))
        .atZone(zone)
    if (now.isBefore(sessionStart)) return 0
    val minutes = ChronoUnit.MINUTES.between(sessionStart, now).coerceAtLeast(0)
    return (minutes / 15).toInt() + 1
}

/**
 * Z для графика — rolling по spread из SQLite-базы, без persistedZ (иначе «прямая» вчера).
 */
/** Слияние today-overlay: второй ряд перекрывает совпадающие 15м слоты. */
internal fun mergeTodayM15ChartOverlays(
    primary: List<DataPoint>,
    secondary: List<DataPoint>,
): List<DataPoint> {
    if (primary.isEmpty()) return secondary
    if (secondary.isEmpty()) return primary
    val byLabel = linkedMapOf<String, DataPoint>()
    primary.forEach { byLabel[it.tradeDate] = it }
    secondary.forEach { byLabel[it.tradeDate] = it }
    return ensureAscendingM15Points(byLabel.values.sortedBy { it.timestampMillis })
}

internal fun recalcM15ZForChartDisplayWindow(
    window: List<DataPoint>,
    rollingBase: List<DataPoint>,
): List<DataPoint> {
    if (window.isEmpty()) return window
    if (rollingBase.size < Z_SCORE_ROLLING_MIN_BARS) return window
    val labels = window.map { it.tradeDate }.toSet()
    val byLabel = linkedMapOf<String, DataPoint>()
    rollingBase.forEach { byLabel[it.tradeDate] = it }
    window.forEach { pt -> byLabel[pt.tradeDate] = pt }
    val combined = ensureAscendingM15Points(byLabel.values.toList()).toMutableList()
    applyZScoresDefaultInPlace(combined)
    return combined.filter { it.tradeDate in labels }
}
