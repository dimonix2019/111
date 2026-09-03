package com.example.moexmvp.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val moexZoneId: ZoneId = ZoneId.of("Europe/Moscow")
private const val LW_INJECT = "<!-- INJECT_LIGHTWEIGHT_CHARTS -->"

internal fun loadTradingViewChartHtml(): String {
  val template = object {}.javaClass.getResource("/tradingview/z_chart.html")?.readText()
        ?: error("z_chart.html not in resources")
    val js = object {}.javaClass.getResource("/tradingview/lightweight-charts.standalone.production.js")?.readText()
        ?: error("lightweight-charts.js not in resources")
    return template.replace(LW_INJECT, "<script>\n$js\n</script>")
}

internal fun buildZScoreCandlesFromM15Points(points: List<DataPoint>): List<CandlePoint> =
    points.mapIndexed { index, point ->
        val close = point.zScore
        val open = points.getOrNull(index - 1)?.zScore ?: close
        CandlePoint(
            label = point.tradeDate,
            open = open,
            high = max(open, close),
            low = min(open, close),
            close = close,
        )
    }

internal fun barReplayWindowCandles(
    points: List<DataPoint>,
    cursorIndex: Int,
    visibleDays: Long,
): List<CandlePoint> {
    val range = barReplayVisibleIndexRange(points, cursorIndex, visibleDays)
    if (range.isEmpty()) return emptyList()
    return buildZScoreCandlesFromM15Points(points.subList(range.first, range.last + 1))
}

internal fun buildZReferenceLines(thresholds: DynamicThresholds): List<ChartReferenceLine> = listOf(
    ChartReferenceLine(thresholds.entry, "#EF5350", "+Entry"),
    ChartReferenceLine(-thresholds.entry, "#66BB6A", "−Entry"),
    ChartReferenceLine(thresholds.exit, "#FFB74D", "+Exit"),
    ChartReferenceLine(-thresholds.exit, "#4DD0E1", "−Exit"),
    ChartReferenceLine(0.0, "#616161", "0"),
)

internal fun barReplaySignalMarkers(
    edges: List<ZStrategy15mSignalEdge>,
    points: List<DataPoint>,
): List<ChartPointMarker> {
    if (edges.isEmpty() || points.isEmpty()) return emptyList()
    val out = ArrayList<ChartPointMarker>(edges.size)
    var tradeNo = 0
    for (edge in edges) {
        val idx = points.indexOfFirst { it.timestampMillis == edge.bar.timestampMillis }.takeIf { it >= 0 }
            ?: continue
        when (edge.signal) {
            ZStrategySignal.EnterLong -> {
                tradeNo += 1
                out += ChartPointMarker(
                    index = idx,
                    value = edge.bar.zScore,
                    colorHex = "#69F0AE",
                    label = "Вх Long",
                    shape = ChartMarkerShape.TriangleUp,
                    badgeText = "${tradeNo}A",
                    barDateLabel = edge.bar.tradeDate,
                )
            }
            ZStrategySignal.EnterShort -> {
                tradeNo += 1
                out += ChartPointMarker(
                    index = idx,
                    value = edge.bar.zScore,
                    colorHex = "#FF5252",
                    label = "Вх Short",
                    shape = ChartMarkerShape.TriangleDown,
                    badgeText = "${tradeNo}A",
                    barDateLabel = edge.bar.tradeDate,
                )
            }
            ZStrategySignal.ExitLong -> {
                out += ChartPointMarker(
                    index = idx,
                    value = edge.bar.zScore,
                    colorHex = "#80CBC4",
                    label = "Вых Long",
                    shape = ChartMarkerShape.Diamond,
                    badgeText = "${tradeNo}R",
                    barDateLabel = edge.bar.tradeDate,
                )
            }
            ZStrategySignal.ExitShort -> {
                out += ChartPointMarker(
                    index = idx,
                    value = edge.bar.zScore,
                    colorHex = "#FFAB91",
                    label = "Вых Short",
                    shape = ChartMarkerShape.Diamond,
                    badgeText = "${tradeNo}R",
                    barDateLabel = edge.bar.tradeDate,
                )
            }
            ZStrategySignal.None -> Unit
        }
    }
    return out
}

