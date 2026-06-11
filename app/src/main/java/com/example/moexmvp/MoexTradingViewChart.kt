package com.example.moexmvp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

private const val TRADINGVIEW_ASSET_BASE = "file:///android_asset/tradingview/"
private const val LW_CHARTS_INJECT_MARKER = "<!-- INJECT_LIGHTWEIGHT_CHARTS -->"

internal data class TradingViewTradeSegment(
    val id: String,
    val entryTimeSec: Long,
    val exitTimeSec: Long?,
    val entryZ: Double,
    val exitZ: Double?,
    val isOpen: Boolean,
)

internal data class ZChartPortfolioOverlay(
    val markers: List<ChartPointMarker>,
    val tradeSegments: List<TradingViewTradeSegment>,
)

internal fun portfolioTableTimeToUnixSec(timeMsk: String): Long? {
    val trimmed = timeMsk.trim()
    if (trimmed.isBlank() || trimmed == "—") return null
    parsePortfolioExecutionTableMsk(trimmed)?.let { return it / 1000L }
    return runCatching { m15CandleLabelToUnixSec(trimmed) }.getOrNull()
}

/** Все закрытые (и опционально открытая) сделки симуляции «Тест страт.» для TradingView. */
internal fun buildTradingViewTradeSegmentsFromStrategyTest(
    tradeItems: List<StrategyTestTradeItem>,
    displayPoints: List<DataPoint>,
    openPosition: PortfolioOpenPosition? = null,
): List<TradingViewTradeSegment> {
    val last = displayPoints.lastOrNull()
    val lastTimeSec = last?.timestampMillis?.div(1000L)
    val lastZ = last?.zScore
    val barIndex = buildM15BarIndexByLabel(displayPoints)
    val segments = mutableListOf<TradingViewTradeSegment>()
    for ((listIndex, item) in tradeItems.withIndex()) {
        val trade = item.trade
        val badge = strategyTestTradeChartBadge(listIndex, trade.direction)
        val tradeId = tradingViewMarkerDisplayText(badge)
        val entryTime = snapTradeLabelToDisplayBarTimeSec(trade.entryDate, displayPoints) ?: continue
        val exitTime = snapTradeLabelToDisplayBarTimeSec(trade.exitDate, displayPoints)
        val entryZ = barIndex[trade.entryDate]?.let { displayPoints[it].zScore }
            ?: lookupSimTradeEntryZ(trade, displayPoints, barIndex)
            ?: displayPoints.getOrNull(indexForNearestChartBar(displayPoints, trade.entryDate) ?: -1)?.zScore
            ?: 0.0
        val exitZ = barIndex[trade.exitDate]?.let { displayPoints[it].zScore }
            ?: displayPoints.getOrNull(indexForNearestChartBar(displayPoints, trade.exitDate) ?: -1)?.zScore
            ?: 0.0
        segments += TradingViewTradeSegment(
            id = tradeId,
            entryTimeSec = entryTime,
            exitTimeSec = exitTime,
            entryZ = entryZ,
            exitZ = exitZ,
            isOpen = false,
        )
    }
    openPosition?.let { open ->
        val entryTime = snapTradeLabelToDisplayBarTimeSec(open.entryDate, displayPoints) ?: return@let
        val entryZ = barIndex[open.entryDate]?.let { displayPoints[it].zScore }
            ?: displayPoints.getOrNull(indexForNearestChartBar(displayPoints, open.entryDate) ?: -1)?.zScore
            ?: lastZ ?: 0.0
        segments += TradingViewTradeSegment(
            id = "open",
            entryTimeSec = entryTime,
            exitTimeSec = lastTimeSec,
            entryZ = entryZ,
            exitZ = lastZ,
            isOpen = true,
        )
    }
    return segments
}

private fun lookupSimTradeEntryZ(
    trade: PortfolioClosedTrade,
    points: List<DataPoint>,
    barIndex: Map<String, Int>,
): Double? {
    val idx = barIndex[trade.entryDate] ?: indexForTradeDateLabel(points, trade.entryDate) ?: return null
    return points[idx].zScore
}

