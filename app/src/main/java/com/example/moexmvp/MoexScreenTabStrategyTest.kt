package com.example.moexmvp

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


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
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxSize()) {
        with(screen) {
            val simThresholds = resolveStrategyTestSimThresholds()
            val simCommission = buildStrategyTestCommissionPercentPerSide(context, portfolioCommissionPercent)
            val parityItems = remember(
                strategyTestAccountSizeRub,
                strategyTestCapitalUsagePercent,
                strategyTestUseLiveZSignals,
                portfolioCommissionPercent,
            ) {
                buildStrategyTestProdParityChecklist(
                    context = context,
                    accountSizeRub = strategyTestAccountSizeRub,
                    capitalUsagePercent = strategyTestCapitalUsagePercent,
                    useLiveZSignals = strategyTestUseLiveZSignals,
                    commissionPercentPerSide = simCommission,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(focusManager) {
                        detectTapGestures(onTap = { focusManager.clearFocus(force = true) })
                    },
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
                        maxLossDdPercent = strategyTestMaxLossDdPercent,
                        useLiveZSignals = strategyTestUseLiveZSignals,
                        onUseLiveZSignalsChange = { enabled ->
                            strategyTestUseLiveZSignals = enabled
                            markStrategyTestSimParamsStale()
                        },
                        parityItems = parityItems,
                        onApplyProdAccountCash = {
                            loadLastProdPortfolioCashRub(context)?.let { cash ->
                                markStrategyTestSimParamsStale()
                                strategyTestAccountSizeRub = cash.coerceIn(
                                    STRATEGY_TEST_ACCOUNT_RUB_MIN,
                                    STRATEGY_TEST_ACCOUNT_RUB_MAX,
                                )
                            }
                        },
                        onApplyProdReservePercent = {
                            val pct = PROD_EFFECTIVE_CAPITAL_USAGE_PERCENT
                            if (pct != strategyTestCapitalUsagePercent) {
                                markStrategyTestSimParamsStale()
                                strategyTestCapitalUsagePercent = pct
                            }
                        },
                        simCommissionPercentPerSide = simCommission,
                        execLogSummary = TradeExecutionLog.calibrationSummary(context),
                        entryThreshold = simThresholds.entry,
                        exitThreshold = simThresholds.exit,
                        compoundReturns = strategyTestCompoundReturns,
                        onCompoundReturnsChange = { strategyTestCompoundReturns = it },
                        excludeRedZone = strategyTestExcludeRedZone,
                        onExcludeRedZoneChange = { strategyTestExcludeRedZone = it },
                        onLeverageChange = { portfolioLeverage = it },
                        onCommissionChange = { portfolioCommissionPercent = it },
                        onAccountSizeChange = { newRub ->
                            val coerced = newRub.coerceIn(
                                STRATEGY_TEST_ACCOUNT_RUB_MIN,
                                STRATEGY_TEST_ACCOUNT_RUB_MAX,
                            )
                            if (coerced != strategyTestAccountSizeRub) {
                                markStrategyTestSimParamsStale()
                                strategyTestAccountSizeRub = coerced
                            }
                        },
                        onCapitalUsageChange = { newPct ->
                            val coerced = newPct.coerceIn(10.0, 100.0)
                            if (coerced != strategyTestCapitalUsagePercent) {
                                markStrategyTestSimParamsStale()
                                strategyTestCapitalUsagePercent = coerced
                            }
                        },
                        onMaxLossDdPercentChange = { newPct ->
                            val coerced = newPct.coerceIn(0.0, 50.0)
                            if (coerced != strategyTestMaxLossDdPercent) {
                                markStrategyTestSimParamsStale()
                                strategyTestMaxLossDdPercent = coerced
                            }
                        },
                        onEntryThresholdChange = { newEntry ->
                            strategyTestEntryThreshold = newEntry.coerceIn(
                                PORTFOLIO_Z_THRESHOLD_MIN,
                                PORTFOLIO_Z_THRESHOLD_MAX,
                            )
                            markStrategyTestSimParamsStale()
                        },
                        onExitThresholdChange = { newExit ->
                            strategyTestExitThreshold = newExit.coerceIn(
                                PORTFOLIO_Z_THRESHOLD_MIN,
                                PORTFOLIO_Z_THRESHOLD_MAX,
                            )
                            markStrategyTestSimParamsStale()
                        },
                        onExportCompareCsv = {
                            scope.launch {
                                val thresholds = resolveStrategyTestSimThresholds()
                                val comm = buildStrategyTestCommissionPercentPerSide(
                                    context,
                                    portfolioCommissionPercent,
                                )
                                val csv = withContext(Dispatchers.IO) {
                                    buildStrategyTestCompareCsvFromState(
                                        context = context,
                                        metrics = strategyTestPortfolioMetrics,
                                        tradeItems = strategyTestTradeItems,
                                        accountSizeRub = strategyTestAccountSizeRub,
                                        capitalUsagePercent = strategyTestCapitalUsagePercent,
                                        leverageForLots = portfolioLeverage,
                                        commissionPercentPerSide = comm,
                                        entryThreshold = thresholds.entry,
                                        exitThreshold = thresholds.exit,
                                        compoundReturns = strategyTestCompoundReturns,
                                        maxLossDdPercent = strategyTestMaxLossDdPercent,
                                        useLiveZSignals = strategyTestUseLiveZSignals,
                                        thresholdSource = thresholds.source.name,
                                    )?.also { persist ->
                                        StrategyTestExportStore.saveCompareCsv(context, persist)
                                    }
                                }
                                if (csv == null || !copyCsvToClipboard(context, csv, "moex_sim_trades.csv")) {
                                    Toast.makeText(
                                        context,
                                        "Нет сделок для выгрузки",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@launch
                                }
                                Toast.makeText(
                                    context,
                                    "CSV Тест страт. (${tradeCompareRowCount(csv)} сделок) в буфере — сравните с Prod",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        dailyReconciliation = dailyReconciliation,
                    )
                }
            }
        }
    }
}
