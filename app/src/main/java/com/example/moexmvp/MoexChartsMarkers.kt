package com.example.moexmvp

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun buildZScoreReferenceLines(
    thresholds: DynamicThresholds,
    desktopStyle: Boolean = false,
): List<ChartReferenceLine> {
    val entryColor = if (desktopStyle) DesktopChartColors.entryThreshold else Color(0xFFFFB74D)
    val exitColor = if (desktopStyle) DesktopChartColors.exitThreshold else Color(0xFFA5D6A7)
    return listOf(
        ChartReferenceLine(
            value = thresholds.entry,
            color = entryColor,
            label = String.format(Locale.US, "+%.2f", thresholds.entry)
        ),
        ChartReferenceLine(
            value = -thresholds.entry,
            color = entryColor,
            label = String.format(Locale.US, "-%.2f", thresholds.entry)
        ),
        ChartReferenceLine(
            value = thresholds.exit,
            color = exitColor,
            label = String.format(Locale.US, "+%.2f", thresholds.exit)
        ),
        ChartReferenceLine(
            value = -thresholds.exit,
            color = exitColor,
            label = String.format(Locale.US, "-%.2f", thresholds.exit)
        )
    )
}

internal fun buildZScoreSignalMarkersFromEvents(
    points: List<DataPoint>,
    events: List<StrategySignalEvent>
): List<ChartPointMarker> {
    if (points.isEmpty()) return emptyList()
    val rangeStart = points.first().timestampMillis
    val rangeEnd = points.last().timestampMillis
    val bucketMs = 15 * 60 * 1000L
    /** Allow snapping across small clock skew (chart vs 15m cache both use Moscow). */
    val maxSnapMs = bucketMs * 2
    /** Events slightly after the last bar (e.g. confirm tap) still map to the last 15m bar. */
    val edgeGraceMs = 48 * 60 * 60 * 1000L
    return events
        .asSequence()
        .filter { it.timestampMillis in (rangeStart - bucketMs - edgeGraceMs)..(rangeEnd + bucketMs + edgeGraceMs) }
        .distinctBy { "${it.signalType}:${it.timestampMillis}" }
        .mapNotNull { event ->
            val idxNearest = points.indices.minByOrNull { index ->
                abs(points[index].timestampMillis - event.timestampMillis)
            } ?: return@mapNotNull null
            val diffNearest = abs(points[idxNearest].timestampMillis - event.timestampMillis)
            val idx = when {
                diffNearest <= maxSnapMs -> idxNearest
                event.timestampMillis > rangeEnd && event.timestampMillis <= rangeEnd + edgeGraceMs ->
                    points.lastIndex
                event.timestampMillis < rangeStart && event.timestampMillis >= rangeStart - edgeGraceMs ->
                    0
                else -> return@mapNotNull null
            }
            val (color, label, shape) = when (event.signalType) {
                StrategySignalType.EnterLong -> Triple(Color(0xFF69F0AE), "Enter LONG", ChartMarkerShape.TriangleUp)
                StrategySignalType.EnterShort -> Triple(Color(0xFFFF8A80), "Enter SHORT", ChartMarkerShape.TriangleDown)
                StrategySignalType.ExitLong -> Triple(Color(0xFF90CAF9), "Exit LONG", ChartMarkerShape.Diamond)
                StrategySignalType.ExitShort -> Triple(Color(0xFFFFCC80), "Exit SHORT", ChartMarkerShape.Diamond)
            }
            ChartPointMarker(
                index = idx,
                value = points[idx].zScore,
                color = color,
                label = label,
                shape = shape
            )
        }
        .toList()
}

/** 15м для «Рынок»: видимый период + rolling Z (30д) + запас. 1D ≈ 38д, 3M ≈ 127д (~4k баров). */
internal fun marketsM15LookbackDays(period: Period): Long =
    calendarDaysForMarketsZChartPeriod(period) + Z_SCORE_ROLLING_LOOKBACK_DAYS + 7L

/** Календарных дней для начального окна Z-графика по кнопке периода (1D…1Y). */
internal fun calendarDaysForMarketsZChartPeriod(period: Period): Long = when (period) {
    Period.OneDay -> 1L
    Period.OneWeek -> 7L
    Period.OneMonth -> 30L
    Period.ThreeMonths -> 90L
    Period.SixMonths -> 180L
    Period.OneYear -> 365L
}

