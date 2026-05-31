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
    val start = (firstVisibleIdx.toFloat() / maxIndex).coerceIn(0f, 1f - width)
    return width to start
}

internal fun indexForTradeDateLabel(points: List<DataPoint>, label: String): Int? {
    if (points.isEmpty()) return null
    val exact = points.indexOfFirst { it.tradeDate == label }
    if (exact >= 0) return exact
    val targetTs = runCatching {
        LocalDateTime.parse(label, portfolio15mLabelFormatter)
            .atZone(moexZoneId)
            .toInstant()
            .toEpochMilli()
    }.getOrNull() ?: return null
    val idx = points.indices.minByOrNull { kotlin.math.abs(points[it].timestampMillis - targetTs) }
        ?: return null
    val diff = kotlin.math.abs(points[idx].timestampMillis - targetTs)
    return if (diff <= 15 * 60 * 1000L * 2) idx else null
}

/** Маркеры входа/выхода симуляции «Тест страт.»; [badgeText] = номер как в списке (#1 — свежая). */
internal fun buildZScoreMarkersFromStrategyTestTrades(
    points: List<DataPoint>,
    tradeItems: List<StrategyTestTradeItem>
): List<ChartPointMarker> {
    if (points.isEmpty() || tradeItems.isEmpty()) return emptyList()
    val markers = mutableListOf<ChartPointMarker>()
    tradeItems.forEachIndexed { listIndex, item ->
        val num = "#${listIndex + 1}"
        val t = item.trade
        val (enterShape, enterColor) = when (t.direction) {
            ZStrategyPosition.Long -> ChartMarkerShape.TriangleUp to Color(0xFF69F0AE)
            ZStrategyPosition.Short -> ChartMarkerShape.TriangleDown to Color(0xFFFF8A80)
            ZStrategyPosition.Flat -> ChartMarkerShape.Circle to Color(0xFFB0BEC5)
        }
        indexForTradeDateLabel(points, t.entryDate)?.let { idx ->
            markers += ChartPointMarker(
                index = idx,
                value = points[idx].zScore,
                color = enterColor,
                label = "Вх #$num",
                shape = enterShape,
                badgeText = num
            )
        }
        indexForTradeDateLabel(points, t.exitDate)?.let { idx ->
            markers += ChartPointMarker(
                index = idx,
                value = points[idx].zScore,
                color = Color(0xFFFFCC80),
                label = "Вых #$num",
                shape = ChartMarkerShape.Diamond,
                badgeText = num
            )
        }
    }
    return markers
}

/** Равномерный прореживание ряда для отрисовки (симуляция — на полном ряду). */
