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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.Alignment
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
import kotlin.math.roundToInt

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
    candles: List<CandlePoint> = emptyList(),
    openPosition: PortfolioOpenPosition? = null,
): List<TradingViewTradeSegment> {
    val last = displayPoints.lastOrNull()
    val lastTimeSec = chartBarTimeSecForIndex(displayPoints.lastIndex, candles, displayPoints)
        ?: last?.tradeDate?.let { snapTradeLabelToDisplayBarTimeSec(it, displayPoints, candles) }
    val lastZ = last?.zScore
    val barIndex = buildM15BarIndexByLabel(displayPoints)
    val segments = mutableListOf<TradingViewTradeSegment>()
    for ((listIndex, item) in tradeItems.withIndex()) {
        val trade = item.trade
        val badge = strategyTestTradeChartBadge(listIndex, trade.direction)
        val tradeId = tradingViewMarkerDisplayText(badge)
        val entryTime = snapTradeLabelToDisplayBarTimeSec(trade.entryDate, displayPoints, candles) ?: continue
        val exitTime = snapTradeLabelToDisplayBarTimeSec(trade.exitDate, displayPoints, candles)
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
        val entryTime = snapTradeLabelToDisplayBarTimeSec(open.entryDate, displayPoints, candles) ?: return@let
        val entryZ = barIndex[open.entryDate]?.let { displayPoints[it].zScore }
            ?: displayPoints.getOrNull(indexForNearestChartBar(displayPoints, open.entryDate) ?: -1)?.zScore
            ?: lastZ ?: 0.0
        val openBadge = strategyTestTradeChartBadge(tradeItems.size, open.direction)
        segments += TradingViewTradeSegment(
            id = tradingViewMarkerDisplayText(openBadge),
            entryTimeSec = entryTime,
            exitTimeSec = lastTimeSec,
            entryZ = entryZ,
            exitZ = lastZ,
            isOpen = true,
        )
    }
    return segments
}

internal data class StrategyTestTvMarkerBucket(
    val timeSec: Long,
    val position: String,
    val color: String,
    val shape: String,
    val isEntry: Boolean,
    val tradeIds: MutableList<String> = mutableListOf(),
)

private fun strategyTestEntryMarkerStyle(direction: ZStrategyPosition): Triple<String, String, String> =
    when (direction) {
        ZStrategyPosition.Short -> Triple("aboveBar", "#FF8A80", "arrowDown")
        ZStrategyPosition.Long -> Triple("belowBar", "#69F0AE", "arrowUp")
        ZStrategyPosition.Flat -> Triple("inBar", "#B0BEC5", "circle")
    }

