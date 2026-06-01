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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
internal fun MoexScreenTabPortfolio(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier,
    landscapeZChartFullscreen: Boolean,
    portfolioZChartPoints: List<DataPoint>,
    portfolioZScoreCandles: List<CandlePoint>,
    portfolioPortraitZScoreCandles: List<CandlePoint>,
    portfolioLandscapeChartThresholds: DynamicThresholds,
    portfolioPortraitZChartPoints: List<DataPoint>,
) {
    val portfolioZInitialWindow = remember(portfolioZChartPoints, screen.marketsZChartPeriod) {
        chartInitialWindowForLastCalendarDays(
            portfolioZChartPoints,
            visibleDays = calendarDaysForMarketsZChartPeriod(screen.marketsZChartPeriod),
        )
    }
    Column(modifier) {
        with(screen) {
            if (landscapeZChartFullscreen) {
                LandscapeZScoreFullscreenPane(
                    modifier = Modifier.fillMaxSize(),
                        selectedPeriod = marketsZChartPeriod,
                        onPeriodSelect = {
                            marketsZChartPeriod = it
                        },
                        candles = portfolioZScoreCandles,
                        referenceLines = buildZScoreReferenceLines(portfolioLandscapeChartThresholds),
                        initialWindowWidth = portfolioZInitialWindow.first,
                        initialWindowStart = portfolioZInitialWindow.second,
                        pointMarkers = buildZScoreSignalMarkersFromEvents(
                            points = portfolioZChartPoints,
                            events = signalEvents
                        ),
                        useDesktopStyle = true,
                        showPlotlyToolbar = true,
                        trackpadGestures = true,
                        tradeTapHintFormatter = { idx ->
                            formatZStrategyTradeTapHint(
                                idx,
                                portfolioZChartPoints,
                                confirmedPortfolioMetrics
                            )
                        },
                        emptyContent = {
                            if (portfolioLoading) LoadingState() else EmptyState()
                        }
                )
            } else {
                val enrichmentPoints = remember(
                    portfolioM15Points,
                    marketsM15Points,
                    lastGoodMarkets?.points
                ) {
                    when {
                        portfolioM15Points.isNotEmpty() -> portfolioM15Points
                        marketsM15Points.isNotEmpty() -> marketsM15Points
                        !lastGoodMarkets?.points.isNullOrEmpty() ->
                            applyZScoresDefault(lastGoodMarkets!!.points)
                        else -> emptyList()
                    }
                }
                val displayOpenExecutions = remember(
                    sandboxSpreadExecutions,
                    enrichmentPoints,
                    portfolioLeverage,
                    portfolioCommissionPercent
                ) {
                    enrichSandboxExecutionsIfNeeded(
                        executions = sandboxSpreadExecutions,
                        points = enrichmentPoints,
                        leverage = portfolioLeverage,
                        commissionPercentPerSide = portfolioCommissionPercent
                    )
                }
                val launchTestSpreadPair: (StrategySignalType) -> Unit = { signalType ->
                    scope.launch {
                        portfolioTestBusy = true
                        try {
                            val r = withContext(Dispatchers.IO) {
                                runPortfolioTestSpreadPairFull(
                                    context.applicationContext,
                                    signalType,
                                )
                            }
                            if (r.isSuccess) {
                                val result = r.getOrThrow()
                                zStrategyPosition = loadSavedStrategyPosition(context)
                                if (sandboxSpreadAutoExecute) {
                                    clearPendingVirtualTradeProposal(context)
                                    pendingVirtualTrade = null
                                } else {
                                    pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                                }
                                signalEvents = loadStrategySignalEvents(context)
                                sandboxSpreadExecReload += result.sandboxSpreadExecReloadDelta
                                Toast.makeText(
                                    context,
                                    result.toastMessage,
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                val err = r.exceptionOrNull()
                                Toast.makeText(
                                    context,
                                    err?.message?.take(400)
                                        ?: err?.javaClass?.simpleName
                                        ?: "Ошибка",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        } finally {
                            portfolioTestBusy = false
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ConfirmedPortfolioTabContent(
                            metrics = confirmedPortfolioMetrics,
                            confirmedTradeTableRows = confirmedPortfolioTableRows,
                            sandboxSpreadExecutions = displayOpenExecutions,
                            portfolioLoading = portfolioLoading,
                            portfolioError = portfolioError,
                            onRefresh = { scope.launch { refreshPortfolio(PortfolioM15LoadMode.INCREMENTAL) } },
                            onMoex15mFullReload = { scope.launch { refreshPortfolio(PortfolioM15LoadMode.FULL_REFRESH) } },
                            leverage = portfolioLeverage,
                            commissionPercentPerSide = portfolioCommissionPercent,
                            onLeverageChange = { portfolioLeverage = it },
                            onCommissionChange = { portfolioCommissionPercent = it },
                            realTradeEntryThreshold = (realTradeEntryThreshold ?: dynamicThresholds.entry)
                                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                            realTradeExitThreshold = (realTradeExitThreshold ?: dynamicThresholds.exit)
                                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                            onRealTradeEntryChange = { v ->
                                realTradeEntryThreshold = v.coerceIn(
                                    PORTFOLIO_Z_THRESHOLD_MIN,
                                    PORTFOLIO_Z_THRESHOLD_MAX
                                )
                            },
                            onRealTradeExitChange = { v ->
                                realTradeExitThreshold = v.coerceIn(
                                    PORTFOLIO_Z_THRESHOLD_MIN,
                                    PORTFOLIO_Z_THRESHOLD_MAX
                                )
                            },
                            portfolioLedgerIncludeAuto = portfolioLedgerIncludeAuto,
                            onPortfolioLedgerIncludeAutoChange = { v ->
                                TinkoffSandboxStorage.setPortfolioDemoEntryMode(context, v)
                                portfolioLedgerIncludeAuto = v
                                sandboxSpreadAutoExecute = v
                                if (v) {
                                    clearPendingVirtualTradeProposal(context)
                                    pendingVirtualTrade = null
                                }
                            },
                            portfolioTestBusy = portfolioTestBusy,
                            onTestSpreadPairLongClick = {
                                launchTestSpreadPair(StrategySignalType.EnterLong)
                            },
                            onTestSpreadPairShortClick = {
                                launchTestSpreadPair(StrategySignalType.EnterShort)
                            },
                            closeAllPortfolioBusy = closeAllPortfolioBusy,
                            onCloseAllTradesClick = { showCloseAllPortfolioDialog = true },
                            dailyReconciliation = dailyReconciliation,
                            latestZScore = portfolioM15Points.lastOrNull()?.zScore,
                            latestSpreadPercent = portfolioM15Points.lastOrNull()?.spreadPercent,
                            latestBarLabel = portfolioM15Points.lastOrNull()?.tradeDate,
                            zScoreCandles = portfolioPortraitZScoreCandles,
                            zChartPoints = portfolioPortraitZChartPoints,
                            zChartThresholds = portfolioLandscapeChartThresholds,
                            signalEvents = signalEvents
                        )
                    }
                }
            }
        }
    }
}
