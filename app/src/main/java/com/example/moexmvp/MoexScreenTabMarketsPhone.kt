package com.example.moexmvp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.Locale

private const val MARKETS_PHONE_SPREAD_POLL_MS = 30_000L
private const val MARKETS_PHONE_CHART_HEIGHT_DP = 280

private data class MarketsPhoneChartState(
    val overlay: ZChartPortfolioOverlay,
    val openSide: ZStrategyPosition?,
    val openEntrySpread: Double?,
    val openDepositRub: Double?,
    val openNotionalRub: Double?,
)

/**
 * Зоны спреда как на web Trade Desk (`SPREAD_REGIME_BAND_COLORS` в trade.js):
 * Long 3.2…4.0 cyan, переход 4.0…5.8 коричневая, Short 5.8…6.1 бордово-красная.
 * Alpha: 0.20 / 0.20 / 0.22.
 */
private val MARKETS_PHONE_SPREAD_ZONE_FILLS = listOf(
    ChartZoneFill(DEFAULT_SPREAD_ENTER_NARROW, 4.0, Color(0x3300BCD4)), // rgba(0,188,212,0.20)
    ChartZoneFill(4.0, 5.8, Color(0x33B76E2D)), // rgba(183,110,45,0.20)
    ChartZoneFill(5.8, DEFAULT_SPREAD_ENTER_WIDE, Color(0x38880E4F)), // rgba(136,14,79,0.22)
)

