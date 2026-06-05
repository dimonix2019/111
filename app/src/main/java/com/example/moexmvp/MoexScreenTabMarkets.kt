package com.example.moexmvp

import android.app.Activity
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale


@Composable
internal fun MoexScreenTabMarkets(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier,
    landscapeZChartFullscreen: Boolean,
    chartSuccess: UiState.Success?,
    staleMarkets: Boolean,
    marketsM15SourcePoints: List<DataPoint>,
    marketsM15ChartPoints: List<DataPoint>,
    marketsZScoreCandles: List<CandlePoint>,
    marketsChartThresholds: DynamicThresholds,
    marketsZStrategyTapMetrics: PortfolioMetrics?,
    dataSourceLabel: MarketsDataSource,
) {
    val marketsZInitialWindow = remember(marketsM15ChartPoints, screen.marketsZChartPeriod) {
        chartInitialWindowForLastCalendarDays(
            marketsM15ChartPoints,
            visibleDays = calendarDaysForMarketsZChartPeriod(screen.marketsZChartPeriod),
        )
    }
    val marketsZReferenceLines = remember(marketsChartThresholds) {
        buildZScoreReferenceLines(marketsChartThresholds, desktopStyle = true)
    }
    Column(modifier) {
    with(screen) {
        val markerSourcePoints = marketsM15SourcePoints.ifEmpty { marketsM15ChartPoints }
        val zChartPointMarkers by produceState(
            initialValue = emptyList<ChartPointMarker>(),
            markerSourcePoints,
            marketsM15ChartPoints,
            signalEvents,
            sandboxSpreadExecReload,
            portfolioLeverage,
            portfolioCommissionPercent,
        ) {
            if (markerSourcePoints.size < 2) {
                value = buildZScoreSignalMarkersFromEvents(marketsM15ChartPoints, signalEvents)
                return@produceState
            }
            val (opens, closed) = withContext(Dispatchers.IO) {
                loadPortfolioTradesForZChart(
                    context = context.applicationContext,
                    points = markerSourcePoints,
                    leverage = portfolioLeverage,
                    commissionPercentPerSide = portfolioCommissionPercent,
                )
            }
            val sourceMarkers = zScoreChartMarkersWithPortfolioTrades(
                points = markerSourcePoints,
                signalEvents = signalEvents,
                openExecutions = opens,
                closedRows = closed,
            )
            value = if (marketsM15ChartPoints.size < 2) {
                sourceMarkers
            } else {
                remapChartMarkersToDisplaySeries(
                    sourcePoints = markerSourcePoints,
                    displayPoints = marketsM15ChartPoints,
                    markers = sourceMarkers,
                )
            }
        }
                Column(Modifier.fillMaxSize()) {
                    if (!landscapeZChartFullscreen) {
                        val last = marketsM15ChartPoints.lastOrNull()
                            ?: chartSuccess?.points?.lastOrNull()
                        MarketsSummaryStrip(
                            z = last?.zScore,
                            spread = last?.spreadPercent,
                            position = zStrategyPosition,
                            signalsToday = dailySignalLimit.sentCount,
                            signalsMax = DAILY_SIGNAL_MAX_PER_DAY,
                            lastLoadedAt = chartSuccess?.loadedAt ?: "—",
                            dataSource = dataSourceLabel,
                            stale = staleMarkets,
                            onMoexRefresh = {
                                scope.launch { refreshData(showLoading = state !is UiState.Success, launchScope = scope, selectedPeriod = selectedPeriod) }
                            }
                        )
                        if (realtimeError != null && chartSuccess != null) {
                            Text(
                                text = "Предупреждение: $realtimeError",
                                color = Color(0xFFEF9A9A),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    MarketsPullRefreshBox(
                        refreshing = isRefreshing,
                        onRefresh = { scope.launch { refreshData(showLoading = false, launchScope = scope, selectedPeriod = selectedPeriod) } },
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = if (landscapeZChartFullscreen) 0.dp else 8.dp),
                        enabled = !landscapeZChartFullscreen,
                    ) {
                        if (landscapeZChartFullscreen) {
                            val landscapePeriod = marketsZChartPeriod.coerceToMarketsUiPeriod()
                            LandscapeZScoreFullscreenPane(
                                modifier = Modifier.fillMaxSize(),
                                selectedPeriod = landscapePeriod,
                                onPeriodSelect = { period ->
                                    val p = period.coerceToMarketsUiPeriod()
                                    marketsZChartPeriod = p
                                    previousZScoreForAlert = null
                                },
                                candles = marketsZScoreCandles,
                                displayPoints = marketsM15ChartPoints,
                                referenceLines = marketsZReferenceLines,
                                pointMarkers = zChartPointMarkers,
                                initialWindowWidth = marketsZInitialWindow.first,
                                initialWindowStart = marketsZInitialWindow.second,
                                useDesktopStyle = true,
                                displayMode = ChartDisplayMode.Candles,
                                showPlotlyToolbar = true,
                                tradeTapHintFormatter = { idx ->
                                    formatZStrategyTradeTapHint(
                                        idx,
                                        marketsM15ChartPoints,
                                        marketsZStrategyTapMetrics
                                    )
                                },
                                emptyContent = {
                                    when {
                                        marketsZScoreCandles.isNotEmpty() -> Unit
                                        isRefreshing || chartSuccess != null || isDataLoadActive -> LoadingStateWithProgress(
                                            progress = dataLoadProgress,
                                            dataLoadSessions = dataLoadSessions,
                                            statusText = "Загрузка 15м для графика Z…",
                                        )
                                        else -> when (val st = state) {
                                            is UiState.Loading -> LoadingStateWithProgress(
                                                progress = dataLoadProgress,
                                                dataLoadSessions = dataLoadSessions,
                                            )
                                            is UiState.Error -> ErrorState(st.message) {
                                                scope.launch {
                                                    refreshData(
                                                        showLoading = true,
                                                        launchScope = scope,
                                                        selectedPeriod = selectedPeriod
                                                    )
                                                }
                                            }
                                            is UiState.Empty -> EmptyState()
                                            else -> EmptyState()
                                        }
                                    }
                                }
                            )
                        } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                        item {
                            Text(
                                text = "TATN / TATNP (MOEX ISS)",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        item {
                            MarketsPeriodSelector(
                                selected = selectedPeriod,
                                onSelect = { period ->
                                    val p = period.coerceToMarketsUiPeriod()
                                    selectedPeriod = p
                                    marketsZChartPeriod = p
                                    previousZScoreForAlert = null
                                }
                            )
                        }
                        item {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Пороги сделок (Портфель): вход ±%.2f, выход ±%.2f",
                                    marketsChartThresholds.entry,
                                    marketsChartThresholds.exit
                                ),
                                color = Color(0xFFF48FB1),
                                fontSize = 12.sp
                            )
                        }
                        item {
                            val monitorEnabled = remember(bgMonitorToggleEpoch) {
                                SignalForegroundService.isBackgroundMonitorEnabled(context)
                            }
                            val testEntryZ = (strategyTestEntryThreshold ?: dynamicThresholds.entry)
                                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
                            val testExitZ = (strategyTestExitThreshold ?: dynamicThresholds.exit)
                                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val body = String.format(
                                            Locale.US,
                                            "Тест сигнала MOEX. Текущие пороги Z: вход ±%.2f, выход ±%.2f.",
                                            testEntryZ,
                                            testExitZ
                                        )
                                        showZStrategySignalPushNotification(
                                            context = context,
                                            title = "Тест сигнала",
                                            body = body
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF455A64),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.NotificationsActive,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Тест", fontSize = 12.sp)
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (monitorEnabled) {
                                            SignalForegroundService.stop(context)
                                        } else {
                                            SignalForegroundService.start(context)
                                        }
                                        bgMonitorToggleEpoch++
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (monitorEnabled) Color(0xFF2E7D32) else Color(0xFF2196F3),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (monitorEnabled) {
                                                Icons.Filled.PauseCircle
                                            } else {
                                                Icons.Filled.PlayCircle
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (monitorEnabled) "BG вкл" else "BG выкл", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        item {
                            RealtimeControls(
                                enabled = realtimeEnabled,
                                isRefreshing = isRefreshing,
                                onToggle = { realtimeEnabled = !realtimeEnabled }
                            )
                        }
                        val showZCharts = marketsM15ChartPoints.isNotEmpty() && marketsZScoreCandles.isNotEmpty()
                        val waitingM15 = !showZCharts && (isRefreshing || chartSuccess != null || isDataLoadActive)
                        if (showZCharts) {
                            item {
                                TradingViewZScoreChartCard(
                                    title = "Z-score · 15м (TradingView)",
                                    candles = marketsZScoreCandles,
                                    displayPoints = marketsM15ChartPoints,
                                    chartHeightDp = 320,
                                    referenceLines = marketsZReferenceLines,
                                    pointMarkers = zChartPointMarkers,
                                    initialWindowWidth = marketsZInitialWindow.first,
                                    initialWindowStart = marketsZInitialWindow.second,
                                )
                            }
                            item {
                                ChartCard(
                                    title = "Spread 15м = (TATN / TATNP - 1) * 100",
                                    series = listOf(
                                        ChartSeries(
                                            "Spread %",
                                            Color(0xFF69F0AE),
                                            marketsM15ChartPoints.map { it.spreadPercent }
                                        )
                                    ),
                                    labels = marketsM15ChartPoints.map { it.tradeDate },
                                    chartHeightDp = 208,
                                    rightAxisPercentBase = spreadPercentBaseForChartRightAxis(marketsM15ChartPoints),
                                    yScale = YAxisScale.Auto,
                                    showLegend = false,
                                    enableZoomPan = false,
                                    markerScale = 1f,
                                    showZoomHint = false,
                                    m15TimeLabels = true,
                                    xLabelStyle = ChartXLabelStyleHorizontal,
                                )
                            }
                        } else if (waitingM15) {
                            item {
                                LoadingStateWithProgress(
                                    progress = dataLoadProgress,
                                    dataLoadSessions = dataLoadSessions,
                                    statusText = "Загрузка 15м данных для графика Z…",
                                )
                            }
                        } else {
                            when (val st = state) {
                                is UiState.Loading -> item {
                                    LoadingStateWithProgress(
                                        progress = dataLoadProgress,
                                        dataLoadSessions = dataLoadSessions,
                                    )
                                }
                                is UiState.Error -> item {
                                    ErrorState(st.message) {
                                        scope.launch { refreshData(showLoading = true, launchScope = scope, selectedPeriod = selectedPeriod) }
                                    }
                                }
                                is UiState.Empty -> item { EmptyState() }
                                else -> item {
                                    Text(
                                        text = "Нет 15м данных. Потяните вниз для обновления.",
                                        color = Color(0xFFBDBDBD),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                        }
                    }
                }
    }
    }
}