internal fun buildTradingViewTradeSegments(
    opens: List<SandboxSpreadExecUi>,
    closed: List<PortfolioConfirmedTradeTableRow>,
    displayPoints: List<DataPoint>,
): List<TradingViewTradeSegment> {
    val last = displayPoints.lastOrNull()
    val lastTimeSec = last?.let { it.timestampMillis / 1000L }
    val lastZ = last?.zScore
    val segments = mutableListOf<TradingViewTradeSegment>()
    for (row in closed) {
        val id = tradingViewMarkerDisplayText(
            portfolioTradeChartBadgeText(row.tradeDisplayId, row.confirmLabel)
        )
        val entryTime = portfolioTableTimeToUnixSec(row.entryTimeMsk) ?: continue
        val exitTime = portfolioTableTimeToUnixSec(row.exitTimeMsk)
        val exitZ = if (row.exitZ.isNaN()) null else row.exitZ
        segments += TradingViewTradeSegment(
            id = id,
            entryTimeSec = entryTime,
            exitTimeSec = exitTime,
            entryZ = row.entryZ,
            exitZ = exitZ,
            isOpen = false,
        )
    }
    for (exec in opens) {
        if (exec.signalType != StrategySignalType.EnterLong &&
            exec.signalType != StrategySignalType.EnterShort
        ) {
            continue
        }
        val id = tradingViewMarkerDisplayText(
            portfolioTradeChartBadgeText(exec.tradeDisplayId, exec.confirmLabel)
        )
        val entryLabel = portfolioChartEntryTimeLabel(
            entryTimeMsk = exec.entryTimeMsk,
            entrySignalBarTimeMsk = exec.entrySignalBarTimeMsk,
            barTimestampMillis = exec.barTimestampMillis,
        )
        val entryTime = portfolioTableTimeToUnixSec(entryLabel) ?: (exec.barTimestampMillis / 1000L)
        val exitZ = when {
            exec.exitZDisplay.isNaN() -> lastZ
            else -> exec.exitZDisplay
        }
        segments += TradingViewTradeSegment(
            id = id,
            entryTimeSec = entryTime,
            exitTimeSec = lastTimeSec,
            entryZ = exec.zScore,
            exitZ = exitZ,
            isOpen = true,
        )
    }
    return segments
}

/** JSON для WebView lightweight-charts (parity strategy-web /m). */
internal fun buildTradingViewChartPayloadJson(
    candles: List<CandlePoint>,
    displayPoints: List<DataPoint>,
    referenceLines: List<ChartReferenceLine>,
    pointMarkers: List<ChartPointMarker>,
    tradeSegments: List<TradingViewTradeSegment> = emptyList(),
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f,
    areaFillColor: String? = null,
): String {
    val candleArr = JSONArray()
    val seenTimes = linkedSetOf<Long>()
    for (c in candles) {
        val timeSec = m15CandleLabelToUnixSec(c.label)
        if (!seenTimes.add(timeSec)) continue
        candleArr.put(
            JSONObject()
                .put("time", timeSec)
                .put("open", c.open)
                .put("high", c.high)
                .put("low", c.low)
                .put("close", c.close)
        )
    }
    val hlines = JSONArray()
    for (hl in referenceLines) {
        hlines.put(
            JSONObject()
                .put("value", hl.value)
                .put("color", composeColorToHex(hl.color))
                .put("title", hl.label)
        )
    }
    val markers = JSONArray()
    for (m in pointMarkers.filter { !it.badgeText.isNullOrBlank() }) {
        val barTimeSec = resolveTradingViewMarkerBarTimeSec(m, displayPoints, candles) ?: continue
        val tv = tradingViewMarkerFromChartMarker(m, barTimeSec)
        val tradeId = tradingViewMarkerDisplayText(m.badgeText.orEmpty())
        markers.put(
            JSONObject()
                .put("time", tv.time)
                .put("position", tv.position)
                .put("color", tv.color)
                .put("shape", tv.shape)
                .put("text", tradeId)
                .put("size", tv.size)
                .put("tradeId", tradeId)
                .put("isEntry", m.label.startsWith("Вх", ignoreCase = true))
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
                .put("exitZ", t.exitZ ?: JSONObject.NULL)
        )
    }
    val root = JSONObject()
        .put("candles", candleArr)
        .put("hlines", hlines)
        .put("markers", markers)
        .put("trades", trades)
        .put(
            "window",
            JSONObject()
                .put("start", initialWindowStart.toDouble())
                .put("width", initialWindowWidth.toDouble())
        )
    if (!areaFillColor.isNullOrBlank()) {
        root.put("areaFillColor", areaFillColor)
    }
    return root.toString()
}

internal fun m15CandleLabelToUnixSec(label: String): Long {
    val dt = LocalDateTime.parse(label.trim(), portfolio15mLabelFormatter)
    return dt.atZone(moexZoneId).toEpochSecond()
}

internal data class TradingViewMarker(
    val time: Long,
    val position: String,
    val color: String,
    val shape: String,
    val text: String,
    val size: Double,
)

internal fun tradingViewMarkerFromChartMarker(
    marker: ChartPointMarker,
    barTimeSec: Long,
): TradingViewMarker {
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
        ChartMarkerShape.Diamond -> "circle"
        ChartMarkerShape.Circle -> "circle"
    }
    val badge = marker.badgeText?.takeIf { it.isNotBlank() } ?: ""
    val text = tradingViewMarkerDisplayText(badge)
    return TradingViewMarker(
        time = barTimeSec,
        position = position,
        color = composeColorToHex(marker.color),
        shape = shape,
        text = text,
        size = 1.2,
    )
}

