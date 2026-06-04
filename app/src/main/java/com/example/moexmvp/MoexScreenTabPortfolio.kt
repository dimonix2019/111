package com.example.moexmvp

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
internal fun MoexScreenTabPortfolio(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier,
) {
    Column(modifier) {
        with(screen) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    ConfirmedPortfolioTabContent(
                        metrics = confirmedPortfolioMetrics,
                        confirmedTradeTableRows = confirmedPortfolioTableRows,
                        sandboxSpreadExecutions = sandboxSpreadExecutions,
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
                        onTestSpreadPairClick = {
                            scope.launch {
                                portfolioTestBusy = true
                                try {
                                    val proposal = pendingVirtualTrade
                                    val pairType = when (proposal?.signalType) {
                                        StrategySignalType.EnterShort -> StrategySignalType.EnterShort
                                        else -> StrategySignalType.EnterLong
                                    }
                                    val r = withContext(Dispatchers.IO) {
                                        runPortfolioTestSpreadPairFull(
                                            context.applicationContext,
                                            pairType
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
                                        android.widget.Toast.makeText(
                                            context,
                                            result.toastMessage,
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        val err = r.exceptionOrNull()
                                        android.widget.Toast.makeText(
                                            context,
                                            err?.message?.take(400) ?: err?.javaClass?.simpleName ?: "Ошибка",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } finally {
                                    portfolioTestBusy = false
                                }
                            }
                        },
                        closeAllPortfolioBusy = closeAllPortfolioBusy,
                        onCloseAllTradesClick = { showCloseAllPortfolioDialog = true },
                        dailyReconciliation = dailyReconciliation,
                        latestZScore = portfolioM15Points.lastOrNull()?.zScore,
                        latestSpreadPercent = portfolioM15Points.lastOrNull()?.spreadPercent,
                        latestBarLabel = portfolioM15Points.lastOrNull()?.tradeDate
                    )
                }
            }
        }
    }
}
