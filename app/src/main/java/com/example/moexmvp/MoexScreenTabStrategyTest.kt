package com.example.moexmvp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
internal fun MoexScreenTabStrategyTest(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier,
    strategyTestTradeItems: List<StrategyTestTradeItem>,
    strategyTestM15ChartPoints: List<DataPoint>,
    strategyTestZScoreCandles: List<CandlePoint>,
    strategyTestChartThresholds: DynamicThresholds,
    strategyTestZInitialWindow: Pair<Float, Float>,
) {
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
                        chartMarkers = strategyTestChartMarkers,
                        zInitialWindow = strategyTestZInitialWindow,
                        durationSummary = strategyTestDurationSummary,
                        tradeRiskAssessments = strategyTestTradeRiskAssessments,
                        spreadHourlyVolatility = strategyTestSpreadHourlyVolatility,
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
                        presets = portfolioPresets,
                        onApplyPreset = { p ->
                            portfolioLeverage = p.leverage
                            portfolioCommissionPercent = p.commissionPercentPerSide
                            strategyTestEntryThreshold = p.entryThreshold
                            strategyTestExitThreshold = p.exitThreshold
                        },
                        onDeletePreset = { id ->
                            portfolioPresets = deletePortfolioPreset(context, id)
                        },
                        onWalkForward = {
                            scope.launch {
                                walkForwardBusy = true
                                try {
                                    val pts = loadM15ForStrategyTest(
                                        PortfolioM15LoadMode.INCREMENTAL,
                                    )
                                    val th = if (pts.size >= 80) {
                                        withContext(Dispatchers.Default) {
                                            calculateWalkForwardRobustThresholds(pts)
                                        }
                                    } else {
                                        null
                                    }
                                    if (th == null) {
                                        Toast.makeText(
                                            context,
                                            "Недостаточно данных для walk-forward.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        robustCandidate = th
                                    }
                                } finally {
                                    walkForwardBusy = false
                                }
                            }
                        },
                        walkForwardBusy = walkForwardBusy,
                        dailyReconciliation = dailyReconciliation,
                        portfolioEntryThreshold = (realTradeEntryThreshold ?: dynamicThresholds.entry)
                            .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                        portfolioExitThreshold = (realTradeExitThreshold ?: dynamicThresholds.exit)
                            .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                    )
                }
            }
        }
    }
}