internal fun composeColorToHex(color: Color): String =
    String.format("#%06X", 0xFFFFFF and color.toArgb())

/** Подпись маркера на canvas: латиница (А→A, Р→R) — читаемо в WebView. */
internal fun tradingViewMarkerDisplayText(text: String): String =
    text
        .replace('А', 'A')
        .replace('а', 'a')
        .replace('Р', 'R')
        .replace('р', 'r')

/** Время бара для маркера TradingView — должно совпадать с time свечи в payload. */
internal fun resolveTradingViewMarkerBarTimeSec(
    marker: ChartPointMarker,
    displayPoints: List<DataPoint>,
    candles: List<CandlePoint>,
): Long? {
    marker.barDateLabel?.let { label ->
        val idx = indexForNearestChartBar(displayPoints, label)
        if (idx != null) {
            candles.getOrNull(idx)?.let { return m15CandleLabelToUnixSec(it.label) }
            displayPoints.getOrNull(idx)?.let { return it.timestampMillis / 1000L }
        }
    }
    displayPoints.getOrNull(marker.index)?.let { point ->
        candles.getOrNull(marker.index)?.let { return m15CandleLabelToUnixSec(it.label) }
        return point.timestampMillis / 1000L
    }
    candles.getOrNull(marker.index)?.let { return m15CandleLabelToUnixSec(it.label) }
    return null
}

internal fun loadTradingViewChartHtml(context: Context): String {
    val template = context.assets.open("tradingview/z_chart.html").bufferedReader().use { it.readText() }
    val js = context.assets.open("tradingview/lightweight-charts.standalone.production.js")
        .bufferedReader()
        .use { it.readText() }
    return template.replace(
        LW_CHARTS_INJECT_MARKER,
        "<script>\n$js\n</script>",
    )
}

private fun pushTradingViewPayload(webView: WebView, payloadJson: String) {
    val b64 = Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    webView.evaluateJavascript(
        "window.updateMoexChartFromBase64('$b64')",
        null,
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TradingViewZScoreChart(
    payloadJson: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val html = remember { loadTradingViewChartHtml(context.applicationContext) }
    var pageReady by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    fun deliverPayload() {
        val view = webViewRef ?: return
        if (!pageReady) return
        pushTradingViewPayload(view, payloadJson)
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef = this
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(AndroidColor.parseColor("#131722"))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = true
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onReady() {
                            post { pageReady = true }
                        }
                    },
                    "MoexChartBridge",
                )
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript("window.moexChartPageReady && window.moexChartPageReady()", null)
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                        MoexDiagnostics.logWebViewGone(
                            context.applicationContext,
                            "didCrash=${detail?.didCrash()} rendererPriorityAtExit=${detail?.rendererPriorityAtExit()}",
                        )
                        return true
                    }
                }
                loadDataWithBaseURL(TRADINGVIEW_ASSET_BASE, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webViewRef = webView
            deliverPayload()
        },
        modifier = modifier,
    )

    LaunchedEffect(pageReady, payloadJson) {
        deliverPayload()
    }
}

/** Z-score свечи через TradingView lightweight-charts (strategy-web mobile /m). */
@Composable
internal fun TradingViewZScoreChartCard(
    title: String,
    candles: List<CandlePoint>,
    displayPoints: List<DataPoint>,
    chartHeightDp: Int,
    referenceLines: List<ChartReferenceLine>,
    pointMarkers: List<ChartPointMarker>,
    tradeSegments: List<TradingViewTradeSegment> = emptyList(),
    modifier: Modifier = Modifier,
    landscapeMinimal: Boolean = false,
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f,
    areaFillColor: String? = null,
) {
    if (candles.isEmpty()) return
    val payload = remember(
        candles,
        displayPoints,
        referenceLines,
        pointMarkers,
        tradeSegments,
        initialWindowWidth,
        initialWindowStart,
        areaFillColor,
    ) {
        buildTradingViewChartPayloadJson(
            candles = candles,
            displayPoints = displayPoints,
            referenceLines = referenceLines,
            pointMarkers = pointMarkers,
            tradeSegments = tradeSegments,
            initialWindowWidth = initialWindowWidth,
            initialWindowStart = initialWindowStart,
            areaFillColor = areaFillColor,
        )
    }
    Column(
        modifier = modifier
            .then(if (landscapeMinimal) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .background(Color(0xFF131722), RoundedCornerShape(if (landscapeMinimal) 0.dp else 12.dp)),
    ) {
        if (!landscapeMinimal && title.isNotBlank()) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE5E7EB),
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (landscapeMinimal) Modifier.weight(1f).fillMaxHeight()
                    else Modifier.height(chartHeightDp.dp)
                ),
        ) {
            TradingViewZScoreChart(
                payloadJson = payload,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