/** Маркеры симуляции «Тест страт.» с объединением подписей на одном баре. */
internal fun buildStrategyTestTradingViewMarkerBuckets(
    tradeItems: List<StrategyTestTradeItem>,
    displayPoints: List<DataPoint>,
    candles: List<CandlePoint>,
    openPosition: PortfolioOpenPosition? = null,
    snapToCandleTimeSec: (Long) -> Long?,
): List<StrategyTestTvMarkerBucket> {
    val buckets = linkedMapOf<String, StrategyTestTvMarkerBucket>()
    fun add(
        timeSec: Long?,
        direction: ZStrategyPosition,
        tradeId: String,
        isEntry: Boolean,
    ) {
        val raw = timeSec ?: return
        val snapped = snapToCandleTimeSec(raw) ?: return
        val (position, color, shape) = if (isEntry) {
            strategyTestEntryMarkerStyle(direction)
        } else {
            Triple("inBar", "#FFCC80", "circle")
        }
        val key = "$snapped:$isEntry:$position:$shape"
        val bucket = buckets.getOrPut(key) {
            StrategyTestTvMarkerBucket(
                timeSec = snapped,
                position = position,
                color = color,
                shape = shape,
                isEntry = isEntry,
            )
        }
        if (tradeId !in bucket.tradeIds) bucket.tradeIds += tradeId
    }
    for ((listIndex, item) in tradeItems.withIndex()) {
        val trade = item.trade
        val tradeId = tradingViewMarkerDisplayText(strategyTestTradeChartBadge(listIndex, trade.direction))
        add(
            timeSec = snapTradeLabelToDisplayBarTimeSec(trade.entryDate, displayPoints, candles),
            direction = trade.direction,
            tradeId = tradeId,
            isEntry = true,
        )
        add(
            timeSec = snapTradeLabelToDisplayBarTimeSec(trade.exitDate, displayPoints, candles),
            direction = trade.direction,
            tradeId = tradeId,
            isEntry = false,
        )
    }
    openPosition?.let { open ->
        val tradeId = tradingViewMarkerDisplayText(
            strategyTestTradeChartBadge(tradeItems.size, open.direction)
        )
        add(
            timeSec = snapTradeLabelToDisplayBarTimeSec(open.entryDate, displayPoints, candles),
            direction = open.direction,
            tradeId = tradeId,
            isEntry = true,
        )
    }
    return buckets.values.toList()
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
    strategyTestTradeItems: List<StrategyTestTradeItem> = emptyList(),
    openPosition: PortfolioOpenPosition? = null,
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f,
    areaFillColor: String? = null,
    chartBackgroundHex: String? = null,
    pnlRubPerSpreadPoint: Double? = null,
    pnlNetOffsetRub: Double = 0.0,
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
    val markerKeys = linkedSetOf<String>()
    val candleTimesSec = candles.map { m15CandleLabelToUnixSec(it.label) }
    val candleTimeSet = candleTimesSec.toSet()
    fun snapToCandleTimeSec(timeSec: Long): Long? {
        if (timeSec in candleTimeSet) return timeSec
        if (candleTimesSec.isEmpty()) return null
        return candleTimesSec.minByOrNull { kotlin.math.abs(it - timeSec) }
    }
    fun putMarker(
        timeSec: Long,
        position: String,
        color: String,
        shape: String,
        tradeId: String,
        isEntry: Boolean,
        size: Double,
        markerKeys: MutableSet<String>,
    ) {
        val snapped = snapToCandleTimeSec(timeSec) ?: return
        val key = "$tradeId:${if (isEntry) "e" else "x"}:$snapped"
        if (!markerKeys.add(key)) return
        markers.put(
            JSONObject()
                .put("time", snapped)
                .put("position", position)
                .put("color", color)
                .put("shape", shape)
                .put("text", tradeId)
                .put("size", size)
                .put("tradeId", tradeId)
                .put("isEntry", isEntry)
        )
    }
    val useStrategyTestMarkers = strategyTestTradeItems.isNotEmpty() || openPosition != null
    if (useStrategyTestMarkers) {
        val buckets = buildStrategyTestTradingViewMarkerBuckets(
            tradeItems = strategyTestTradeItems,
            displayPoints = displayPoints,
            candles = candles,
            openPosition = openPosition,
            snapToCandleTimeSec = ::snapToCandleTimeSec,
        )
        for (bucket in buckets) {
            val label = bucket.tradeIds.joinToString(" ")
            val tradeId = bucket.tradeIds.first()
            markers.put(
                JSONObject()
                    .put("time", bucket.timeSec)
                    .put("position", bucket.position)
                    .put("color", bucket.color)
                    .put("shape", bucket.shape)
                    .put("text", label)
                    .put("size", 2.5)
                    .put("tradeId", tradeId)
                    .put("isEntry", bucket.isEntry)
            )
        }
    } else {
        for (m in pointMarkers.filter { !it.badgeText.isNullOrBlank() }) {
            val barTimeSec = resolveTradingViewMarkerBarTimeSec(m, displayPoints, candles) ?: continue
            val tv = tradingViewMarkerFromChartMarker(m, barTimeSec)
            val tradeId = tradingViewMarkerDisplayText(m.badgeText.orEmpty())
            putMarker(
                timeSec = tv.time,
                position = tv.position,
                color = tv.color,
                shape = tv.shape,
                tradeId = tradeId,
                isEntry = m.label.startsWith("Вх", ignoreCase = true),
                size = tv.size,
                markerKeys = markerKeys,
            )
        }
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
        .put(
            "rightOffsetBars",
            tradingViewZChartRightOffsetBars(
                barCount = candleArr.length(),
                windowWidth = initialWindowWidth,
            ),
        )
    if (!areaFillColor.isNullOrBlank()) {
        root.put("areaFillColor", areaFillColor)
    }
    if (!chartBackgroundHex.isNullOrBlank()) {
        root.put("backgroundColor", chartBackgroundHex)
    }
    if (pnlRubPerSpreadPoint != null) {
        root.put(
            "pnlAxis",
            JSONObject()
                .put("rubPerPoint", pnlRubPerSpreadPoint)
                .put("netOffset", pnlNetOffsetRub),
        )
    }
    return root.toString()
}

/** Пустой зазор справа на Z-score (parity CHART_RIGHT_PLOT_PADDING_FRACTION / 1м график). */
internal fun tradingViewZChartRightOffsetBars(
    barCount: Int,
    windowWidth: Float,
): Int {
    if (barCount <= 0) return 12
    val visible = (barCount * windowWidth.coerceIn(CHART_ZOOM_MIN_WINDOW, 1f))
        .roundToInt()
        .coerceAtLeast(1)
    return maxOf(12, (visible * CHART_RIGHT_PLOT_PADDING_FRACTION).roundToInt())
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
        size = 2.0,
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
    chartBarTimeSecForIndex(marker.index, candles, displayPoints)?.let { return it }
    marker.barDateLabel?.let { label ->
        val idx = indexForNearestChartBar(displayPoints, label) ?: return@let null
        return chartBarTimeSecForIndex(idx, candles, displayPoints)
    }
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
        },
        modifier = modifier,
    )

    LaunchedEffect(pageReady, payloadJson) {
        if (pageReady) deliverPayload()
    }
}

