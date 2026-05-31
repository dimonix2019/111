package com.example.moexmvp

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
    val configuration = LocalConfiguration.current
    val landscapeZChartFullscreen =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            (screen.selectedTab == MainTab.Markets || screen.selectedTab == MainTab.Portfolio)

    val chartSuccess = (screen.state as? UiState.Success) ?: screen.lastGoodMarkets
    val staleMarkets = screen.marketsStale || (screen.realtimeError != null && chartSuccess != null)
    val marketsM15ChartPoints = remember(screen.marketsM15Points, screen.selectedPeriod) {
        downsampleDataPointsForChart(
            filterM15PointsForMarketsPeriod(screen.marketsM15Points, screen.selectedPeriod)
        )
    }
    var marketsZScoreCandles by remember { mutableStateOf<List<CandlePoint>>(emptyList()) }
    val ohlcLookbackDays = remember(screen.selectedPeriod) {
        when (screen.selectedPeriod) {
            Period.OneDay -> 3L
            Period.OneWeek -> 10L
            Period.OneMonth -> 14L
            else -> CHART_INTRABAR_OHLC_LOOKBACK_DAYS
        }
    }
    LaunchedEffect(marketsM15ChartPoints, ohlcLookbackDays) {
        if (marketsM15ChartPoints.isEmpty()) {
            marketsZScoreCandles = emptyList()
            return@LaunchedEffect
        }
        marketsZScoreCandles = buildZScoreCandlesFromM15Points(marketsM15ChartPoints)
        if (marketsM15ChartPoints.size < 2) return@LaunchedEffect
        delay(350)
        runCatching {
            withContext(Dispatchers.IO) {
                buildZScoreCandlesOhlcAnchoredToM15Series(
                    marketsM15ChartPoints,
                    intrabarLookbackDays = ohlcLookbackDays,
                )
            }
        }.getOrNull()?.let { marketsZScoreCandles = it }
    }
    val portfolioZChartPoints = remember(screen.portfolioM15Points, screen.selectedPeriod) {
        downsampleDataPointsForChart(
            filterM15PointsForMarketsPeriod(screen.portfolioM15Points, screen.selectedPeriod)
        )
    }
    val portfolioPortraitZChartPoints = remember(screen.portfolioM15Points) {
        downsampleDataPointsForChart(
            filterM15PointsForMarketsPeriod(screen.portfolioM15Points, Period.OneDay)
        )
    }
    val portfolioZScoreCandles = remember(portfolioZChartPoints) {
        buildZScoreCandlesFromM15Points(portfolioZChartPoints)
    }
    val portfolioPortraitZScoreCandles = remember(portfolioPortraitZChartPoints) {
        buildZScoreCandlesFromM15Points(portfolioPortraitZChartPoints)
    }
    val portfolioLandscapeChartThresholds = remember(
        screen.realTradeEntryThreshold,
        screen.realTradeExitThreshold
    ) {
        portfolioChartZThresholds(screen.realTradeEntryThreshold, screen.realTradeExitThreshold)
    }
    val strategyTestTradeItems = remember(screen.strategyTestPortfolioMetrics) {
        buildStrategyTestTradeListFromSimulation(
            screen.strategyTestPortfolioMetrics?.closedTrades.orEmpty()
        )
    }
    val marketsChartThresholds = remember(
        screen.realTradeEntryThreshold,
        screen.realTradeExitThreshold
    ) {
        portfolioChartZThresholds(screen.realTradeEntryThreshold, screen.realTradeExitThreshold)
    }

    LaunchedEffect(
        marketsM15ChartPoints,
        marketsChartThresholds.entry,
        marketsChartThresholds.exit,
        screen.portfolioLeverage,
        screen.portfolioCommissionPercent,
        screen.selectedPeriod
    ) {
        screen.marketsZStrategyTapMetrics = withContext(Dispatchers.Default) {
            val pts = marketsM15ChartPoints
            if (pts.size < 2) return@withContext null
            buildZStrategyPortfolioMetrics(
                points = pts,
                thresholds = marketsChartThresholds,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = screen.portfolioLeverage,
                commissionPercentPerSide = screen.portfolioCommissionPercent,
                periodDescription = "${screen.selectedPeriod.label} · тап Z",
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

    MoexScreenEffects(screen, scope)

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val remote = fetchRemoteAppUpdate()
            if (remote == null || !shouldOfferAppUpdateUi(remote, context)) {
                screen.pendingAppUpdate = null
            }
        }
    }

    AppUpdateChecker { remote ->
        if (!shouldOfferAppUpdateUi(remote, context)) return@AppUpdateChecker
        if (screen.pendingAppUpdate == null || screen.pendingAppUpdate!!.versionCode < remote.versionCode) {
            screen.pendingAppUpdate = remote
        }
    }

    AppUpdateDialogHost(
        pendingUpdate = screen.pendingAppUpdate,
        onDismiss = { update ->
            saveDismissedAppUpdateVersionCode(context, update.versionCode)
            screen.pendingAppUpdate = null
        },
        onInstalledOffer = { screen.pendingAppUpdate = null }
    )

    MoexScreenDialogs(screen, scope)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(if (landscapeZChartFullscreen) 0.dp else 12.dp)
    ) {
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
                modifier = Modifier.weight(1f),
                landscapeZChartFullscreen = landscapeZChartFullscreen,
                portfolioZChartPoints = portfolioZChartPoints,
                portfolioZScoreCandles = portfolioZScoreCandles,
                portfolioPortraitZScoreCandles = portfolioPortraitZScoreCandles,
                portfolioLandscapeChartThresholds = portfolioLandscapeChartThresholds,
                portfolioPortraitZChartPoints = portfolioPortraitZChartPoints
            )
            MainTab.StrategyTest -> MoexScreenTabStrategyTest(
                screen = screen,
                scope = scope,
                modifier = Modifier.weight(1f),
                strategyTestTradeItems = strategyTestTradeItems
            )
            MainTab.Markets -> MoexScreenTabMarkets(
                screen = screen,
                scope = scope,
                modifier = Modifier.weight(1f),
                landscapeZChartFullscreen = landscapeZChartFullscreen,
                chartSuccess = chartSuccess,
                staleMarkets = staleMarkets,
                marketsM15ChartPoints = marketsM15ChartPoints,
                marketsZScoreCandles = marketsZScoreCandles,
                marketsChartThresholds = marketsChartThresholds,
                marketsZStrategyTapMetrics = screen.marketsZStrategyTapMetrics,
                dataSourceLabel = dataSourceLabel,
                todayPnlHint = screen.todayPnlHint
            )
        }
    }
}
