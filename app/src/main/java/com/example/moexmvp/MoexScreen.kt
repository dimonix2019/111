package com.example.moexmvp

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    val configuration = LocalConfiguration.current
    val landscapeZChartFullscreen =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            (screen.selectedTab == MainTab.Markets || screen.selectedTab == MainTab.Portfolio)

    val chartSuccess = (screen.state as? UiState.Success) ?: screen.lastGoodMarkets
    val staleMarkets = screen.marketsStale || (screen.realtimeError != null && chartSuccess != null)
    val marketsM15SimPoints = remember(screen.marketsM15Points, screen.marketsZChartPeriod) {
        filterM15PointsForMarketsPeriod(screen.marketsM15Points, screen.marketsZChartPeriod)
    }
    val marketsChartSeries = rememberM15ZChartSeries(marketsM15SimPoints)
    val marketsM15ChartPoints = marketsChartSeries.first
    val marketsZScoreCandles = marketsChartSeries.second

    val portfolioM15SimPoints = remember(screen.portfolioM15Points, screen.marketsZChartPeriod) {
        filterM15PointsForMarketsPeriod(screen.portfolioM15Points, screen.marketsZChartPeriod)
    }
    val portfolioPortraitM15SimPoints = remember(screen.portfolioM15Points) {
        filterM15PointsForMarketsPeriod(screen.portfolioM15Points, Period.OneDay)
    }
    val portfolioChartSeries = rememberM15ZChartSeries(portfolioM15SimPoints)
    val portfolioZChartPoints = portfolioChartSeries.first
    val portfolioZScoreCandles = portfolioChartSeries.second
    val portfolioPortraitChartSeries = rememberM15ZChartSeries(portfolioPortraitM15SimPoints)
    val portfolioPortraitZChartPoints = portfolioPortraitChartSeries.first
    val portfolioPortraitZScoreCandles = portfolioPortraitChartSeries.second
    val portfolioLandscapeChartThresholds = remember(
        screen.realTradeEntryThreshold,
        screen.realTradeExitThreshold
    ) {
        portfolioChartZThresholds(screen.realTradeEntryThreshold, screen.realTradeExitThreshold)
    }
    val strategyTestM15Full = remember(screen.portfolioM15Points, screen.marketsM15Points) {
        when {
            screen.portfolioM15Points.size >= 2 -> screen.portfolioM15Points
            screen.marketsM15Points.size >= 2 -> screen.marketsM15Points
            else -> emptyList()
        }
    }
    val strategyTestM15SimPoints = remember(strategyTestM15Full) {
        val tail = filterM15PointsForMarketsPeriod(strategyTestM15Full, Period.OneMonth)
        if (tail.size >= 2) tail else strategyTestM15Full
    }
    val strategyTestChartSeries = rememberM15ZChartSeries(strategyTestM15SimPoints)
    val strategyTestM15ChartPoints = strategyTestChartSeries.first
    val strategyTestZScoreCandles = strategyTestChartSeries.second
    val strategyTestChartThresholds = remember(
        screen.strategyTestEntryThreshold,
        screen.strategyTestExitThreshold,
        screen.dynamicThresholds
    ) {
        DynamicThresholds(
            entry = (screen.strategyTestEntryThreshold ?: screen.dynamicThresholds.entry)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
            exit = (screen.strategyTestExitThreshold ?: screen.dynamicThresholds.exit)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
            calculatedDate = screen.dynamicThresholds.calculatedDate
        )
    }
    val strategyTestZInitialWindow = remember(strategyTestM15ChartPoints) {
        chartInitialWindowForLastCalendarDays(
            strategyTestM15ChartPoints,
            visibleDays = STRATEGY_TEST_Z_CHART_VISIBLE_DAYS
        )
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
        marketsM15SimPoints,
        marketsChartThresholds.entry,
        marketsChartThresholds.exit,
        screen.portfolioLeverage,
        screen.portfolioCommissionPercent,
        screen.marketsZChartPeriod
    ) {
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

    MoexScreenEffects(screen, scope)

    AppUpdateBackgroundChecker()

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
            screen.dataLoadProgress?.takeIf { it.active }?.let { progress ->
                DataLoadProgressCard(
                    progress = progress,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
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
                strategyTestTradeItems = strategyTestTradeItems,
                strategyTestM15ChartPoints = strategyTestM15ChartPoints,
                strategyTestZScoreCandles = strategyTestZScoreCandles,
                strategyTestChartThresholds = strategyTestChartThresholds,
                strategyTestZInitialWindow = strategyTestZInitialWindow
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
