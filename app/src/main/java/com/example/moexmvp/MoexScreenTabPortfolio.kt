package com.example.moexmvp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            var pendingCloseTradeId by remember { mutableStateOf<String?>(null) }
            var closingTradeId by remember { mutableStateOf<String?>(null) }
            val enrichmentPoints by produceState(
                initialValue = emptyList<DataPoint>(),
                portfolioM15Points,
                marketsM15DataEpoch,
                lastGoodMarkets?.points,
            ) {
                value = withContext(Dispatchers.Default) {
                    when {
                        portfolioM15Points.isNotEmpty() -> portfolioM15Points
                        marketsM15Source().isNotEmpty() -> marketsM15Source()
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
                portfolioCommissionPercent,
                executionMode,
            ) {
                enrichSandboxExecutionsIfNeeded(
                    executions = sandboxSpreadExecutions,
                    points = enrichmentPoints,
                    leverage = portfolioLeverage,
                    commissionPercentPerSide = portfolioCommissionPercent,
                    executionMode = executionMode,
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
            pendingCloseTradeId?.let { tradeId ->
                val exec = sandboxSpreadExecutions.find { it.tradeId == tradeId }
                AlertDialog(
                    onDismissRequest = {
                        if (closingTradeId == null) pendingCloseTradeId = null
                    },
                    title = { Text("Закрыть сделку", color = Color.White) },
                    text = {
                        Text(
                            text = if (exec != null) {
                                "Сделка ${exec.tradeId} (${exec.directionLabel}): обратные заявки на ${executionAccountShortRu(executionMode)} " +
                                    "(если включено), запись выхода в журнал, перенос в «Закрытые»."
                            } else {
                                "Закрыть сделку $tradeId?"
                            },
                            color = Color(0xFFE0E0E0),
                            fontSize = 13.sp
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = closingTradeId == null && exec != null,
                            onClick = {
                                val target = exec ?: return@TextButton
                                closingTradeId = tradeId
                                scope.launch {
                                    try {
                                        val msg = withContext(Dispatchers.IO) {
                                            closePortfolioOpenTrade(context, target).getOrThrow()
                                        }
                                        zStrategyPosition = loadSavedStrategyPosition(context)
                                        signalEvents = loadStrategySignalEvents(context)
                                        sandboxSpreadExecReload++
                                        refreshPortfolio(null)
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            e.message?.take(200) ?: e.javaClass.simpleName,
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                        closingTradeId = null
                                        pendingCloseTradeId = null
                                    }
                                }
                            }
                        ) {
                            Text("Закрыть", color = Color(0xFFFFAB91))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = closingTradeId == null,
                            onClick = { pendingCloseTradeId = null }
                        ) {
                            Text("Отмена")
                        }
                    },
                    containerColor = Color(0xFF263238)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    ConfirmedPortfolioTabContent(
                        confirmedTradeTableRows = confirmedPortfolioTableRows,
                        sandboxSpreadExecutions = displayOpenExecutions,
                        portfolioLoading = portfolioLoading,
                        portfolioError = portfolioError,
                        lookbackDays = portfolioLookbackDays,
                        onLookbackDaysChange = { days ->
                            portfolioLookbackDays = normalizePortfolioLookbackDays(days)
                        },
                        onRefresh = {
                            portfolioTabUiBuiltKey = 0L
                            scope.launch {
                                if (executionMode == TinkoffExecutionMode.Prod) {
                                    refreshProdOpenTradesFromBroker()
                                }
                                refreshPortfolio(PortfolioM15LoadMode.INCREMENTAL)
                            }
                        },
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
                        executionMode = executionMode,
                        portfolioTestBusy = portfolioTestBusy,
                        onTestSpreadPairLongClick = {
                            launchTestSpreadPair(StrategySignalType.EnterLong)
                        },
                        onTestSpreadPairShortClick = {
                            launchTestSpreadPair(StrategySignalType.EnterShort)
                        },
                        closeAllPortfolioBusy = closeAllPortfolioBusy,
                        onCloseAllTradesClick = { showCloseAllPortfolioDialog = true },
                        onCloseOpenTrade = { tradeId -> pendingCloseTradeId = tradeId },
                        closingTradeId = closingTradeId,
                    )
                }
                dailyReconciliation?.let { rec ->
                    item {
                        DailyReconciliationSection(rec)
                    }
                }
            }
        }
    }
}
