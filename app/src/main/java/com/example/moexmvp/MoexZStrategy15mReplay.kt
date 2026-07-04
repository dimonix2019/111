package com.example.moexmvp

internal data class ZStrategy15mSignalEdge(
    val signal: ZStrategySignal,
    val bar: DataPoint,
    val positionBefore: ZStrategyPosition,
    val positionAfter: ZStrategyPosition,
)

internal fun positionAfterZStrategySignal(signal: ZStrategySignal): ZStrategyPosition = when (signal) {
    ZStrategySignal.EnterLong -> ZStrategyPosition.Long
    ZStrategySignal.EnterShort -> ZStrategyPosition.Short
    ZStrategySignal.ExitLong, ZStrategySignal.ExitShort -> ZStrategyPosition.Flat
    ZStrategySignal.None -> error("positionAfterZStrategySignal(None)")
}

/**
 * Собирает все пересечения порога на новых 15м барах с [lastProcessedBarTimestampMillis].
 * При первом запуске (null/0) — только последняя пара баров, без прогона всей истории.
 */
internal fun resolveZStrategyReplayStartIndex(
    points: List<DataPoint>,
    lastProcessedBarTimestampMillis: Long?,
): Int? {
    if (points.size < 2) return null
    if (lastProcessedBarTimestampMillis == null || lastProcessedBarTimestampMillis <= 0L) {
        return points.size - 1
    }
    val idx = points.indexOfFirst { it.timestampMillis > lastProcessedBarTimestampMillis }
    return when {
        idx >= 0 -> maxOf(idx, 1)
        points.last().timestampMillis == lastProcessedBarTimestampMillis -> points.size - 1
        else -> null
    }
}

internal fun collectZStrategy15mSignalEdgesSinceProcessedBar(
    points: List<DataPoint>,
    lastProcessedBarTimestampMillis: Long?,
    initialPosition: ZStrategyPosition,
    thresholds: DynamicThresholds,
): Pair<List<ZStrategy15mSignalEdge>, ZStrategyPosition> {
    val startIndex = resolveZStrategyReplayStartIndex(points, lastProcessedBarTimestampMillis)
        ?: return emptyList<ZStrategy15mSignalEdge>() to initialPosition

    var position = initialPosition
    val edges = mutableListOf<ZStrategy15mSignalEdge>()
    for (index in startIndex until points.size) {
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

/** Индексы баров, обработанных replay с [lastProcessedBarTimestampMillis]. */
internal fun zStrategyReplayBarIndexRange(
    points: List<DataPoint>,
    lastProcessedBarTimestampMillis: Long?,
): IntRange? {
    val startIndex = resolveZStrategyReplayStartIndex(points, lastProcessedBarTimestampMillis)
        ?: return null
    return startIndex until points.size
}

/** Полный replay пересечений Z на закрытых барах (бэктест / «Тест страт.»). */
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

internal fun shouldAdvanceLastProcessed15mBar(
    points: List<DataPoint>,
    lastProcessedBarTimestampMillis: Long?,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
    if (points.isEmpty()) return false
    val lastBarTs = points.last().timestampMillis
    if (isM15BarStillForming(lastBarTs, nowMillis)) return false
    return lastProcessedBarTimestampMillis == null ||
        lastProcessedBarTimestampMillis <= 0L ||
        lastBarTs > lastProcessedBarTimestampMillis
}

internal fun zStrategySignalToStrategySignalType(signal: ZStrategySignal): StrategySignalType = when (signal) {
    ZStrategySignal.EnterLong -> StrategySignalType.EnterLong
    ZStrategySignal.EnterShort -> StrategySignalType.EnterShort
    ZStrategySignal.ExitLong -> StrategySignalType.ExitLong
    ZStrategySignal.ExitShort -> StrategySignalType.ExitShort
    ZStrategySignal.None -> error("zStrategySignalToStrategySignalType(None)")
}
