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
internal fun MoexScreenVirtualTradeCard(
    screen: MoexScreenState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    with(screen) {
        pendingVirtualTrade?.let { proposal ->
            PendingVirtualTradeProposalCard(
                proposal = proposal,
                sandboxState = sandboxExecState,
                executionMode = executionMode,
                onAccept = {
                    scope.launch {
                        val mode = currentExecutionMode(context)
                        val st = TinkoffSandboxStorage.resolveExecUiState(context, mode)
                        sandboxExecState = st
                        when (st) {
                            SandboxExecUiState.Ready -> {
                                val tok = TinkoffSandboxStorage.getActiveToken(context, mode)
                                val acc = TinkoffSandboxStorage.getActiveAccountId(context, mode)
                                if (tok.isNullOrBlank() || acc.isNullOrBlank()) {
                                    Toast.makeText(
                                        context,
                                        "Нет токена или счёта (${executionModeLabelRu(mode)}).",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@launch
                                }
                                try {
                                    val nowMs = System.currentTimeMillis()
                                    val (skipDupJournal, legs, opened) = withContext(Dispatchers.IO) {
                                        val legsInner = tinkoffExecuteSpreadEntryDetailed(
                                            mode,
                                            tok,
                                            acc,
                                            proposal.signalType
                                        )
                                        clearPendingVirtualTradeProposal(context, proposal)
                                        appendPortfolioExecutionLedger(
                                            context,
                                            barTimestampMillis = proposal.timestampMillis,
                                            signalType = proposal.signalType,
                                            source = PortfolioExecSource.MANUAL
                                        )
                                        val recent = loadStrategySignalEvents(context)
                                        val last = recent.lastOrNull()
                                        val skipDup = last != null &&
                                            last.signalType == proposal.signalType &&
                                            last.timestampMillis == proposal.timestampMillis
                                        if (!skipDup) {
                                            recordStrategySignalEvent(
                                                context = context,
                                                signalType = proposal.signalType,
                                                zScore = proposal.zScore,
                                                timestampMillis = proposal.timestampMillis,
                                                skipJournalWallDedup = true,
                                                savePendingVirtualTradeIfEntry = false
                                            )
                                        }
                                        val entrySpread = resolveSpreadPercentAtBar(
                                            context,
                                            proposal.timestampMillis
                                        )
                                        val opened = TinkoffSandboxSpreadExecLog.recordFromLegs(
                                            context,
                                            proposal.signalType,
                                            proposal.zScore,
                                            barTimestampMillis = proposal.timestampMillis,
                                            executedAtMillis = nowMs,
                                            entrySpreadPercent = entrySpread,
                                            source = PortfolioExecSource.MANUAL,
                                            legs = legsInner,
                                            fromTestButton = false
                                        )
                                        val position = when (proposal.signalType) {
                                            StrategySignalType.EnterLong -> ZStrategyPosition.Long
                                            StrategySignalType.EnterShort -> ZStrategyPosition.Short
                                            else -> ZStrategyPosition.Flat
                                        }
                                        saveStrategyPosition(context, position)
                                        Triple(skipDup, legsInner, opened)
                                    }
                                    val lastLeg = legs.lastOrNull()
                                    opened?.let { trade ->
                                        notifySandboxTradeOpened(
                                            context = context,
                                            execution = trade,
                                            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                                            leverage = TinkoffSandboxStorage.getSandboxNotifyLeverage(context),
                                            portfolioTotalRub = lastLeg?.portfolioTotalRub,
                                            portfolioCashRub = lastLeg?.portfolioCashRub,
                                        )
                                    }
                                    zStrategyPosition = loadSavedStrategyPosition(context)
                                    pendingVirtualTrade = null
                                    sandboxSpreadExecReload++
                                    signalEvents = loadStrategySignalEvents(context)
                                    val tail = if (skipDupJournal) {
                                        "Повторный вход в журнал не записан (тот же сигнал уже в журнале)."
                                    } else {
                                        "Вход записан в журнал."
                                    }
                                    Toast.makeText(
                                        context,
                                        "Отправлены 2 заявки (${executionModeLabelRu(mode)}). $tail",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        e.message?.takeIf { it.isNotBlank() }
                                            ?: "${e.javaClass.simpleName} (см. лог)",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            SandboxExecUiState.MissingCredentials -> {
                                clearPendingVirtualTradeProposal(context, proposal)
                                pendingVirtualTrade = null
                                Toast.makeText(
                                    context,
                                    "Принято без заявок: сохраните токен и счёт (${executionModeLabelRu(mode)}).",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            SandboxExecUiState.Off -> Unit
                        }
                    }
                },
                onReject = {
                    clearPendingVirtualTradeProposal(context, proposal)
                    pendingVirtualTrade = null
                    Toast.makeText(context, "Отклонено.", Toast.LENGTH_SHORT).show()
                },
                modifier = modifier.padding(top = 6.dp)
            )
        }
    }
}
