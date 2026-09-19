package com.example.moexmvp

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Z для UI «Рынок»: rolling по spread из полного 255д ряда, без persistedZ из SQLite
 * (снимки могут «застыть» на −3.5+ при пересмотре spread MOEX).
 */
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

/**
 * До открытия котировок (до 07:00 МСК) — последний **закрытый** бар (обычно вчера 23:30),
 * не «формирующийся» слот 05:45 с вчерашними ценами.
 */
internal fun lastClosedM15BarForDisplay(
    points: List<DataPoint>,
    now: ZonedDateTime = ZonedDateTime.now(moexZoneId),
    zone: ZoneId = moexZoneId,
): DataPoint? {
    if (points.isEmpty()) return null
    val todayStart = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    val bucketStart = currentM15BucketStartMillis(now.toInstant(), zone)
    if (!isMoexQuotesSessionLikelyOpen(now)) {
        return points.filter { it.timestampMillis < todayStart }.lastOrNull()
            ?: points.filter { it.timestampMillis < bucketStart }.lastOrNull()
    }
    return points.filter { it.timestampMillis < bucketStart }.lastOrNull()
        ?: points.lastOrNull()
}

internal fun indexOfM15Bar(points: List<DataPoint>, bar: DataPoint): Int =
    points.indexOfLast { it.tradeDate == bar.tradeDate && it.timestampMillis == bar.timestampMillis }

/** Rolling-Z на последнем закрытом 15м баре (утро до открытия — вчера 23:30). */
internal fun rollingZForClosedM15Bar(
    points: List<DataPoint>,
    now: ZonedDateTime = ZonedDateTime.now(moexZoneId),
): Double? {
    if (points.size < Z_SCORE_ROLLING_MIN_BARS) return null
    val bar = lastClosedM15BarForDisplay(points, now) ?: return null
    val index = indexOfM15Bar(points, bar)
    if (index < 0) return null
    return rollingZAtBarIndex(points, index)
}

/** Rolling-Z на последнем баре полного 15м ряда (fallback если live 1м недоступен). */
internal fun rollingZForLastM15Bar(points: List<DataPoint>): Double? =
    rollingZForClosedM15Bar(points)

/** Live Z из 1м — только когда котировки реально идут (≥07:00 МСК, бар за сегодня). */
internal fun shouldApplyMarketsLiveZFromIntraday1m(
    snap: MarketsIntraday1mSnapshot,
    now: ZonedDateTime = ZonedDateTime.now(moexZoneId),
    zone: ZoneId = moexZoneId,
): Boolean {
    if (!isMoexQuotesSessionLikelyOpen(now)) return false
    val newest1m = maxOf(snap.tatnLastBarMillis, snap.tatnpLastBarMillis)
    if (newest1m <= 0L) return false
    val barZdt = Instant.ofEpochMilli(newest1m).atZone(zone)
    if (barZdt.toLocalDate().isBefore(now.toLocalDate())) return false
    return true
}