internal fun buildTradeSegmentsFromEdges(
    edges: List<ZStrategy15mSignalEdge>,
): List<TradingViewTradeSegment> {
    val out = ArrayList<TradingViewTradeSegment>()
    var tradeNo = 0
    var openEntry: ZStrategy15mSignalEdge? = null
    for (edge in edges) {
        when (edge.signal) {
            ZStrategySignal.EnterLong, ZStrategySignal.EnterShort -> {
                tradeNo += 1
                openEntry = edge
            }
            ZStrategySignal.ExitLong, ZStrategySignal.ExitShort -> {
                val entry = openEntry ?: continue
                out += TradingViewTradeSegment(
                    id = "${tradeNo}R",
                    entryTimeSec = m15LabelToUnixSec(entry.bar.tradeDate),
                    exitTimeSec = m15LabelToUnixSec(edge.bar.tradeDate),
                    entryZ = entry.bar.zScore,
                    exitZ = edge.bar.zScore,
                    isOpen = false,
                )
                openEntry = null
            }
            ZStrategySignal.None -> Unit
        }
    }
    openEntry?.let { entry ->
        out += TradingViewTradeSegment(
            id = "${tradeNo}A",
            entryTimeSec = m15LabelToUnixSec(entry.bar.tradeDate),
            exitTimeSec = null,
            entryZ = entry.bar.zScore,
            exitZ = null,
            isOpen = true,
        )
    }
    return out
}

internal fun buildTradeTableRows(edges: List<ZStrategy15mSignalEdge>): List<DesktopTradeRow> {
    val rows = ArrayList<DesktopTradeRow>()
    var tradeNo = 0
    var openEntry: ZStrategy15mSignalEdge? = null
    for (edge in edges) {
        when (edge.signal) {
            ZStrategySignal.EnterLong, ZStrategySignal.EnterShort -> {
                tradeNo += 1
                openEntry = edge
            }
            ZStrategySignal.ExitLong, ZStrategySignal.ExitShort -> {
                val entry = openEntry ?: continue
                val side = if (entry.signal == ZStrategySignal.EnterLong) "Long" else "Short"
                rows += DesktopTradeRow(
                    id = "$tradeNo",
                    side = side,
                    entryTime = entry.bar.tradeDate,
                    exitTime = edge.bar.tradeDate,
                    entryZ = "%+.2f".format(entry.bar.zScore),
                    exitZ = "%+.2f".format(edge.bar.zScore),
                    status = "Закрыта",
                )
                openEntry = null
            }
            ZStrategySignal.None -> Unit
        }
    }
    openEntry?.let { entry ->
        val side = if (entry.signal == ZStrategySignal.EnterLong) "Long" else "Short"
        rows += DesktopTradeRow(
            id = "$tradeNo",
            side = side,
            entryTime = entry.bar.tradeDate,
            exitTime = "—",
            entryZ = "%+.2f".format(entry.bar.zScore),
            exitZ = "—",
            status = "Открыта",
        )
    }
    return rows
}

internal fun m15LabelToUnixSec(label: String): Long {
    val dt = LocalDateTime.parse(label.trim(), portfolio15mLabelFormatter)
    return dt.atZone(moexZoneId).toEpochSecond()
}

