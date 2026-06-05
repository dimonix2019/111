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

/** JSON для WebView lightweight-charts (parity strategy-web /m). */
internal fun buildTradingViewChartPayloadJson(
    candles: List<CandlePoint>,
    displayPoints: List<DataPoint>,
    referenceLines: List<ChartReferenceLine>,
    pointMarkers: List<ChartPointMarker>,
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f,
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
    for (m in pointMarkers) {
        val barTimeSec = displayPoints.getOrNull(m.index)?.let { it.timestampMillis / 1000L }
            ?: candles.getOrNull(m.index)?.let { m15CandleLabelToUnixSec(it.label) }
            ?: continue
        val tv = tradingViewMarkerFromChartMarker(m, barTimeSec)
        markers.put(
            JSONObject()
                .put("time", tv.time)
                .put("position", tv.position)
                .put("color", tv.color)
                .put("shape", tv.shape)
                .put("text", tv.text)
                .put("size", tv.size)
        )
    }
    return JSONObject()
        .put("candles", candleArr)
        .put("hlines", hlines)
        .put("markers", markers)
        .put(
            "window",
            JSONObject()
                .put("start", initialWindowStart.toDouble())
                .put("width", initialWindowWidth.toDouble())
        )
        .toString()
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
    val text = marker.badgeText?.takeIf { it.isNotBlank() } ?: marker.label
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
        "window.updateMoexChart(JSON.parse(atob('$b64')))",
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
    modifier: Modifier = Modifier,
    landscapeMinimal: Boolean = false,
    initialWindowWidth: Float = 1f,
    initialWindowStart: Float = 0f,
) {
    if (candles.isEmpty()) return
    val payload = remember(
        candles,
        displayPoints,
        referenceLines,
        pointMarkers,
        initialWindowWidth,
        initialWindowStart,
    ) {
        buildTradingViewChartPayloadJson(
            candles = candles,
            displayPoints = displayPoints,
            referenceLines = referenceLines,
            pointMarkers = pointMarkers,
            initialWindowWidth = initialWindowWidth,
            initialWindowStart = initialWindowStart,
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
