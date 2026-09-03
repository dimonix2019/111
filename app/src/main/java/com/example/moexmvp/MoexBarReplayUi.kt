package com.example.moexmvp

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/** Toggle + панель Bar Replay на «Тест страт.» (данные из уже загруженного M15). */
@Composable
internal fun StrategyTestBarReplaySection(
    points: List<DataPoint>,
    thresholds: DynamicThresholds,
    chartHeightDp: Int,
    modifier: Modifier = Modifier,
    onReplayEnabledChange: (Boolean) -> Unit = {},
) {
    var enabled by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF141414), RoundedCornerShape(10.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Режим replay",
                color = Color(0xFFE0E0E0),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    onReplayEnabledChange(it)
                },
            )
        }
        if (!enabled) {
            Text(
                text = "Вкл. — свечи по одному (TradingView Bar Replay), без новой загрузки MOEX",
                color = Color(0xFF757575),
                fontSize = 9.sp,
            )
            return
        }
        if (points.size < 2) {
            Text("Нет 15м данных для replay", color = Color(0xFFEF9A9A), fontSize = 10.sp)
            return
        }
        StrategyTestBarReplayPlayer(
            points = points,
            thresholds = thresholds,
            chartHeightDp = chartHeightDp,
        )
    }
}

@Composable
private fun StrategyTestBarReplayPlayer(
    points: List<DataPoint>,
    thresholds: DynamicThresholds,
    chartHeightDp: Int,
) {
    val engine = remember(points, thresholds.entry, thresholds.exit) {
        BarReplayEngine(
            ReplayConfig(
                points = points,
                thresholds = thresholds,
                startIndex = Z_SCORE_ROLLING_MIN_BARS.coerceAtMost(points.lastIndex),
            ),
        )
    }
    var cursorTick by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var scrubbing by remember { mutableStateOf(false) }

    fun bump() {
        cursorTick++
    }

    val frame = remember(cursorTick, engine) { engine.frameAtCursor() }
    val windowCandles = remember(frame.cursorIndex, points) {
        barReplayWindowCandles(points, frame.cursorIndex)
    }
    val windowPoints = remember(frame.cursorIndex, points) {
        val range = barReplayVisibleIndexRange(points, frame.cursorIndex)
        if (range.isEmpty()) emptyList()
        else points.subList(range.first, range.last + 1)
    }
    val markers = remember(frame.signalEdgesSoFar, points) {
        barReplaySignalMarkers(frame.signalEdgesSoFar, points)
    }
    val referenceLines = remember(thresholds.entry, thresholds.exit, thresholds.calculatedDate) {
        buildZScoreReferenceLines(thresholds, desktopStyle = true)
    }

    LaunchedEffect(playing, speed, scrubbing) {
        if (!playing || scrubbing) return@LaunchedEffect
        while (playing) {
            delay(barReplayDelayMs(speed))
            if (!playing || scrubbing) break
            engine.speed = speed
            val next = engine.stepForward()
            bump()
            if (next == null) {
                playing = false
                break
            }
        }
    }

    DisposableEffect(engine) {
        onDispose {
            engine.pause()
            playing = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ReplayStatusRow(frame = frame, thresholds = thresholds)
        TradingViewReplayChart(
            candles = windowCandles,
            displayPoints = windowPoints,
            pointMarkers = markers,
            referenceLines = referenceLines,
            playing = playing,
            chartHeightDp = chartHeightDp,
            modifier = Modifier.fillMaxWidth(),
        )
        ReplayControlBar(
            playing = playing,
            speed = speed,
            progress = engine.progressFraction,
            onPlayPause = {
                if (playing) {
                    engine.pause()
                    playing = false
                } else {
                    engine.speed = speed
                    engine.play()
                    playing = true
                }
                bump()
            },
            onStepBack = {
                playing = false
                engine.stepBackward()
                bump()
            },
            onStepForward = {
                playing = false
                engine.pause()
                engine.stepForward()
                bump()
            },
            onSeekStart = {
                playing = false
                engine.seekToStart()
                bump()
            },
            onSeekEnd = {
                playing = false
                engine.seekToEnd()
                bump()
            },
            onSpeedChange = { s ->
                speed = s
                engine.speed = s
            },
            onScrubStart = {
                scrubbing = true
                playing = false
                engine.pause()
            },
            onScrub = { frac ->
                val minC = Z_SCORE_ROLLING_MIN_BARS.coerceAtMost(engine.lastIndex)
                val span = (engine.lastIndex - minC).coerceAtLeast(0)
                val target = (minC + frac * span).roundToInt()
                engine.seekTo(target)
                bump()
            },
            onScrubEnd = { scrubbing = false },
        )
    }
}

@Composable
internal fun ReplayStatusRow(
    frame: ReplayFrame,
    thresholds: DynamicThresholds,
) {
    val z = frame.visiblePoints.lastOrNull()?.zScore
    val zText = z?.let { String.format(Locale.US, "%+.2f", it) } ?: "—"
    val pos = when (frame.position) {
        ZStrategyPosition.Flat -> "Flat"
        ZStrategyPosition.Long -> "Long"
        ZStrategyPosition.Short -> "Short"
    }
    Text(
        text = buildString {
            append(frame.barLabel.ifBlank { "—" })
            append(" · Z ")
            append(zText)
            append(" · ")
            append(pos)
            append(" · вх±")
            append(String.format(Locale.US, "%.2f", thresholds.entry))
            append(" вых±")
            append(String.format(Locale.US, "%.2f", thresholds.exit))
            if (frame.signalEdgesSoFar.isNotEmpty()) {
                append(" · сигн. ")
                append(frame.signalEdgesSoFar.size)
            }
        },
        color = Color(0xFFBDBDBD),
        fontSize = 9.sp,
        maxLines = 2,
    )
}

@Composable
internal fun ReplayControlBar(
    playing: Boolean,
    speed: Float,
    progress: Float,
    onPlayPause: () -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = onSeekStart, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "В начало", tint = Color(0xFFE0E0E0))
            }
            IconButton(onClick = onStepBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.FastRewind, contentDescription = "−1 бар", tint = Color(0xFFE0E0E0))
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Пауза" else "Play",
                    tint = Color(0xFF81D4FA),
                )
            }
            IconButton(onClick = onStepForward, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.FastForward, contentDescription = "+1 бар", tint = Color(0xFFE0E0E0))
            }
            IconButton(onClick = onSeekEnd, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "В конец", tint = Color(0xFFE0E0E0))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (s in BAR_REPLAY_SPEEDS) {
                StrategyTestOptionChip(
                    label = formatReplaySpeed(s),
                    selected = kotlin.math.abs(speed - s) < 0.01f,
                    onClick = { onSpeedChange(s) },
                    modifier = Modifier.weight(1f),
                    selectedColor = Color(0xFF1565C0),
                )
            }
        }
        var sliderValue by remember(progress) { mutableFloatStateOf(progress) }
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                onScrubStart()
                onScrub(it)
            },
            onValueChangeFinished = onScrubEnd,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF81D4FA),
                activeTrackColor = Color(0xFF1565C0),
                inactiveTrackColor = Color(0xFF424242),
            ),
        )
    }
}