internal fun buildTradingViewReplayCursorJson(
    candles: List<CandlePoint>,
    displayPoints: List<DataPoint>,
    referenceLines: List<ChartReferenceLine>,
    pointMarkers: List<ChartPointMarker>,
    tradeSegments: List<TradingViewTradeSegment>,
    playing: Boolean = false,
): String {
    val candleArr = JSONArray()
    val seen = linkedSetOf<Long>()
    for (c in candles) {
        val timeSec = m15LabelToUnixSec(c.label)
        if (!seen.add(timeSec)) continue
        candleArr.put(
            JSONObject()
                .put("time", timeSec)
                .put("open", c.open)
                .put("high", c.high)
                .put("low", c.low)
                .put("close", c.close),
        )
    }
    val hlines = JSONArray()
    for (hl in referenceLines) {
        hlines.put(
            JSONObject()
                .put("value", hl.value)
                .put("color", hl.colorHex)
                .put("title", hl.label),
        )
    }
    val candleTimesSec = candles.map { m15LabelToUnixSec(it.label) }
    val candleTimeSet = candleTimesSec.toSet()
    fun snap(timeSec: Long): Long? {
        if (timeSec in candleTimeSet) return timeSec
        if (candleTimesSec.isEmpty()) return null
        return candleTimesSec.minByOrNull { abs(it - timeSec) }
    }
    val markers = JSONArray()
    for (m in pointMarkers.filter { !it.badgeText.isNullOrBlank() }) {
        val barTimeSec = resolveMarkerBarTimeSec(m, displayPoints, candles) ?: continue
        val tv = tradingViewMarkerFrom(m, barTimeSec)
        val snapped = snap(tv.time) ?: continue
        markers.put(
            JSONObject()
                .put("time", snapped)
                .put("position", tv.position)
                .put("color", tv.color)
                .put("shape", tv.shape)
                .put("text", tv.text)
                .put("size", tv.size)
                .put("tradeId", tv.text)
                .put("isEntry", m.label.startsWith("Вх", ignoreCase = true)),
        )
    }
    val trades = JSONArray()
    for (t in tradeSegments) {
        trades.put(
            JSONObject()
                .put("id", t.id)
                .put("entryTime", t.entryTimeSec)
                .put("entryZ", t.entryZ)
                .put("open", t.isOpen)
                .put("exitTime", t.exitTimeSec ?: JSONObject.NULL)
                .put("exitZ", t.exitZ ?: JSONObject.NULL),
        )
    }
    return JSONObject()
        .put("candles", candleArr)
        .put("hlines", hlines)
        .put("markers", markers)
        .put("trades", trades)
        .put("windowWidth", 1.0)
        .put("playing", playing)
        .toString()
}

private data class TvMarker(
    val time: Long,
    val position: String,
    val color: String,
    val shape: String,
    val text: String,
    val size: Double,
)

private fun tradingViewMarkerFrom(marker: ChartPointMarker, barTimeSec: Long): TvMarker {
    val isEntry = marker.label.startsWith("Вх", ignoreCase = true)
    val isLong = marker.shape == ChartMarkerShape.TriangleUp
    val position = when {
        isEntry && isLong -> "belowBar"
        isEntry -> "aboveBar"
        else -> "inBar"
    }
    val shape = when (marker.shape) {
        ChartMarkerShape.TriangleUp -> "arrowUp"
        ChartMarkerShape.TriangleDown -> "arrowDown"
        else -> "circle"
    }
    val text = marker.badgeText?.replace('А', 'A')?.replace('Р', 'R').orEmpty()
    return TvMarker(barTimeSec, position, marker.colorHex, shape, text, 2.0)
}

private fun resolveMarkerBarTimeSec(
    marker: ChartPointMarker,
    displayPoints: List<DataPoint>,
    candles: List<CandlePoint>,
): Long? {
    if (marker.index in displayPoints.indices) {
        return m15LabelToUnixSec(displayPoints[marker.index].tradeDate)
    }
    marker.barDateLabel?.let { return m15LabelToUnixSec(it) }
    if (marker.index in candles.indices) {
        return m15LabelToUnixSec(candles[marker.index].label)
    }
    return null
}

internal fun encodeReplayPayloadForJs(payloadJson: String): String =
    Base64.getEncoder().encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