/** Начальное окно графика: виден хвост последних [visibleDays] календарных дней. */
internal fun chartInitialWindowForLastCalendarDays(
    points: List<DataPoint>,
    visibleDays: Long = STRATEGY_TEST_Z_CHART_VISIBLE_DAYS
): Pair<Float, Float> {
    if (points.size < 2) return 1f to 0f
    val till = points.last().timestampMillis
    val from = java.time.Instant.ofEpochMilli(till)
        .atZone(moexZoneId)
        .toLocalDate()
        .minusDays(visibleDays)
        .atStartOfDay(moexZoneId)
        .toInstant()
        .toEpochMilli()
    val firstVisibleIdx = points.indexOfFirst { it.timestampMillis >= from }.coerceAtLeast(0)
    val maxIndex = points.lastIndex.coerceAtLeast(1)
    val width = ((points.size - firstVisibleIdx).toFloat() / points.size)
        .coerceIn(CHART_ZOOM_MIN_WINDOW, 1f)
    val startUpper = chartWindowStartMax(width)
    val startFromData = (firstVisibleIdx.toFloat() / maxIndex).coerceIn(0f, startUpper)
    val start = maxOf(startFromData, chartInitialWindowStartWithRightGap(width))
        .coerceIn(0f, startUpper)
    return width to start
}

internal fun indexForTradeDateLabel(points: List<DataPoint>, label: String): Int? {
    if (points.isEmpty()) return null
    val exact = points.indexOfFirst { it.tradeDate == label }
    if (exact >= 0) return exact
    val targetTs = parsePortfolioExecutionTableMsk(label) ?: return null
    val idx = points.indices.minByOrNull { kotlin.math.abs(points[it].timestampMillis - targetTs) }
        ?: return null
    val diff = kotlin.math.abs(points[idx].timestampMillis - targetTs)
    return if (diff <= 15 * 60 * 1000L * 2) idx else null
}

private fun chartSnapToleranceMs(points: List<DataPoint>, idx: Int): Long {
    val self = points[idx].timestampMillis
    val prev = points.getOrNull(idx - 1)?.timestampMillis
    val next = points.getOrNull(idx + 1)?.timestampMillis
    val leftGap = if (prev != null) kotlin.math.abs(self - prev) / 2 else 45 * 60_000L
    val rightGap = if (next != null) kotlin.math.abs(next - self) / 2 else 45 * 60_000L
    return max(15 * 60_000L, max(leftGap, rightGap))
}

/** Привязка сделки к ближайшему бару на отображаемом ряду (в т.ч. после downsample). */
internal fun indexForChartTradeLabel(points: List<DataPoint>, label: String): Int? {
    if (points.isEmpty() || label.isBlank() || label == "—") return null
    indexForTradeDateLabel(points, label)?.let { return it }
    val targetTs = parsePortfolioExecutionTableMsk(label) ?: return null
    val idx = points.indices.minByOrNull { kotlin.math.abs(points[it].timestampMillis - targetTs) }
        ?: return null
    val diff = kotlin.math.abs(points[idx].timestampMillis - targetTs)
    return if (diff <= chartSnapToleranceMs(points, idx)) idx else null
}

/** Ближайший бар без отсечения по tolerance — для downsample и TradingView (time = candle). */
internal fun indexForNearestChartBar(points: List<DataPoint>, label: String): Int? {
    if (points.isEmpty() || label.isBlank() || label == "—") return null
    indexForTradeDateLabel(points, label)?.let { return it }
    val targetTs = parsePortfolioExecutionTableMsk(label)
        ?: runCatching { m15CandleLabelToUnixSec(label.trim()) * 1000L }.getOrNull()
        ?: return null
    return points.indices.minByOrNull { kotlin.math.abs(points[it].timestampMillis - targetTs) }
}

/** Время бара для TradingView — из label свечи (должно совпадать с time в payload candles). */
internal fun chartBarTimeSecForIndex(
    idx: Int,
    candles: List<CandlePoint>,
    displayPoints: List<DataPoint>,
): Long? {
    candles.getOrNull(idx)?.label?.let { label ->
        return runCatching { m15CandleLabelToUnixSec(label) }.getOrNull()
    }
    displayPoints.getOrNull(idx)?.tradeDate?.let { label ->
        return runCatching { m15CandleLabelToUnixSec(label) }.getOrNull()
    }
    return null
}

internal fun snapTradeLabelToDisplayBarTimeSec(
    label: String,
    displayPoints: List<DataPoint>,
    candles: List<CandlePoint> = emptyList(),
): Long? {
    val idx = indexForNearestChartBar(displayPoints, label) ?: return null
    return chartBarTimeSecForIndex(idx, candles, displayPoints)
}

