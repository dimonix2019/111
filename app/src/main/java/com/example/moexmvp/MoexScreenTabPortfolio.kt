package com.example.moexmvp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MoexScreenTabPortfolio(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier,
) {
    Column(modifier) {
        with(screen) {
            val enrichmentPoints by produceState(
                initialValue = emptyList<DataPoint>(),
                portfolioM15Points,
                marketsM15Points,
                lastGoodMarkets?.points,
            ) {
                value = withContext(Dispatchers.Default) {
                    when {
                        portfolioM15Points.isNotEmpty() -> portfolioM15Points
                        marketsM15Points.isNotEmpty() -> marketsM15Points
                        !lastGoodMarkets?.points.isNullOrEmpty() ->
                            applyZScoresDefault(lastGoodMarkets!!.points)
                        else -> emptyList()
                    }
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
                        onMoex15mFullReload = {
                            scope.launch { refreshPortfolio(PortfolioM15LoadMode.INCREMENTAL) }
                        },
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
                    )
                }
                if (portfolioLoading) {
                    item {
                        LoadingStateWithProgress(
                            progress = dataLoadProgress,
                            dataLoadSessions = dataLoadSessions,
                            statusText = "Загрузка 15м для портфеля (~$PORTFOLIO_TAB_M15_LOOKBACK_DAYS дн.)…",
                        )
                    }
                }
            }
        }
    }
}