/** Z-score свечи через TradingView lightweight-charts (strategy-web mobile /m). */
@Composable
internal fun TradingViewZScoreChartCard(
    title: String,
    subtitle: String? = null,
    candles: List<CandlePoint>,
    displayPoints: List<DataPoint>,
    chartHeightDp: Int,
    referenceLines: List<ChartReferenceLine>,
    pointMarkers: List<ChartPointMarker>,
    tradeSegments: List<TradingViewTradeSegment> = emptyList(),
    strategyTestTradeItems: List<StrategyTestTradeItem> = emptyList(),
    openPosition: PortfolioOpenPosition? = null,
    modifier: Modifier = Modifier,
    landscapeMinimal: Boolean = false,
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f,
    areaFillColor: String? = null,
    chartBackgroundHex: String? = null,
    pnlRubPerSpreadPoint: Double? = null,
    pnlNetOffsetRub: Double = 0.0,
    onFullscreenClick: (() -> Unit)? = null,
    onExitFullscreenClick: (() -> Unit)? = null,
) {
    if (candles.isEmpty()) return
    val payload = remember(
        candles,
        displayPoints,
        referenceLines,
        pointMarkers,
        tradeSegments,
        strategyTestTradeItems,
        openPosition,
        initialWindowWidth,
        initialWindowStart,
        areaFillColor,
        chartBackgroundHex,
        pnlRubPerSpreadPoint,
        pnlNetOffsetRub,
    ) {
        buildTradingViewChartPayloadJson(
            candles = candles,
            displayPoints = displayPoints,
            referenceLines = referenceLines,
            pointMarkers = pointMarkers,
            tradeSegments = tradeSegments,
            strategyTestTradeItems = strategyTestTradeItems,
            openPosition = openPosition,
            initialWindowWidth = initialWindowWidth,
            initialWindowStart = initialWindowStart,
            areaFillColor = areaFillColor,
            chartBackgroundHex = chartBackgroundHex,
            pnlRubPerSpreadPoint = pnlRubPerSpreadPoint,
            pnlNetOffsetRub = pnlNetOffsetRub,
        )
    }
    val cardBg = chartBackgroundHex?.let { hex ->
        runCatching {
            Color(AndroidColor.parseColor(hex))
        }.getOrNull()
    } ?: Color(0xFF131722)
    Column(
        modifier = modifier
            .then(if (landscapeMinimal) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .background(cardBg, RoundedCornerShape(if (landscapeMinimal) 0.dp else 12.dp)),
    ) {
        if (!landscapeMinimal && (title.isNotBlank() || !subtitle.isNullOrBlank() || onFullscreenClick != null || onExitFullscreenClick != null)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (title.isNotBlank()) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE5E7EB),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .padding(start = 4.dp),
                        )
                    }
                    subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                        Text(
                            text = sub,
                            color = Color(0xFF80CBC4),
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                        )
                    }
                }
                when {
                    onExitFullscreenClick != null -> {
                        IconButton(
                            onClick = onExitFullscreenClick,
                            modifier = Modifier.size(36.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = Color(0xFF90CAF9),
                            ),
                        ) {
                            Icon(
                                Icons.Filled.CloseFullscreen,
                                contentDescription = "Свернуть",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    onFullscreenClick != null -> {
                        IconButton(
                            onClick = onFullscreenClick,
                            modifier = Modifier.size(36.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = Color(0xFF90CAF9),
                            ),
                        ) {
                            Icon(
                                Icons.Filled.OpenInFull,
                                contentDescription = "На весь экран",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
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
