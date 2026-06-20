package com.example.moexmvp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
            val displayTradeItems = if (strategyTestExcludeRedZone) {
                filterStrategyTestTradeItemsExcludingRedZone(
                    strategyTestTradeItems,
                    strategyTestTradeRiskAssessments,
                )
            } else {
                strategyTestTradeItems
            }
            if (strategyTestZScoreCandles.isNotEmpty()) {
                StrategyTestZScoreTradingViewChart(
                    candles = strategyTestZScoreCandles,
                    chartPoints = strategyTestM15ChartPoints,
                    pointMarkers = strategyTestChartMarkers,
                    tradeSegments = strategyTestChartTradeSegments,
                    tradeItems = displayTradeItems,
                    openPosition = if (strategyTestExcludeRedZone) null else strategyTestOpenPosition,
                    referenceLines = zReferenceLines,
                    initialWindow = strategyTestZInitialWindow,
                    landscapeMinimal = true,
                    modifier = modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
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
                }
            }
        }
        return
    }
    val context = LocalContext.current
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
                        accountSizeRub = strategyTestAccountSizeRub,
                        capitalUsagePercent = strategyTestCapitalUsagePercent,
                        execLogSummary = TradeExecutionLog.calibrationSummary(context),
                        entryThreshold = (strategyTestEntryThreshold ?: dynamicThresholds.entry)
                            .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                        exitThreshold = (strategyTestExitThreshold ?: dynamicThresholds.exit)
                            .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                        compoundReturns = strategyTestCompoundReturns,
                        onCompoundReturnsChange = { strategyTestCompoundReturns = it },
                        excludeRedZone = strategyTestExcludeRedZone,
                        onExcludeRedZoneChange = { strategyTestExcludeRedZone = it },
                        onLeverageChange = { portfolioLeverage = it },
                        onCommissionChange = { portfolioCommissionPercent = it },
                        onAccountSizeChange = { strategyTestAccountSizeRub = it.coerceIn(1_000.0, 10_000_000.0) },
                        onCapitalUsageChange = {
                            strategyTestCapitalUsagePercent = it.coerceIn(10.0, 100.0)
                        },
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