internal fun formatReplaySpeed(speed: Float): String =
    if (speed < 1f) String.format(Locale.US, "%.1f×", speed)
    else String.format(Locale.US, "%.0f×", speed)

/** JSON для лёгкого setReplayCursor (окно ~30д уже нарезано). */
internal fun buildTradingViewReplayCursorJson(
    candles: List<CandlePoint>,
    displayPoints: List<DataPoint>,
    referenceLines: List<ChartReferenceLine>,
    pointMarkers: List<ChartPointMarker>,
    playing: Boolean = false,
): String {
    val candleArr = JSONArray()
    val seen = linkedSetOf<Long>()
    for (c in candles) {
        val timeSec = m15CandleLabelToUnixSec(c.label)
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
                .put("color", composeColorToHex(hl.color))
                .put("title", hl.label),
        )
    }
    val markers = JSONArray()
    val candleTimesSec = candles.map { m15CandleLabelToUnixSec(it.label) }
    val candleTimeSet = candleTimesSec.toSet()
    fun snap(timeSec: Long): Long? {
        if (timeSec in candleTimeSet) return timeSec
        if (candleTimesSec.isEmpty()) return null
        return candleTimesSec.minByOrNull { kotlin.math.abs(it - timeSec) }
    }
    for (m in pointMarkers.filter { !it.badgeText.isNullOrBlank() }) {
        val barTimeSec = resolveTradingViewMarkerBarTimeSec(m, displayPoints, candles) ?: continue
        val tv = tradingViewMarkerFromChartMarker(m, barTimeSec)
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
    return JSONObject()
        .put("candles", candleArr)
        .put("hlines", hlines)
        .put("markers", markers)
        .put("trades", JSONArray())
        .put("windowWidth", 1.0)
        .put("playing", playing)
        .toString()
}

private fun pushReplayCursor(webView: WebView, payloadJson: String) {
    val b64 = Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    webView.evaluateJavascript("window.setReplayCursorFromBase64('$b64')", null)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TradingViewReplayChart(
    candles: List<CandlePoint>,
    displayPoints: List<DataPoint>,
    pointMarkers: List<ChartPointMarker>,
    referenceLines: List<ChartReferenceLine>,
    playing: Boolean,
    chartHeightDp: Int,
    modifier: Modifier = Modifier,
) {
    if (candles.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(chartHeightDp.dp)
                .background(Color(0xFF131722), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Нет свечей для replay", color = Color(0xFF757575), fontSize = 10.sp)
        }
        return
    }
    val context = LocalContext.current
    val html = remember { loadTradingViewChartHtml(context.applicationContext) }
    var pageReady by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val payload = remember(candles, displayPoints, pointMarkers, referenceLines, playing) {
        buildTradingViewReplayCursorJson(
            candles = candles,
            displayPoints = displayPoints,
            referenceLines = referenceLines,
            pointMarkers = pointMarkers,
            playing = playing,
        )
    }

    fun deliver() {
        val view = webViewRef ?: return
        if (!pageReady) return
        pushReplayCursor(view, payload)
        view.evaluateJavascript("window.setReplayPlaying(${if (playing) "true" else "false"})", null)
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
                        view?.evaluateJavascript(
                            "window.moexChartPageReady && window.moexChartPageReady()",
                            null,
                        )
                    }
                }
                loadDataWithBaseURL(TRADINGVIEW_ASSET_BASE, html, "text/html", "UTF-8", null)
            }
        },
        update = { webView -> webViewRef = webView },
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeightDp.dp)
            .background(Color(0xFF131722), RoundedCornerShape(8.dp)),
    )

    LaunchedEffect(pageReady, payload) {
        if (pageReady) deliver()
    }
}
