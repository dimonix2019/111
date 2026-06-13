package com.example.moexmvp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


@Composable
internal fun MoexScreenTabStrategyTest(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier,
    landscapeZChartFullscreen: Boolean,
    strategyTestTradeItems: List<StrategyTestTradeItem>,
    strategyTestM15ChartPoints: List<DataPoint>,
    strategyTestZScoreCandles: List<CandlePoint>,
    strategyTestChartThresholds: DynamicThresholds,
    strategyTestChartTradeSegments: List<TradingViewTradeSegment>,
    strategyTestChartMarkers: List<ChartPointMarker> = emptyList(),
    strategyTestOpenPosition: PortfolioOpenPosition?,
    strategyTestZInitialWindow: Pair<Float, Float>,
) {
    val zReferenceLines = remember(strategyTestChartThresholds) {
        buildZScoreReferenceLines(strategyTestChartThresholds, desktopStyle = true)
    }
    if (landscapeZChartFullscreen) {
        with(screen) {
            LandscapeZScoreFullscreenPane(
                modifier = modifier.fillMaxSize(),
                selectedPeriod = Period.OneMonth,
                onPeriodSelect = {},
                showPeriodSelector = false,
                candles = strategyTestZScoreCandles,
                displayPoints = strategyTestM15ChartPoints,
                referenceLines = zReferenceLines,
                pointMarkers = strategyTestChartMarkers,
                tradeSegments = strategyTestChartTradeSegments,
                strategyTestTradeItems = strategyTestTradeItems,
                openPosition = strategyTestOpenPosition,
                initialWindowWidth = strategyTestZInitialWindow.first,
                initialWindowStart = strategyTestZInitialWindow.second,
                emptyContent = {
                    when {
                        strategyTestM15Loading || strategyTestSimComputing -> {
                            Text(
                                text = "Загрузка 15м для графика Z…",
                                color = Color(0xFFBDBDBD),
                                fontSize = 11.sp,
                            )
                        }
                        strategyTestError != null -> {
                            Text(
                                text = strategyTestError.orEmpty(),
                                color = Color(0xFFEF9A9A),
                                fontSize = 11.sp,
                            )
                        }
                        else -> EmptyState()
                    }
                },
            )
        }
        return
    }
    Column(modifier.fillMaxSize()) {
        with(screen) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    StrategyTestTabContent(
                        metrics = strategyTestPortfolioMetrics,
                        simulationComputing = strategyTestSimComputing,
                        tradeItems = strategyTestTradeItems,
                        m15Loading = strategyTestM15Loading,
                        m15Error = strategyTestError,
                        m15ChartPoints = strategyTestM15ChartPoints,
                        zScoreCandles = strategyTestZScoreCandles,
                        chartThresholds = strategyTestChartThresholds,
                        chartTradeSegments = strategyTestChartTradeSegments,
                        chartPointMarkers = strategyTestChartMarkers,
                        zInitialWindow = strategyTestZInitialWindow,
                        durationSummary = strategyTestDurationSummary,
                        monthlyReturnSummary = strategyTestMonthlyReturnSummary,
                        tradeRiskAssessments = strategyTestTradeRiskAssessments,
                        onRefresh = {
                            scope.launch {
                                ensureM15PointsForStrategyTest(preferNetwork = true)
                            }
                        },
                        onMoex15mFullReload = {
                            scope.launch {
                                ensureM15PointsForStrategyTest(
                                    preferNetwork = true,
                                    networkMode = PortfolioM15LoadMode.FULL_REFRESH
                                )
                            }
                        },
                        leverage = portfolioLeverage,
                        commissionPercentPerSide = portfolioCommissionPercent,
                        entryThreshold = (strategyTestEntryThreshold ?: dynamicThresholds.entry)
                            .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                        exitThreshold = (strategyTestExitThreshold ?: dynamicThresholds.exit)
                            .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                        compoundReturns = strategyTestCompoundReturns,
                        onCompoundReturnsChange = { strategyTestCompoundReturns = it },
                        onLeverageChange = { portfolioLeverage = it },
                        onCommissionChange = { portfolioCommissionPercent = it },
                        onEntryThresholdChange = { newEntry ->
                            strategyTestEntryThreshold = newEntry.coerceIn(
                                PORTFOLIO_Z_THRESHOLD_MIN,
                                PORTFOLIO_Z_THRESHOLD_MAX
                            )
                        },
                        onExitThresholdChange = { newExit ->
                            strategyTestExitThreshold = newExit.coerceIn(
                                PORTFOLIO_Z_THRESHOLD_MIN,
                                PORTFOLIO_Z_THRESHOLD_MAX
                            )
                        },
                        dailyReconciliation = dailyReconciliation,
                    )
                }
            }
        }
    }
}
