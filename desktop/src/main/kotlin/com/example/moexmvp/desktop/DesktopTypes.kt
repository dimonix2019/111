package com.example.moexmvp.desktop

import java.time.format.DateTimeFormatter

internal val portfolio15mLabelFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal const val Z_SCORE_ROLLING_MIN_BARS = 48
internal const val STRATEGY_TEST_Z_CHART_VISIBLE_DAYS = 30L
internal const val BAR_REPLAY_BASE_DELAY_MS: Long = 900L

internal val BAR_REPLAY_SPEEDS: List<Float> = listOf(0.5f, 1f, 2f, 5f, 10f)

internal fun barReplayDelayMs(speed: Float): Long =
    (BAR_REPLAY_BASE_DELAY_MS / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(50L)

internal fun formatReplaySpeed(speed: Float): String =
    if (speed < 1f) String.format(java.util.Locale.US, "%.1f×", speed)
    else String.format(java.util.Locale.US, "%.0f×", speed)

internal data class DataPoint(
    val timestampMillis: Long,
    val tradeDate: String,
    val tatnClose: Double,
    val tatnpClose: Double,
    val spreadPercent: Double,
    val diff: Double,
    val zScore: Double,
)

internal data class DynamicThresholds(
    val entry: Double,
    val exit: Double,
    val calculatedDate: String? = null,
)

internal enum class ZStrategyPosition {
    Flat,
    Long,
    Short,
}

internal enum class ZStrategySignal {
    None,
    EnterLong,
    EnterShort,
    ExitLong,
    ExitShort,
}

internal data class ZStrategy15mSignalEdge(
    val signal: ZStrategySignal,
    val bar: DataPoint,
    val positionBefore: ZStrategyPosition,
    val positionAfter: ZStrategyPosition,
)

internal enum class ReplayState {
    Idle,
    Playing,
    Paused,
}

internal data class ReplayConfig(
    val points: List<DataPoint>,
    val thresholds: DynamicThresholds,
    val startIndex: Int = Z_SCORE_ROLLING_MIN_BARS,
)

internal data class ReplayFrame(
    val cursorIndex: Int,
    val visiblePoints: List<DataPoint>,
    val position: ZStrategyPosition,
    val newSignalThisBar: ZStrategy15mSignalEdge?,
    val barLabel: String,
    val signalEdgesSoFar: List<ZStrategy15mSignalEdge> = emptyList(),
)