/** Псевдо‑точки для привязки маркеров сделок к свечам спреда %. */
internal fun spreadCandlesToMarkerPoints(candles: List<CandlePoint>): List<DataPoint> =
    candles.mapNotNull { c ->
        val ts = parsePortfolioExecutionTableMsk(c.label)
            ?: runCatching {
                LocalDateTime.parse(c.label.trim(), portfolio15mLabelFormatter)
                    .atZone(moexZoneId)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
            ?: return@mapNotNull null
        DataPoint(
            timestampMillis = ts,
            tradeDate = c.label,
            tatnClose = 0.0,
            tatnpClose = 0.0,
            spreadPercent = c.close,
            diff = 0.0,
            zScore = c.close,
        )
    }

/** Телефонная вкладка «Рынок»: TradingView-свечи спреда 1м за текущую неделю. */
@Composable
internal fun MoexScreenTabMarketsPhone(
    screen: MoexScreenState,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    modifier: Modifier,
) {
    var candles by remember { mutableStateOf<List<CandlePoint>>(emptyList()) }
    var lastSpread by remember { mutableStateOf<Double?>(null) }
    var lastBarLabel by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var brokerSide by remember { mutableStateOf(BrokerAccountPrefs.lastSide(screen.context)) }

    LaunchedEffect(screen.selectedTab, screen.activityResumed) {
        if (screen.selectedTab != MainTab.Markets || !screen.activityResumed) return@LaunchedEffect
        while (true) {
            runCatching {
                val snap = withContext(Dispatchers.IO) { fetchMarketsIntraday1mWeek() }
                candles = snap.spreadCandles
                lastSpread = snap.lastSpreadPercent
                maybeNotifySpreadLevelAlerts(screen.context, snap.lastSpreadPercent)
                lastBarLabel = formatIntraday1mLastBarLabel(snap.lastBarMillis)
                    ?: snap.spreadCandles.lastOrNull()?.label
                loadError = null
                loading = false
            }.onFailure { e ->
                loadError = e.message?.take(120) ?: e.javaClass.simpleName
                loading = false
                MoexDiagnostics.logError(screen.context, "markets_phone", e, "week spread fetch")
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    pollBrokerAccountAndNotify(screen.context)
                }
                brokerSide = BrokerAccountPrefs.lastSide(screen.context)
            }
            delay(MARKETS_PHONE_SPREAD_POLL_MS)
            if (screen.selectedTab != MainTab.Markets || !screen.activityResumed) break
        }
    }

    val markerPoints = remember(candles) { spreadCandlesToMarkerPoints(candles) }
    val weekStartMs = remember {
        currentWeekMondayMsk().atStartOfDay(moexZoneId).toInstant().toEpochMilli()
    }
    val chartState by produceState(
        initialValue = MarketsPhoneChartState(
            ZChartPortfolioOverlay(emptyList(), emptyList()),
            null,
            null,
            null,
            null,
        ),
        markerPoints,
        screen.sandboxSpreadExecReload,
        screen.portfolioLeverage,
        screen.portfolioCommissionPercent,
        brokerSide,
    ) {
        if (markerPoints.size < 2) {
            value = MarketsPhoneChartState(
                ZChartPortfolioOverlay(emptyList(), emptyList()),
                null,
                null,
                null,
                null,
            )
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val (opens, closed) = loadPortfolioTradesForZChart(
                context = screen.context.applicationContext,
                points = markerPoints,
                leverage = screen.portfolioLeverage,
                commissionPercentPerSide = screen.portfolioCommissionPercent,
            )
            val weekOpens = opens.filter {
                it.barTimestampMillis >= weekStartMs ||
                    (parsePortfolioExecutionTableMsk(it.entryTimeMsk) ?: 0L) >= weekStartMs
            }
            val weekClosed = closed.filter { row ->
                val entryMs = parsePortfolioExecutionTableMsk(row.entryTimeMsk) ?: 0L
                val exitMs = parsePortfolioExecutionTableMsk(row.exitTimeMsk) ?: 0L
                entryMs >= weekStartMs || exitMs >= weekStartMs
            }
            val spreadMarkers = zScoreChartMarkersFromPortfolioTrades(
                markerPoints,
                weekOpens,
                weekClosed,
            ).map { marker ->
                marker.copy(value = markerPoints.getOrNull(marker.index)?.zScore ?: marker.value)
            }
            val openExec = weekOpens.firstOrNull {
                it.signalType == StrategySignalType.EnterLong ||
                    it.signalType == StrategySignalType.EnterShort
            }
            MarketsPhoneChartState(
                overlay = ZChartPortfolioOverlay(
                    markers = spreadMarkers,
                    tradeSegments = remapTradingViewTradeSegmentsToDisplayValues(
                        buildTradingViewTradeSegments(weekOpens, weekClosed, markerPoints),
                        markerPoints,
                    ),
                ),
                openSide = resolveMarketsSpreadChartOpenSide(weekOpens, brokerSide),
                openEntrySpread = openExec?.entrySpreadPercent,
                openDepositRub = openExec?.entryPortfolioTotalRub?.takeIf { it > 0 }
                    ?: openExec?.entryPortfolioCashRub?.takeIf { it > 0 },
                openNotionalRub = openExec?.executionNotionalRub?.takeIf { it > 0 },
            )
        }
    }
    val chartOverlay = chartState.overlay

    val window = remember(candles.size) {
        intraday1mChartInitialWindow(candles.size, visibleBars = 240)
    }

    val sideLabel = when (brokerSide) {
        ZStrategyPosition.Long -> "Long"
        ZStrategyPosition.Short -> "Short"
        ZStrategyPosition.Flat -> "FLAT"
    }
    val spreadText = lastSpread?.let {
        String.format(Locale("ru", "RU"), "%.2f%%", it)
    } ?: "—"
    val spreadRefs = remember(
        chartState.openSide,
        chartState.openEntrySpread,
        chartState.openDepositRub,
        chartState.openNotionalRub,
    ) {
        buildMarketsSpreadChartReferenceLines(
            openSide = chartState.openSide,
            openEntrySpread = chartState.openEntrySpread,
            depositRub = chartState.openDepositRub,
            notionalRub = chartState.openNotionalRub,
        )
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "S% (последний): $spreadText · позиция: $sideLabel" +
                (lastBarLabel?.let { " · бар $it" } ?: ""),
            color = Color(0xFFE0E0E0),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        if (loading && candles.isEmpty()) {
            Text("Загрузка свечей спреда 1м…", color = Color(0xFF9E9E9E), fontSize = 12.sp)
        }
        loadError?.let {
            Text("Ошибка: $it", color = Color(0xFFFFAB91), fontSize = 12.sp)
        }

        if (candles.isNotEmpty()) {
            TradingViewZScoreChartCard(
                title = "Спред TATN/TATNP, % · 1м · неделя (TradingView)",
                showOhlcLegend = true,
                spreadChart = true,
                candles = candles,
                displayPoints = markerPoints,
                chartHeightDp = MARKETS_PHONE_CHART_HEIGHT_DP,
                referenceLines = spreadRefs,
                zoneFills = MARKETS_PHONE_SPREAD_ZONE_FILLS,
                pointMarkers = chartOverlay.markers,
                tradeSegments = chartOverlay.tradeSegments,
                initialWindowWidth = window.first,
                initialWindowStart = window.second,
            )
        } else if (!loading) {
            Text(
                "Нет 1м баров TATN/TATNP за эту неделю.",
                color = Color(0xFF9E9E9E),
                fontSize = 12.sp,
            )
        }

        Text(
            text = "Pinch — масштаб, drag — панорамирование, шкала справа — масштаб цены. " +
                "Маркеры — входы/выходы сделок за неделю. График обновляется ~30 с.",
            color = Color(0xFF757575),
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
    }
}
