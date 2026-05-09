package com.example.moexmvp

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

internal data class DataPoint(
    val timestampMillis: Long,
    val tradeDate: String,
    val tatnClose: Double,
    val tatnpClose: Double,
    val spreadPercent: Double,
    val diff: Double,
    val zScore: Double
)

internal data class CandlePoint(
    val label: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

internal data class FetchedData(
    val points: List<DataPoint>,
    val tatnCandles: List<CandlePoint>,
    val tatnpCandles: List<CandlePoint>
)

internal data class ChartSeries(
    val name: String,
    val color: Color,
    val values: List<Double>,
    val lineWidth: Float = 4f
)

internal data class ChartReferenceLine(
    val value: Double,
    val color: Color,
    val label: String,
    val dashOnPx: Float = 10f,
    val dashOffPx: Float = 8f
)

internal data class ChartPointMarker(
    val index: Int,
    val value: Double,
    val color: Color,
    val label: String,
    val shape: ChartMarkerShape
)

internal enum class ChartMarkerShape {
    Circle,
    TriangleUp,
    TriangleDown,
    Diamond
}

internal data class AxisScale(
    val yTicks: List<Double>,
    val xTicks: List<XAxisTick>
)

internal data class ChartStats(
    val min: Double,
    val max: Double
)

internal data class XAxisTick(
    val index: Int,
    val label: String
)

internal sealed interface YAxisScale {
    data object Auto : YAxisScale
    data class Fixed(val min: Double, val max: Double) : YAxisScale
}

internal data class HistoryPage(
    val rows: Map<LocalDate, Double>,
    val total: Int?
)

internal data class CandleBar(
    val timestamp: LocalDateTime,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

internal data class DynamicThresholds(
    val entry: Double,
    val exit: Double,
    val calculatedDate: String?
)

internal data class DynamicThresholdUpdate(
    val thresholds: DynamicThresholds,
    val recalculated: Boolean
)

internal data class BacktestPoint(
    val spread: Double,
    val z: Double
)

internal data class BacktestResult(
    val pnl: Double,
    val trades: Int,
    val entry: Double,
    val exit: Double
)

internal data class DailySignalLimit(
    val date: String,
    val sentCount: Int
)

internal enum class ZStrategyPosition {
    Flat,
    Long,
    Short
}

internal enum class ZStrategySignal {
    None,
    EnterLong,
    EnterShort,
    ExitLong,
    ExitShort
}

internal sealed interface UiState {
    data object Loading : UiState
    data class Error(val message: String) : UiState
    data object Empty : UiState
    data class Success(
        val points: List<DataPoint>,
        val loadedAt: String,
        val tatnCandles: List<CandlePoint>,
        val tatnpCandles: List<CandlePoint>
    ) : UiState
}

internal enum class Period(val label: String, private val months: Long) {
    OneDay("1D", 0),
    OneWeek("1W", 0),
    OneMonth("1M", 1),
    ThreeMonths("3M", 3),
    SixMonths("6M", 6),
    OneYear("1Y", 12);

    fun from(till: LocalDate): LocalDate {
        return when (this) {
            OneDay -> till.minus(1, ChronoUnit.DAYS)
            OneWeek -> till.minus(7, ChronoUnit.DAYS)
            OneYear -> till.minus(365, ChronoUnit.DAYS)
            else -> till.minusMonths(months)
        }
    }
}

internal enum class RealtimeInterval(val label: String, val millis: Long) {
    FiveSeconds("5s", 5_000L),
    TenSeconds("10s", 10_000L),
    FifteenSeconds("15s", 15_000L)
}

internal enum class SpreadScaleMode(val label: String) {
    Auto("Auto"),
    Fixed("Fixed 0..15%")
}

internal enum class MainTab(val label: String) {
    Markets("Рынок"),
    Portfolio("Портфель")
}

internal data class PortfolioClosedTrade(
    val direction: ZStrategyPosition,
    val entryDate: String,
    val exitDate: String,
    val entrySpreadPercent: Double,
    val exitSpreadPercent: Double,
    val pnlSpreadPoints: Double,
    val pnlRubApprox: Double
)

internal data class PortfolioOpenPosition(
    val direction: ZStrategyPosition,
    val entryDate: String,
    val entrySpreadPercent: Double,
    val lastSpreadPercent: Double,
    val unrealizedPnlSpread: Double,
    val unrealizedRubApprox: Double
)

internal data class PortfolioMetrics(
    val periodDescription: String,
    val notionalRub: Double,
    val leverage: Double,
    val commissionPercentPerSide: Double,
    val totalCommissionRub: Double,
    val closedTrades: List<PortfolioClosedTrade>,
    val openPosition: PortfolioOpenPosition?,
    val cumulativeRealizedSpread: Double,
    val cumulativeRealizedRubApprox: Double,
    val unrealizedRubApprox: Double,
    val totalPnlSpread: Double,
    val totalPnlRubApprox: Double,
    val totalReturnPercent: Double,
    val maxDrawdownRubApprox: Double,
    val maxDrawdownPercent: Double,
    val winCount: Int,
    val lossCount: Int,
    val winRate: Double,
    val profitFactor: Double?,
    val avgWinRub: Double,
    val avgLossRub: Double,
    val largestWinRub: Double,
    val largestLossRub: Double
)
