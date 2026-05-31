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
internal fun MoexScreenTabStrategyTest(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier,
    strategyTestTradeItems: List<StrategyTestTradeItem>,
) {
    Column(modifier) {
    with(screen) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        StrategyTestTabContent(
                            metrics = strategyTestPortfolioMetrics,
                            simulationComputing = strategyTestSimComputing,
                            tradeItems = strategyTestTradeItems,
                            m15Loading = strategyTestM15Loading,
                            m15Error = strategyTestError,
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
                                        val till = LocalDate.now(moexZoneId)
                                        val from = till.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
                                        val pts = withContext(Dispatchers.IO) {
                                            loadPortfolio15mDataPoints(
                                                context,
                                                from,
                                                till,
                                                PortfolioM15LoadMode.INCREMENTAL
                                            )
                                        }
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
                                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
                        )
                    }
                }
    }
    }
}
