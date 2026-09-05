package com.example.moexmvp.desktop

/** Окно графика (календарные дни до cursor), как период на TradingView. */
internal enum class ReplayChartPeriod(val label: String, val visibleDays: Long) {
    D30("30д", 30L),
    M3("3М", 90L),
    M6("6М", 180L),
    ALL("Всё", 400L),
}

internal data class CandlePoint(
    val label: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
)

internal enum class ChartMarkerShape {
    Circle,
    TriangleUp,
    TriangleDown,
    Diamond,
}

internal data class ChartPointMarker(
    val index: Int,
    val value: Double,
    val colorHex: String,
    val label: String,
    val shape: ChartMarkerShape,
    val badgeText: String? = null,
    val barDateLabel: String? = null,
)

internal data class ChartReferenceLine(
    val value: Double,
    val colorHex: String,
    val label: String,
)

internal data class TradingViewTradeSegment(
    val id: String,
    val entryTimeSec: Long,
    val exitTimeSec: Long?,
    val entryZ: Double,
    val exitZ: Double?,
    val isOpen: Boolean,
)

internal data class DesktopTradeRow(
    val id: String,
    val side: String,
    val entryTime: String,
    val exitTime: String,
    val entryZ: String,
    val exitZ: String,
    val status: String,
)
