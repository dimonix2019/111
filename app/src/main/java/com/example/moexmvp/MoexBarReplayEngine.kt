package com.example.moexmvp

import java.time.Instant

/** Состояние авто-проигрывания Bar Replay (TradingView-style). */
internal enum class ReplayState {
    Idle,
    Playing,
    Paused,
}

/**
 * Конфиг replay на уже загруженном 15м ряде «Тест страт.» —
 * без второй загрузки MOEX и без тиков.
 */
internal data class ReplayConfig(
    val points: List<DataPoint>,
    val thresholds: DynamicThresholds,
    val notionalRub: Double = 0.0,
    val leverage: Double = 1.0,
    val commissionPercentPerSide: Double = 0.0,
    val startIndex: Int = Z_SCORE_ROLLING_MIN_BARS,
)

/**
 * Кадр на текущем cursor: видимый префикс ряда + позиция/сигнал (R2).
 * [closedTradesSoFar] / [openPnlRub] заполняются с R3; в R1–R2 обычно пусто/null.
 */
internal data class ReplayFrame(
    val cursorIndex: Int,
    val visiblePoints: List<DataPoint>,
    val position: ZStrategyPosition,
    val openPnlRub: Double?,
    val closedTradesSoFar: List<PortfolioClosedTrade>,
    val newSignalThisBar: ZStrategy15mSignalEdge?,
    val barLabel: String,
    val signalEdgesSoFar: List<ZStrategy15mSignalEdge> = emptyList(),
)

/** Скорости авто-шага (1× ≈ 900 ms на бар). */
internal val BAR_REPLAY_SPEEDS: List<Float> = listOf(0.5f, 1f, 2f, 5f, 10f)

internal const val BAR_REPLAY_BASE_DELAY_MS: Long = 900L

internal fun barReplayDelayMs(speed: Float): Long =
    (BAR_REPLAY_BASE_DELAY_MS / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(50L)

/**
 * Индексы баров для окна ~[visibleDays] календарных дней, заканчивающегося на [cursorIndex].
 * Снижает нагрузку WebView на 8 ГБ RAM (не весь 255д ряд).
 */
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

/**
 * Движок Bar Replay: cursor по 15м барам, Idle/Playing/Paused,
 * инкрементальные пересечения Z (parity с [collectZStrategy15mSignalEdgesFull]).
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

    fun togglePlayPause() {
        when (state) {
            ReplayState.Playing -> pause()
            ReplayState.Idle, ReplayState.Paused -> play()
        }
    }

    /** +1 бар; null если конец ряда (state → Idle). */
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

    /** −1 бар с пересчётом позиции с начала (пауза). */
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
                openPnlRub = null,
                closedTradesSoFar = emptyList(),
                newSignalThisBar = null,
                barLabel = "",
                signalEdgesSoFar = emptyList(),
            )
        }
        val idx = cursor.coerceIn(0, points.lastIndex)
        val bar = points[idx]
        return ReplayFrame(
            cursorIndex = idx,
            visiblePoints = points.subList(0, idx + 1),
            position = position,
            openPnlRub = null,
            closedTradesSoFar = emptyList(),
            newSignalThisBar = newSignal,
            barLabel = bar.tradeDate,
            signalEdgesSoFar = edges.toList(),
        )
    }
}

/** Маркеры сигналов, уже «случившиеся» к cursor (для TradingView / line chart). */
internal fun barReplaySignalMarkers(
    edges: List<ZStrategy15mSignalEdge>,
    points: List<DataPoint>,
): List<ChartPointMarker> {
    if (edges.isEmpty() || points.isEmpty()) return emptyList()
    val out = ArrayList<ChartPointMarker>(edges.size)
    var tradeNo = 0
    for (edge in edges) {
        val idx = points.indexOfFirst { it.timestampMillis == edge.bar.timestampMillis }
            .takeIf { it >= 0 }
            ?: continue
        when (edge.signal) {
            ZStrategySignal.EnterLong -> {
                tradeNo += 1
                out += ChartPointMarker(
                    index = idx,
                    value = edge.bar.zScore,
                    color = androidx.compose.ui.graphics.Color(0xFF69F0AE),
                    label = "Вх Long",
                    shape = ChartMarkerShape.TriangleUp,
                    badgeText = "${tradeNo}А",
                    barDateLabel = edge.bar.tradeDate,
                )
            }
            ZStrategySignal.EnterShort -> {
                tradeNo += 1
                out += ChartPointMarker(
                    index = idx,
                    value = edge.bar.zScore,
                    color = androidx.compose.ui.graphics.Color(0xFFFF5252),
                    label = "Вх Short",
                    shape = ChartMarkerShape.TriangleDown,
                    badgeText = "${tradeNo}А",
                    barDateLabel = edge.bar.tradeDate,
                )
            }
            ZStrategySignal.ExitLong -> {
                out += ChartPointMarker(
                    index = idx,
                    value = edge.bar.zScore,
                    color = androidx.compose.ui.graphics.Color(0xFF80CBC4),
                    label = "Вых Long",
                    shape = ChartMarkerShape.Diamond,
                    badgeText = "${tradeNo}Р",
                    barDateLabel = edge.bar.tradeDate,
                )
            }
            ZStrategySignal.ExitShort -> {
                out += ChartPointMarker(
                    index = idx,
                    value = edge.bar.zScore,
                    color = androidx.compose.ui.graphics.Color(0xFFFFAB91),
                    label = "Вых Short",
                    shape = ChartMarkerShape.Diamond,
                    badgeText = "${tradeNo}Р",
                    barDateLabel = edge.bar.tradeDate,
                )
            }
            ZStrategySignal.None -> Unit
        }
    }
    return out
}

/** Свечи Z для окна replay (~30д до cursor), индексы относительно [points]. */
internal fun barReplayWindowCandles(
    points: List<DataPoint>,
    cursorIndex: Int,
    visibleDays: Long = STRATEGY_TEST_Z_CHART_VISIBLE_DAYS,
): List<CandlePoint> {
    val range = barReplayVisibleIndexRange(points, cursorIndex, visibleDays)
    if (range.isEmpty()) return emptyList()
    return buildZScoreCandlesFromM15Points(points.subList(range.first, range.last + 1))
}
