package com.example.moexmvp.desktop

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val moexZoneId: ZoneId = ZoneId.of("Europe/Moscow")
private val tradeDateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

internal fun positionAfterZStrategySignal(signal: ZStrategySignal): ZStrategyPosition = when (signal) {
    ZStrategySignal.EnterLong -> ZStrategyPosition.Long
    ZStrategySignal.EnterShort -> ZStrategyPosition.Short
    ZStrategySignal.ExitLong, ZStrategySignal.ExitShort -> ZStrategyPosition.Flat
    ZStrategySignal.None -> error("positionAfterZStrategySignal(None)")
}

internal fun determineZStrategySignal(
    previousZ: Double?,
    currentZ: Double,
    position: ZStrategyPosition,
    thresholds: DynamicThresholds,
): ZStrategySignal {
    val prev = previousZ ?: return ZStrategySignal.None
    return when (position) {
        ZStrategyPosition.Flat -> when {
            prev > -thresholds.entry && currentZ <= -thresholds.entry -> ZStrategySignal.EnterLong
            prev < thresholds.entry && currentZ >= thresholds.entry -> ZStrategySignal.EnterShort
            else -> ZStrategySignal.None
        }
        ZStrategyPosition.Long -> if (prev < -thresholds.exit && currentZ >= -thresholds.exit) {
            ZStrategySignal.ExitLong
        } else {
            ZStrategySignal.None
        }
        ZStrategyPosition.Short -> if (prev > thresholds.exit && currentZ <= thresholds.exit) {
            ZStrategySignal.ExitShort
        } else {
            ZStrategySignal.None
        }
    }
}

internal fun barMillisAt(point: DataPoint): Long {
    if (point.timestampMillis > 0L) return point.timestampMillis
    return parseTradeDateMillis(point.tradeDate)
}

private fun parseTradeDateMillis(tradeDate: String): Long {
    val s = tradeDate.trim()
    runCatching {
        LocalDateTime.parse(s, portfolio15mLabelFormatter)
            .atZone(moexZoneId)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()?.let { return it }
    runCatching {
        LocalDate.parse(s.take(10), tradeDateFormatter)
            .atStartOfDay(moexZoneId)
            .toInstant()
            .toEpochMilli()
    }.getOrNull()?.let { return it }
    return 0L
}

internal fun isConsecutiveM15Bar(previous: DataPoint, current: DataPoint): Boolean {
    val prevMs = barMillisAt(previous)
    val curMs = barMillisAt(current)
    if (prevMs <= 0L || curMs <= 0L) return false
    val prev = Instant.ofEpochMilli(prevMs).atZone(moexZoneId).toLocalDateTime()
    val cur = Instant.ofEpochMilli(curMs).atZone(moexZoneId).toLocalDateTime()
    return ChronoUnit.MINUTES.between(prev, cur) == 15L
}

internal fun determineZStrategySignalBetweenBars(
    previous: DataPoint,
    current: DataPoint,
    position: ZStrategyPosition,
    thresholds: DynamicThresholds,
): ZStrategySignal {
    if (!isConsecutiveM15Bar(previous, current)) return ZStrategySignal.None
    return determineZStrategySignal(previous.zScore, current.zScore, position, thresholds)
}

internal fun collectZStrategy15mSignalEdgesFull(
    points: List<DataPoint>,
    initialPosition: ZStrategyPosition = ZStrategyPosition.Flat,
    thresholds: DynamicThresholds,
    loopStartIndex: Int = 1,
): Pair<List<ZStrategy15mSignalEdge>, ZStrategyPosition> {
    if (points.size < 2) return emptyList<ZStrategy15mSignalEdge>() to initialPosition
    val start = loopStartIndex.coerceIn(1, points.lastIndex)
    var position = initialPosition
    val edges = mutableListOf<ZStrategy15mSignalEdge>()
    for (index in start until points.size) {
        val prev = points[index - 1]
        val current = points[index]
        val signal = determineZStrategySignalBetweenBars(prev, current, position, thresholds)
        if (signal != ZStrategySignal.None) {
            val after = positionAfterZStrategySignal(signal)
            edges += ZStrategy15mSignalEdge(
                signal = signal,
                bar = current,
                positionBefore = position,
                positionAfter = after,
            )
            position = after
        }
    }
    return edges to position
}

internal fun barReplayVisibleIndexRange(
    points: List<DataPoint>,
    cursorIndex: Int,
    visibleDays: Long = STRATEGY_TEST_Z_CHART_VISIBLE_DAYS,
): IntRange {
    if (points.isEmpty()) return IntRange.EMPTY
    val cursor = cursorIndex.coerceIn(0, points.lastIndex)
    val till = points[cursor].timestampMillis
    val from = Instant.ofEpochMilli(till)
        .atZone(moexZoneId)
        .toLocalDate()
        .minusDays(visibleDays)
        .atStartOfDay(moexZoneId)
        .toInstant()
        .toEpochMilli()
    val start = points.indexOfFirst { it.timestampMillis >= from }.coerceAtLeast(0)
    return start..cursor
}
