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

private fun collectZStrategy15mSignalEdgesLoop(
    points: List<DataPoint>,
    startIndex: Int,
    endIndexExclusive: Int,
    initialPosition: ZStrategyPosition,
    thresholds: DynamicThresholds,
): Pair<List<ZStrategy15mSignalEdge>, ZStrategyPosition> {
    var position = initialPosition
    val rearm = ZStrategyRearmState()
    val edges = mutableListOf<ZStrategy15mSignalEdge>()
    for (index in startIndex until endIndexExclusive) {
        val prev = points[index - 1]
        val current = points[index]
        val signal = resolveZStrategySignalWithRearm(prev.zScore, current.zScore, position, thresholds, rearm)
        if (signal == ZStrategySignal.None) continue
        val after = positionAfterZStrategySignal(signal)
        edges += ZStrategy15mSignalEdge(
            signal = signal,
            bar = current,
            positionBefore = position,
            positionAfter = after,
        )
        position = after
        when (signal) {
            ZStrategySignal.ExitShort -> rearm.onExitShort()
            ZStrategySignal.ExitLong -> rearm.onExitLong()
            else -> Unit
        }
    }
    return edges to position
}

/**
 * Собирает все пересечения порога на новых 15м барах с [lastProcessedBarTimestampMillis].
 * При первом запуске (null/0) — только последняя пара баров, без прогона всей истории.
 */
internal fun collectZStrategy15mSignalEdgesSinceProcessedBar(
    points: List<DataPoint>,
    lastProcessedBarTimestampMillis: Long?,
    initialPosition: ZStrategyPosition,
    thresholds: DynamicThresholds,
): Pair<List<ZStrategy15mSignalEdge>, ZStrategyPosition> {
    if (points.size < 2) return emptyList<ZStrategy15mSignalEdge>() to initialPosition

    val startIndex = when {
        lastProcessedBarTimestampMillis == null || lastProcessedBarTimestampMillis <= 0L ->
            points.size - 1
        else -> {
            val idx = points.indexOfFirst { it.timestampMillis > lastProcessedBarTimestampMillis }
            if (idx < 0) return emptyList<ZStrategy15mSignalEdge>() to initialPosition
            maxOf(idx, 1)
        }
    }

    var position = initialPosition
    return collectZStrategy15mSignalEdgesLoop(
        points = points,
        startIndex = startIndex,
        endIndexExclusive = points.size,
        initialPosition = position,
        thresholds = thresholds,
    )
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
    return collectZStrategy15mSignalEdgesLoop(
        points = points,
        startIndex = start,
        endIndexExclusive = points.size,
        initialPosition = initialPosition,
        thresholds = thresholds,
    )
}

internal fun shouldAdvanceLastProcessed15mBar(
    points: List<DataPoint>,
    lastProcessedBarTimestampMillis: Long?,
): Boolean {
    if (points.isEmpty()) return false
    val lastBarTs = points.last().timestampMillis
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
