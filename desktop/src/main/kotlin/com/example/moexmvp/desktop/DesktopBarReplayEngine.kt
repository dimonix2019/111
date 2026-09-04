package com.example.moexmvp.desktop

/**
 * Bar Replay engine (parity с Android [com.example.moexmvp.BarReplayEngine]).
 */
internal class BarReplayEngine(
    private val config: ReplayConfig,
) {
    private val points: List<DataPoint> = config.points
    private val minCursor: Int =
        config.startIndex.coerceIn(0, (points.size - 1).coerceAtLeast(0))

    var cursor: Int = minCursor
        private set

    var state: ReplayState = ReplayState.Idle
        private set

    var speed: Float = 1f

    private var position: ZStrategyPosition = ZStrategyPosition.Flat
    private val edges = mutableListOf<ZStrategy15mSignalEdge>()

    init {
        rebuildStateToCursor(minCursor)
    }

    fun play() {
        if (points.size < 2) return
        if (cursor >= points.lastIndex) {
            seekTo(minCursor)
        }
        state = ReplayState.Playing
    }

    fun pause() {
        if (state == ReplayState.Playing) {
            state = ReplayState.Paused
        }
    }

    fun stop() {
        state = ReplayState.Idle
        seekTo(minCursor)
    }

    fun stepForward(): ReplayFrame? {
        if (points.size < 2) return null
        if (cursor >= points.lastIndex) {
            state = ReplayState.Idle
            return null
        }
        val next = cursor + 1
        val signal = advanceOneBar(next)
        cursor = next
        return frameAtCursor(newSignal = signal)
    }

    fun stepBackward(): ReplayFrame {
        if (state == ReplayState.Playing) {
            state = ReplayState.Paused
        }
        val target = (cursor - 1).coerceAtLeast(minCursor)
        return seekTo(target)
    }

    fun seekToStart(): ReplayFrame = seekTo(minCursor)

    fun seekToEnd(): ReplayFrame {
        if (points.isEmpty()) return frameAtCursor(null)
        return seekTo(points.lastIndex)
    }

    fun seekTo(index: Int): ReplayFrame {
        if (points.isEmpty()) {
            cursor = 0
            position = ZStrategyPosition.Flat
            edges.clear()
            return frameAtCursor(null)
        }
        val target = index.coerceIn(minCursor, points.lastIndex)
        rebuildStateToCursor(target)
        cursor = target
        if (state == ReplayState.Playing && cursor >= points.lastIndex) {
            state = ReplayState.Idle
        }
        return frameAtCursor(newSignal = null)
    }

    fun frameAtCursor(): ReplayFrame = frameAtCursor(newSignal = null)

    val lastIndex: Int get() = points.lastIndex.coerceAtLeast(0)

    val progressFraction: Float
        get() {
            val span = (lastIndex - minCursor).coerceAtLeast(1)
            return ((cursor - minCursor).toFloat() / span).coerceIn(0f, 1f)
        }

    private fun rebuildStateToCursor(target: Int) {
        position = ZStrategyPosition.Flat
        edges.clear()
        if (points.size < 2 || target < 1) return
        for (index in 1..target) {
            advanceOneBar(index)
        }
    }

    private fun advanceOneBar(index: Int): ZStrategy15mSignalEdge? {
        if (index < 1 || index >= points.size) return null
        val prev = points[index - 1]
        val current = points[index]
        val signal = determineZStrategySignalBetweenBars(
            prev,
            current,
            position,
            config.thresholds,
        )
        if (signal == ZStrategySignal.None) return null
        val after = positionAfterZStrategySignal(signal)
        val edge = ZStrategy15mSignalEdge(
            signal = signal,
            bar = current,
            positionBefore = position,
            positionAfter = after,
        )
        position = after
        edges += edge
        return edge
    }

    private fun frameAtCursor(newSignal: ZStrategy15mSignalEdge?): ReplayFrame {
        if (points.isEmpty()) {
            return ReplayFrame(
                cursorIndex = 0,
                visiblePoints = emptyList(),
                position = ZStrategyPosition.Flat,
                newSignalThisBar = null,
                barLabel = "",
            )
        }
        val idx = cursor.coerceIn(0, points.lastIndex)
        val bar = points[idx]
        return ReplayFrame(
            cursorIndex = idx,
            visiblePoints = points.subList(0, idx + 1),
            position = position,
            newSignalThisBar = newSignal,
            barLabel = bar.tradeDate,
            signalEdgesSoFar = edges.toList(),
        )
    }
}
