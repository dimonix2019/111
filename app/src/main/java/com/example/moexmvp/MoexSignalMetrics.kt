package com.example.moexmvp

import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate

/**
 * Rough same-day spread PnL (in %-points) from paired enter/exit events vs nearest chart bar.
 * Only counts exits whose **local** calendar day is [today] (Europe/Moscow).
 */
internal fun estimateTodaySpreadPnlFromEvents(
    events: List<StrategySignalEvent>,
    points: List<DataPoint>,
    today: LocalDate = LocalDate.now(ZoneId.of("Europe/Moscow"))
): Double? {
    if (events.isEmpty() || points.isEmpty()) return null
    fun spreadAt(ts: Long): Double? {
        val idx = points.indices.minByOrNull { kotlin.math.abs(points[it].timestampMillis - ts) } ?: return null
        if (kotlin.math.abs(points[idx].timestampMillis - ts) > 6 * 60 * 60 * 1000L) return null
        return points[idx].spreadPercent
    }
    var pos: ZStrategyPosition = ZStrategyPosition.Flat
    var entrySpread: Double? = null
    var sum = 0.0
    var used = false
    for (ev in events.sortedBy { it.timestampMillis }) {
        val day = Instant.ofEpochMilli(ev.timestampMillis).atZone(ZoneId.of("Europe/Moscow")).toLocalDate()
        when (ev.signalType) {
            StrategySignalType.EnterLong -> {
                pos = ZStrategyPosition.Long
                entrySpread = spreadAt(ev.timestampMillis)
            }
            StrategySignalType.EnterShort -> {
                pos = ZStrategyPosition.Short
                entrySpread = spreadAt(ev.timestampMillis)
            }
            StrategySignalType.ExitLong -> {
                val es = entrySpread
                if (pos == ZStrategyPosition.Long && es != null) {
                    val ex = spreadAt(ev.timestampMillis)
                    if (ex != null && day == today) {
                        sum += ex - es
                        used = true
                    }
                }
                pos = ZStrategyPosition.Flat
                entrySpread = null
            }
            StrategySignalType.ExitShort -> {
                val es = entrySpread
                if (pos == ZStrategyPosition.Short && es != null) {
                    val ex = spreadAt(ev.timestampMillis)
                    if (ex != null && day == today) {
                        sum += es - ex
                        used = true
                    }
                }
                pos = ZStrategyPosition.Flat
                entrySpread = null
            }
        }
    }
    return if (used) sum else null
}