internal fun portfolioChartEntryTimeLabel(
    entryTimeMsk: String,
    entrySignalBarTimeMsk: String,
    barTimestampMillis: Long,
): String = when {
    entrySignalBarTimeMsk.isNotBlank() && entrySignalBarTimeMsk != "—" -> entrySignalBarTimeMsk
    entryTimeMsk.isNotBlank() && entryTimeMsk != "—" -> entryTimeMsk
    else -> formatPortfolioExecutionTableMsk(barTimestampMillis)
}

internal fun remapChartMarkersToDisplaySeries(
    sourcePoints: List<DataPoint>,
    displayPoints: List<DataPoint>,
    markers: List<ChartPointMarker>,
): List<ChartPointMarker> {
    if (sourcePoints.isEmpty() || displayPoints.isEmpty()) return emptyList()
    if (sourcePoints.size == displayPoints.size &&
        sourcePoints.zip(displayPoints).all { (a, b) -> a.timestampMillis == b.timestampMillis }
    ) {
        return markers
    }
    return markers.mapNotNull { marker ->
        val timeLabel = marker.barDateLabel
            ?: sourcePoints.getOrNull(marker.index)?.let { formatPortfolioExecutionTableMsk(it.timestampMillis) }
            ?: return@mapNotNull null
        val displayIdx = indexForNearestChartBar(displayPoints, timeLabel) ?: return@mapNotNull null
        val z = if (marker.value.isNaN()) displayPoints[displayIdx].zScore else marker.value
        marker.copy(index = displayIdx, value = z, barDateLabel = timeLabel)
    }
}

/** Подпись маркера симуляции «Тест страт.» — как на «Рынок» (1А / 2Р). */
internal fun strategyTestTradeChartBadge(listIndex: Int, direction: ZStrategyPosition): String {
    val num = (listIndex + 1).toString()
    val confirm = when (direction) {
        ZStrategyPosition.Long -> "авто"
        ZStrategyPosition.Short -> "ручное"
        ZStrategyPosition.Flat -> "сигнал"
    }
    return portfolioTradeChartBadgeText("$num x", confirm)
}

/** Маркеры входа/выхода симуляции «Тест страт.»; badge как на «Рынок» (1А / 2Р). */
internal fun buildZScoreMarkersFromStrategyTestTrades(
    points: List<DataPoint>,
    tradeItems: List<StrategyTestTradeItem>,
    openPosition: PortfolioOpenPosition? = null,
): List<ChartPointMarker> {
    if (points.isEmpty()) return emptyList()
    val markers = mutableListOf<ChartPointMarker>()
    tradeItems.forEachIndexed { listIndex, item ->
        val badge = strategyTestTradeChartBadge(listIndex, item.trade.direction)
        val t = item.trade
        val (enterShape, enterColor) = when (t.direction) {
            ZStrategyPosition.Long -> ChartMarkerShape.TriangleUp to Color(0xFF69F0AE)
            ZStrategyPosition.Short -> ChartMarkerShape.TriangleDown to Color(0xFFFF8A80)
            ZStrategyPosition.Flat -> ChartMarkerShape.Circle to Color(0xFFB0BEC5)
        }
        indexForNearestChartBar(points, t.entryDate)?.let { idx ->
            markers += ChartPointMarker(
                index = idx,
                value = points[idx].zScore,
                color = enterColor,
                label = "Вх $badge",
                shape = enterShape,
                badgeText = badge,
                barDateLabel = t.entryDate,
            )
        }
        indexForNearestChartBar(points, t.exitDate)?.let { idx ->
            markers += ChartPointMarker(
                index = idx,
                value = points[idx].zScore,
                color = Color(0xFFFFCC80),
                label = "Вых $badge",
                shape = ChartMarkerShape.Diamond,
                badgeText = badge,
                barDateLabel = t.exitDate,
            )
        }
    }
    openPosition?.let { open ->
        val badge = strategyTestTradeChartBadge(tradeItems.size, open.direction)
        val (enterShape, enterColor) = when (open.direction) {
            ZStrategyPosition.Long -> ChartMarkerShape.TriangleUp to Color(0xFF69F0AE)
            ZStrategyPosition.Short -> ChartMarkerShape.TriangleDown to Color(0xFFFF8A80)
            ZStrategyPosition.Flat -> ChartMarkerShape.Circle to Color(0xFFB0BEC5)
        }
        indexForNearestChartBar(points, open.entryDate)?.let { idx ->
            markers += ChartPointMarker(
                index = idx,
                value = points[idx].zScore,
                color = enterColor,
                label = "Вх $badge",
                shape = enterShape,
                badgeText = badge,
                barDateLabel = open.entryDate,
            )
        }
    }
    return markers
}

