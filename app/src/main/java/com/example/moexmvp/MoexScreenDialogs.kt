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
internal fun MoexScreenDialogs(
    screen: MoexScreenState,
    scope: CoroutineScope,
) {
    with(screen) {
    robustCandidate?.let { cand ->
        AlertDialog(
            onDismissRequest = { robustCandidate = null },
            title = { Text("Walk-forward пороги", color = Color.White) },
            text = {
                Text(
                    String.format(
                        Locale.US,
                        "Вход ±%.2f, выход ±%.2f\n%s",
                        cand.entry,
                        cand.exit,
                        cand.calculatedDate ?: ""
                    ),
                    color = Color(0xFFE0E0E0),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveRealTradeZThresholds(context, cand.entry, cand.exit)
                        realTradeEntryThreshold = cand.entry.coerceIn(
                            PORTFOLIO_Z_THRESHOLD_MIN,
                            PORTFOLIO_Z_THRESHOLD_MAX
                        )
                        realTradeExitThreshold = cand.exit.coerceIn(
                            PORTFOLIO_Z_THRESHOLD_MIN,
                            PORTFOLIO_Z_THRESHOLD_MAX
                        )
                        dynamicThresholds = portfolioChartZThresholds(
                            realTradeEntryThreshold,
                            realTradeExitThreshold
                        )
                        robustCandidate = null
                        Toast.makeText(context, "Пороги портфеля применены", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Применить")
                }
            },
            dismissButton = {
                TextButton(onClick = { robustCandidate = null }) {
                    Text("Закрыть")
                }
            },
            containerColor = Color(0xFF263238)
        )
    }

    if (showCloseAllPortfolioDialog) {
        AlertDialog(
            onDismissRequest = { if (!closeAllPortfolioBusy) showCloseAllPortfolioDialog = false },
            title = { Text("Закрыть все сделки", color = Color.White) },
            text = {
                Text(
                    "Отправить обратные заявки на ${executionAccountShortRu(executionMode)} (если позиция открыта), записать выход в журнал, " +
                        "очистить список исполнений портфеля и сбросить локальную позицию Z в FLAT. Журнал сигналов не очищается.",
                    color = Color(0xFFE0E0E0),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !closeAllPortfolioBusy,
                    onClick = {
                        closeAllPortfolioBusy = true
                        scope.launch {
                            try {
                                val pos = withContext(Dispatchers.IO) { loadSavedStrategyPosition(context) }
                                val entrySignal = when (pos) {
                                    ZStrategyPosition.Long -> StrategySignalType.EnterLong
                                    ZStrategyPosition.Short -> StrategySignalType.EnterShort
                                    ZStrategyPosition.Flat -> null
                                }
                                val mode = TinkoffSandboxStorage.getExecutionMode(context)
                                val tok = TinkoffSandboxStorage.getActiveToken(context, mode)
                                val acc = TinkoffSandboxStorage.getActiveAccountId(context, mode)
                                if (entrySignal != null &&
                                    TinkoffSandboxStorage.isExecuteSignalsOnSandbox(context) &&
                                    !tok.isNullOrBlank() &&
                                    !acc.isNullOrBlank()
                                ) {
                                    val openTrade = sandboxSpreadExecutions
                                        .filter { it.signalType == entrySignal }
                                        .maxByOrNull { it.barTimestampMillis }
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            if (openTrade != null) {
                                                executeSpreadExitDetailedForConfiguredMode(
                                                    context,
                                                    entrySignal,
                                                    openTrade.quantityLots,
                                                )
                                            } else {
                                                executeSpreadExitDetailedForConfiguredMode(context, entrySignal, 1)
                                            }
                                        }
                                    }
                                }
                                val (tsExit, lastZ) = withContext(Dispatchers.IO) {
                                    val till = LocalDate.now(moexZoneId)
                                    val pts = loadPortfolio15mDataPoints(
                                        context,
                                        till.minusDays(3),
                                        till,
                                        PortfolioM15LoadMode.INCREMENTAL
                                    )
                                    val lastPt = pts.lastOrNull()
                                    Pair(
                                        lastPt?.timestampMillis ?: System.currentTimeMillis(),
                                        lastPt?.zScore ?: 0.0
                                    )
                                }
                                when (pos) {
                                    ZStrategyPosition.Long -> {
                                        recordStrategySignalEvent(
                                            context = context,
                                            signalType = StrategySignalType.ExitLong,
                                            zScore = lastZ,
                                            timestampMillis = tsExit,
                                            skipJournalWallDedup = true,
                                            savePendingVirtualTradeIfEntry = false
                                        )
                                    }
                                    ZStrategyPosition.Short -> {
                                        recordStrategySignalEvent(
                                            context = context,
                                            signalType = StrategySignalType.ExitShort,
                                            zScore = lastZ,
                                            timestampMillis = tsExit,
                                            skipJournalWallDedup = true,
                                            savePendingVirtualTradeIfEntry = false
                                        )
                                    }
                                    ZStrategyPosition.Flat -> Unit
                                }
                                saveStrategyPosition(context, ZStrategyPosition.Flat)
                                zStrategyPosition = ZStrategyPosition.Flat
                                clearPortfolioExecutionLedger(context)
                                TinkoffSandboxSpreadExecLog.clear(context)
                                clearSandboxAutoSpreadDedup(context)
                                clearConsumed15mStrategySignalEdge(context)
                                clearPendingVirtualTradeProposal(context)
                                pendingVirtualTrade = null
                                signalEvents = loadStrategySignalEvents(context)
                                sandboxSpreadExecReload++
                                refreshPortfolio(null)
                                Toast.makeText(context, "Портфель сделок сброшен.", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    e.message?.take(120) ?: e.javaClass.simpleName,
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                closeAllPortfolioBusy = false
                                showCloseAllPortfolioDialog = false
                            }
                        }
                    }
                ) {
                    Text("Закрыть и сбросить", color = Color(0xFFFFAB91))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !closeAllPortfolioBusy,
                    onClick = { showCloseAllPortfolioDialog = false }
                ) {
                    Text("Отмена")
                }
            },
            containerColor = Color(0xFF263238)
        )
    }
    }
}
