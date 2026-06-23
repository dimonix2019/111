package com.example.moexmvp

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun MoexScreen() {
    val context = LocalContext.current
    val screen = remember(context) { MoexScreenState(context) }
    val scope = rememberCoroutineScope()

    DisposableEffect(screen) {
        MoexMemoryPressure.registerTrimHandler { level ->
            screen.trimMemoryCaches(level)
            MoexDiagnostics.log(
                context,
                "mem",
                "trimCaches level=$level tab=${screen.selectedTab.label} usedAfter=${Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) }}MB",
            )
        }
        onDispose { MoexMemoryPressure.unregisterTrimHandler() }
    }

    val configuration = LocalConfiguration.current
    val landscapeZChartFullscreen =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            (screen.selectedTab == MainTab.Markets || screen.selectedTab == MainTab.StrategyTest)

    val chartSuccess = (screen.state as? UiState.Success) ?: screen.lastGoodMarkets
    val staleMarkets = screen.marketsStale || (screen.realtimeError != null && chartSuccess != null)
    val onMarketsTab = screen.selectedTab == MainTab.Markets
    val onStrategyTestTab = screen.selectedTab == MainTab.StrategyTest

    val marketsM15SimPoints = remember(
        screen.marketsM15DataEpoch,
        screen.marketsM15ChartOverlayEpoch,
        screen.marketsM15SqliteChartEpoch,
        screen.marketsZChartPeriod,
        onMarketsTab,
    ) {
        if (!onMarketsTab) return@remember emptyList<DataPoint>()
        screen.buildMarketsM15PointsForZChart(screen.marketsZChartPeriod)
    }
    val marketsChartBase = if (onMarketsTab) {
        rememberM15ZChartSeries(
            simPoints = marketsM15SimPoints,
            dataEpoch = screen.marketsM15DataEpoch + screen.marketsM15ChartOverlayEpoch,
        )
    } else {
        emptyList<DataPoint>() to emptyList()
    }
    val marketsChartSeries = remember(marketsChartBase, screen.marketsLiveZScore, screen.marketsLiveZBarAt) {
        applyLiveZToM15ChartSeries(
            marketsChartBase.first,
            marketsChartBase.second,
            screen.marketsLiveZScore,
            screen.marketsLiveZBarAt,
        )
    }
    val marketsFormingBarHint = remember(
        screen.marketsLiveZScore,
        screen.marketsLiveZBarAt,
        marketsChartSeries,
        marketsChartBase,
    ) {
        resolveMarketsFormingBarHint(
            liveZ = screen.marketsLiveZScore,
            liveBarAt = screen.marketsLiveZBarAt,
            patchedPoints = marketsChartSeries.first,
            basePoints = marketsChartBase.first,
        )
    }
    val marketsFormingBarHintText = remember(marketsFormingBarHint) {
        marketsFormingBarHint?.let(::formatMarketsFormingBarHint)
    }
    val marketsM15ChartPoints = marketsChartSeries.first
    val marketsZScoreCandles = marketsChartSeries.second

    val strategyTestM15SimPoints = remember(
        screen.strategyTestM15ChartTail,
        screen.strategyTestLastSimKey,
        onStrategyTestTab,
    ) {
        if (!onStrategyTestTab) return@remember emptyList()
        val full = screen.strategyTestM15SessionCache
        if (full.size >= 2) full else screen.strategyTestM15ChartTail
    }
    val strategyTestChartSeries = if (onStrategyTestTab) {
        rememberM15ZChartSeries(strategyTestM15SimPoints)
    } else {
        emptyList<DataPoint>() to emptyList()
    }
    val strategyTestM15ChartPoints = strategyTestChartSeries.first
    val strategyTestZScoreCandles = strategyTestChartSeries.second
    val strategyTestChartThresholds = remember(
        screen.strategyTestUsePortfolioThresholds,
        screen.realTradeEntryThreshold,
        screen.realTradeExitThreshold,
        screen.strategyTestEntryThreshold,
        screen.strategyTestExitThreshold,
        screen.dynamicThresholds.entry,
        screen.dynamicThresholds.exit,
        screen.dynamicThresholds.calculatedDate,
    ) {
        val sim = screen.resolveStrategyTestSimThresholds()
        DynamicThresholds(
            entry = sim.entry,
            exit = sim.exit,
            calculatedDate = screen.dynamicThresholds.calculatedDate,
        )
    }
    // Хвост ~30 календарных дней — как на «Рынок»; иначе TradingView fitContent() на ~1200 барах
    // делает маркеры сделок невидимыми (sub-pixel). Pinch-zoom покажет всю историю.
    val strategyTestZInitialWindow = remember(strategyTestM15ChartPoints) {
        chartInitialWindowForLastCalendarDays(
            strategyTestM15ChartPoints,
            STRATEGY_TEST_Z_CHART_VISIBLE_DAYS,
        )
    }
    val strategyTestTradeItems = remember(screen.strategyTestPortfolioMetrics) {
        buildStrategyTestTradeListFromSimulation(
            screen.strategyTestPortfolioMetrics?.closedTrades.orEmpty()
        )
    }
    val strategyTestTradeItemsForDisplay = remember(
        strategyTestTradeItems,
        screen.strategyTestTradeRiskAssessments,
        screen.strategyTestExcludeRedZone,
    ) {
        if (!screen.strategyTestExcludeRedZone) {
            strategyTestTradeItems
        } else {
            filterStrategyTestTradeItemsExcludingRedZone(
                strategyTestTradeItems,
                screen.strategyTestTradeRiskAssessments,
            )
        }
    }
    val strategyTestChartTradeSegmentsForDisplay = remember(
        strategyTestM15ChartPoints,
        strategyTestZScoreCandles,
        strategyTestTradeItemsForDisplay,
        screen.strategyTestPortfolioMetrics?.openPosition,
        screen.strategyTestExcludeRedZone,
    ) {
        if (strategyTestM15ChartPoints.size < 2) {
            emptyList()
        } else {
            buildTradingViewTradeSegmentsFromStrategyTest(
                tradeItems = strategyTestTradeItemsForDisplay,
                displayPoints = strategyTestM15ChartPoints,
                candles = strategyTestZScoreCandles,
                openPosition = if (screen.strategyTestExcludeRedZone) null
                else screen.strategyTestPortfolioMetrics?.openPosition,
            )
        }
    }
    /** Маркеры как на «Рынок»: ChartPointMarker + remap на downsample-ряд графика. */
    val strategyTestChartMarkersForDisplay = remember(
        strategyTestM15SimPoints,
        strategyTestM15ChartPoints,
        strategyTestTradeItemsForDisplay,
        screen.strategyTestPortfolioMetrics?.openPosition,
        screen.strategyTestChartMarkers,
        screen.strategyTestExcludeRedZone,
    ) {
        if (strategyTestM15ChartPoints.size < 2) {
            emptyList()
        } else {
            val sourceMarkers = when {
                strategyTestTradeItemsForDisplay.isNotEmpty() ||
                    (!screen.strategyTestExcludeRedZone &&
                        screen.strategyTestPortfolioMetrics?.openPosition != null) ->
                    buildZScoreMarkersFromStrategyTestTrades(
                        points = strategyTestM15SimPoints,
                        tradeItems = strategyTestTradeItemsForDisplay,
                        openPosition = if (screen.strategyTestExcludeRedZone) null
                        else screen.strategyTestPortfolioMetrics?.openPosition,
                    )
                screen.strategyTestChartMarkers.isNotEmpty() ->
                    screen.strategyTestChartMarkers
                else -> emptyList()
            }
            if (sourceMarkers.isEmpty()) {
                emptyList()
            } else {
                remapChartMarkersToDisplaySeries(
                    sourcePoints = strategyTestM15SimPoints,
                    displayPoints = strategyTestM15ChartPoints,
                    markers = sourceMarkers,
                )
            }
        }
    }
    val marketsChartThresholds = remember(
        screen.realTradeEntryThreshold,
        screen.realTradeExitThreshold
    ) {
        portfolioChartZThresholds(screen.realTradeEntryThreshold, screen.realTradeExitThreshold)
    }

    LaunchedEffect(
        onMarketsTab,
        marketsM15SimPoints,
        marketsChartThresholds.entry,
        marketsChartThresholds.exit,
        screen.portfolioLeverage,
        screen.portfolioCommissionPercent,
        screen.marketsZChartPeriod
    ) {
        if (!onMarketsTab) {
            screen.marketsZStrategyTapMetrics = null
            return@LaunchedEffect
        }
        delay(250)
        screen.marketsZStrategyTapMetrics = withContext(Dispatchers.Default) {
            val pts = marketsM15SimPoints
            if (pts.size < 2) return@withContext null
            buildZStrategyPortfolioMetrics(
                points = pts,
                thresholds = marketsChartThresholds,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = screen.portfolioLeverage,
                commissionPercentPerSide = screen.portfolioCommissionPercent,
                periodDescription = "${screen.marketsZChartPeriod.label} · тап Z",
                compoundReturns = false
            )
        }
    }

    val dataSourceLabel = when {
        staleMarkets -> MarketsDataSource.OfflineStale
        chartSuccess?.marketsDataSource == MarketsDataSource.FifteenMinuteCache ->
            MarketsDataSource.FifteenMinuteCache
        else -> MarketsDataSource.Network
    }

    LaunchedEffect(onMarketsTab, screen.marketsM15DataEpoch) {
        if (!onMarketsTab) return@LaunchedEffect
        screen.refreshMarketsM15SqliteChartCache(reason = "tab_open")
    }

    LaunchedEffect(onMarketsTab, screen.marketsIntraday1mEpoch, screen.marketsM15DataEpoch) {
        if (!onMarketsTab) return@LaunchedEffect
        if (screen.marketsIntraday1mTatn.isEmpty() || screen.marketsIntraday1mTatnp.isEmpty()) return@LaunchedEffect
        screen.refreshMarketsM15TodayChartOverlay()
    }

    LaunchedEffect(
        onMarketsTab,
        screen.marketsLiveZBarAt,
        marketsChartBase.first.lastOrNull()?.tradeDate,
    ) {
        if (!onMarketsTab) return@LaunchedEffect
        val lastLabel = marketsChartBase.first.lastOrNull()?.tradeDate
        val liveAt = screen.marketsLiveZBarAt
        if (marketsChartLiveBarGapNeedsM15Catchup(lastLabel, liveAt) ||
            m15SeriesHasIntradayTradingGap(screen.marketsM15Source())
        ) {
            screen.refreshMarketsM15SqliteChartCache(reason = "chart_gap")
            screen.scheduleMarketsM15MoexCatchup(scope, reason = "chart_gap", debounceMs = 0L)
        }
    }

    MoexScreenEffects(screen, scope)

    AppUpdateBackgroundChecker(
        onUpdateFound = { remote ->
            if (screen.pendingAppUpdate == null || screen.pendingAppUpdate!!.versionCode < remote.versionCode) {
                screen.pendingAppUpdate = remote
            }
        },
    )

    AppUpdateDialogHost(
        pendingUpdate = screen.pendingAppUpdate,
        onDismiss = { update ->
            saveDismissedAppUpdateVersionCode(context, update.versionCode)
            screen.pendingAppUpdate = null
        },
        onInstalledOffer = { screen.pendingAppUpdate = null }
    )

    MoexScreenDialogs(screen, scope)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(if (landscapeZChartFullscreen) 0.dp else 12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!landscapeZChartFullscreen) {
                MainTabSelector(
                    selected = screen.selectedTab,
                    onSelect = { screen.selectedTab = it }
                )
                if (!screen.sandboxSpreadAutoExecute) {
                    MoexScreenVirtualTradeCard(screen, scope, Modifier.padding(top = 6.dp))
                }
            }
            when (screen.selectedTab) {
            MainTab.Journal -> MoexScreenTabJournal(screen, scope, Modifier.weight(1f).fillMaxSize())
            MainTab.About -> MoexScreenTabAbout(screen, scope, Modifier.weight(1f).fillMaxSize())
            MainTab.Sandbox -> MoexScreenTabSandbox(screen, scope, Modifier.weight(1f).fillMaxSize())
            MainTab.Portfolio -> MoexScreenTabPortfolio(
                screen = screen,
                scope = scope,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
            MainTab.StrategyTest -> MoexScreenTabStrategyTest(
                screen = screen,
                scope = scope,
                modifier = Modifier.weight(1f).fillMaxSize(),
                landscapeZChartFullscreen = landscapeZChartFullscreen,
                strategyTestTradeItems = strategyTestTradeItems,
                strategyTestM15ChartPoints = strategyTestM15ChartPoints,
                strategyTestZScoreCandles = strategyTestZScoreCandles,
                strategyTestChartThresholds = strategyTestChartThresholds,
                strategyTestChartTradeSegments = strategyTestChartTradeSegmentsForDisplay,
                strategyTestChartMarkers = strategyTestChartMarkersForDisplay,
                strategyTestOpenPosition = screen.strategyTestPortfolioMetrics?.openPosition,
                strategyTestZInitialWindow = strategyTestZInitialWindow
            )
            MainTab.Markets -> MoexScreenTabMarkets(
                screen = screen,
                scope = scope,
                modifier = Modifier.weight(1f).fillMaxSize(),
                landscapeZChartFullscreen = landscapeZChartFullscreen,
                chartSuccess = chartSuccess,
                staleMarkets = staleMarkets,
                marketsM15SourcePoints = marketsM15SimPoints,
                marketsM15ChartPoints = marketsM15ChartPoints,
                marketsZScoreCandles = marketsZScoreCandles,
                marketsChartThresholds = marketsChartThresholds,
                marketsZStrategyTapMetrics = screen.marketsZStrategyTapMetrics,
                dataSourceLabel = dataSourceLabel,
                marketsFormingBarHint = marketsFormingBarHint,
                marketsFormingBarHintText = marketsFormingBarHintText,
            )
            }
        }
    }
}