private fun portfolioTradeEnterMarkerStyle(directionLabel: String): Pair<ChartMarkerShape, Color> =
    when (directionLabel.lowercase(Locale.US)) {
        "short" -> ChartMarkerShape.TriangleDown to Color(0xFFFF8A80)
        else -> ChartMarkerShape.TriangleUp to Color(0xFF69F0AE)
    }

/** Маркеры сделок портфеля (демо): номер + тип (А/Р) у входа и выхода. */
internal fun buildZScoreMarkersFromPortfolioTrades(
    points: List<DataPoint>,
    openExecutions: List<SandboxSpreadExecUi>,
    closedRows: List<PortfolioConfirmedTradeTableRow>,
): List<ChartPointMarker> {
    if (points.isEmpty()) return emptyList()
    val markers = mutableListOf<ChartPointMarker>()
    fun addEntry(
        entryLabel: String,
        entryZ: Double,
        tradeDisplayId: String,
        confirmLabel: String,
        directionLabel: String,
    ) {
        val idx = indexForChartTradeLabel(points, entryLabel) ?: return
        val (shape, color) = portfolioTradeEnterMarkerStyle(directionLabel)
        val badge = portfolioTradeChartBadgeText(tradeDisplayId, confirmLabel)
        markers += ChartPointMarker(
            index = idx,
            value = if (entryZ.isNaN()) points[idx].zScore else entryZ,
            color = color,
            label = "Вх $badge",
            shape = shape,
            badgeText = badge,
        )
    }
    fun addExit(
        exitLabel: String,
        exitZ: Double,
        tradeDisplayId: String,
        confirmLabel: String,
    ) {
        if (exitLabel.isBlank() || exitLabel == "—") return
        val idx = indexForChartTradeLabel(points, exitLabel) ?: return
        val badge = portfolioTradeChartBadgeText(tradeDisplayId, confirmLabel)
        markers += ChartPointMarker(
            index = idx,
            value = if (exitZ.isNaN()) points[idx].zScore else exitZ,
            color = Color(0xFFFFCC80),
            label = "Вых $badge",
            shape = ChartMarkerShape.Diamond,
            badgeText = badge,
        )
    }
    openExecutions.forEach { exec ->
        addEntry(
            entryLabel = portfolioChartEntryTimeLabel(
                entryTimeMsk = exec.entryTimeMsk,
                entrySignalBarTimeMsk = exec.entrySignalBarTimeMsk,
                barTimestampMillis = exec.barTimestampMillis,
            ),
            entryZ = exec.zScore,
            tradeDisplayId = exec.tradeDisplayId,
            confirmLabel = exec.confirmLabel,
            directionLabel = exec.directionLabel,
        )
    }
    closedRows.forEach { row ->
        addEntry(
            entryLabel = portfolioChartEntryTimeLabel(
                entryTimeMsk = row.entryTimeMsk,
                entrySignalBarTimeMsk = row.entrySignalBarTimeMsk,
                barTimestampMillis = parsePortfolioExecutionTableMsk(row.entryTimeMsk) ?: 0L,
            ),
            entryZ = row.entryZ,
            tradeDisplayId = row.tradeDisplayId,
            confirmLabel = row.confirmLabel,
            directionLabel = row.directionLabel,
        )
        addExit(
            exitLabel = row.exitTimeMsk,
            exitZ = row.exitZ,
            tradeDisplayId = row.tradeDisplayId,
            confirmLabel = row.confirmLabel,
        )
    }
    return markers
}

internal fun zScoreChartMarkersWithPortfolioTrades(
    points: List<DataPoint>,
    signalEvents: List<StrategySignalEvent>,
    openExecutions: List<SandboxSpreadExecUi>,
    closedRows: List<PortfolioConfirmedTradeTableRow>,
): List<ChartPointMarker> =
    buildZScoreSignalMarkersFromEvents(points, signalEvents) +
        buildZScoreMarkersFromPortfolioTrades(points, openExecutions, closedRows)

/** Маркеры только по сделкам портфеля (без Enter LONG / Exit SHORT). */
internal fun zScoreChartMarkersFromPortfolioTrades(
    points: List<DataPoint>,
    openExecutions: List<SandboxSpreadExecUi>,
    closedRows: List<PortfolioConfirmedTradeTableRow>,
): List<ChartPointMarker> =
    buildZScoreMarkersFromPortfolioTrades(points, openExecutions, closedRows)

/** Равномерный прореживание ряда для отрисовки (симуляция — на полном ряду). */
