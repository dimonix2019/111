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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale


@Composable
internal fun MoexScreenEffects(screen: MoexScreenState, scope: CoroutineScope) {
    with(screen) {
        val chartSuccess = (state as? UiState.Success) ?: lastGoodMarkets
    LaunchedEffect(
        portfolioM15Points,
        marketsM15Points,
        strategyTestEntryThreshold,
        strategyTestExitThreshold,
        portfolioLeverage,
        portfolioCommissionPercent,
        strategyTestCompoundReturns,
        dynamicThresholds.entry,
        dynamicThresholds.exit,
        dynamicThresholds.calculatedDate,
        selectedTab,
    ) {
        if (selectedTab == MainTab.Markets) {
            delay(800)
        }
        strategyTestSimComputing = true
        try {
            val points = m15PointsForStrategyTest()
            strategyTestPortfolioMetrics = withContext(Dispatchers.Default) {
                if (points.size < 2) return@withContext null
                val entry = (strategyTestEntryThreshold ?: dynamicThresholds.entry)
                    .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
                val exit = (strategyTestExitThreshold ?: dynamicThresholds.exit)
                    .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
                buildZStrategyPortfolioMetrics(
                    points = points,
                    thresholds = DynamicThresholds(entry, exit, dynamicThresholds.calculatedDate),
                    notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                    leverage = portfolioLeverage,
                    commissionPercentPerSide = portfolioCommissionPercent,
                    periodDescription = "Тест страт. · ${PORTFOLIO_M15_LOOKBACK_DAYS}д",
                    compoundReturns = strategyTestCompoundReturns,
                    exitMode = ZStrategyExitMode.FixedThreshold
                )
            }
        } finally {
            strategyTestSimComputing = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        fun hydrateVirtualTradeAndSandboxUi() {
            val act = context as? Activity
            applyVirtualTradeTapIntent(context, act?.intent)
            parseAppUpdateFromTapIntent(act?.intent)?.let { remote ->
                if (shouldOfferAppUpdateUi(remote, context)) {
                    if (pendingAppUpdate == null || pendingAppUpdate!!.versionCode < remote.versionCode) {
                        pendingAppUpdate = remote
                    }
                }
            }
            restorePendingVirtualTradeFromJournalIfNeeded(context)
            pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
            sandboxExecState = TinkoffSandboxStorage.resolveExecUiState(context)
            executeSignalsOnSandbox = TinkoffSandboxStorage.isExecuteSignalsOnSandbox(context)
            sandboxSpreadAutoExecute = TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context)
        }
        hydrateVirtualTradeAndSandboxUi()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hydrateVirtualTradeAndSandboxUi()
                scope.launch {
                    val (t, a) = withContext(Dispatchers.IO) {
                        Pair(
                            TinkoffSandboxStorage.getToken(context).orEmpty(),
                            TinkoffSandboxStorage.getAccountId(context).orEmpty()
                        )
                    }
                    sandboxTokenInput = t
                    sandboxAccountInput = a
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        runCatching {
            val (t, a) = TinkoffSandboxStorage.hydrateCredentialsForUi(context)
            sandboxTokenInput = t
            sandboxAccountInput = a
        }
    }

    LaunchedEffect(portfolioLeverage) {
        withContext(Dispatchers.IO) {
            TinkoffSandboxStorage.setSandboxNotifyLeverage(context, portfolioLeverage)
        }
    }

    LaunchedEffect(Unit) {
        val rt = loadRealTradeZThresholds(context, dynamicThresholds)
        if (realTradeEntryThreshold == null) realTradeEntryThreshold = rt.entry
        if (realTradeExitThreshold == null) realTradeExitThreshold = rt.exit
        val st = loadStrategyTestZThresholds(context, dynamicThresholds)
        if (strategyTestEntryThreshold == null) strategyTestEntryThreshold = st.entry
        if (strategyTestExitThreshold == null) strategyTestExitThreshold = st.exit
    }

    LaunchedEffect(dynamicThresholds.entry, dynamicThresholds.exit) {
        if (realTradeEntryThreshold == null || realTradeExitThreshold == null) {
            val saved = loadRealTradeZThresholds(context, dynamicThresholds)
            if (realTradeEntryThreshold == null) realTradeEntryThreshold = saved.entry
            if (realTradeExitThreshold == null) realTradeExitThreshold = saved.exit
        }
        if (strategyTestEntryThreshold == null || strategyTestExitThreshold == null) {
            val st = loadStrategyTestZThresholds(context, dynamicThresholds)
            if (strategyTestEntryThreshold == null) strategyTestEntryThreshold = st.entry
            if (strategyTestExitThreshold == null) strategyTestExitThreshold = st.exit
        }
    }

    LaunchedEffect(realTradeEntryThreshold, realTradeExitThreshold) {
        val entry = realTradeEntryThreshold ?: return@LaunchedEffect
        val exit = realTradeExitThreshold ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            saveRealTradeZThresholds(context, entry, exit)
        }
    }

    LaunchedEffect(strategyTestEntryThreshold, strategyTestExitThreshold) {
        val entry = strategyTestEntryThreshold ?: return@LaunchedEffect
        val exit = strategyTestExitThreshold ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            saveStrategyTestZThresholds(context, entry, exit)
        }
    }

    LaunchedEffect(chartSuccess?.points, signalEvents) {
        val pts = chartSuccess?.points
        if (pts != null && pts.isNotEmpty()) {
            val est = estimateTodaySpreadPnlFromEvents(signalEvents, pts)
            todayPnlHint = est?.let { String.format(Locale.US, "%.2f п.п. спрэда", it) }
        } else {
            todayPnlHint = null
        }
    }
    val signalJournalFingerprint = signalEvents.size to signalEvents.sumOf { it.timestampMillis + it.signalType.ordinal * 31L }

    LaunchedEffect(
        signalJournalFingerprint,
        confirmedPortfolioMetrics,
        strategyTestPortfolioMetrics,
        strategyTestEntryThreshold,
        strategyTestExitThreshold,
        dynamicThresholds.entry,
        dynamicThresholds.exit
    ) {
        dailyReconciliation = withContext(Dispatchers.Default) {
            buildDailyPortfolioReconciliation(
                day = LocalDate.now(ZoneId.of("Europe/Moscow")),
                journalEvents = signalEvents,
                confirmed = confirmedPortfolioMetrics,
                simulation = strategyTestPortfolioMetrics,
                simEntryThreshold = strategyTestEntryThreshold ?: dynamicThresholds.entry,
                simExitThreshold = strategyTestExitThreshold ?: dynamicThresholds.exit,
                simExitMode = ZStrategyExitMode.FixedThreshold,
                simZPeakTrailZ = DEFAULT_STRATEGY_TEST_Z_PEAK_TRAIL
            )
        }
    }

    LaunchedEffect(
        selectedTab,
        dynamicThresholds.entry,
        dynamicThresholds.exit,
        portfolioLeverage,
        portfolioCommissionPercent,
        realTradeEntryThreshold,
        realTradeExitThreshold,
        strategyTestEntryThreshold,
        strategyTestExitThreshold,
        signalJournalFingerprint,
        strategyTestCompoundReturns,
        sandboxSpreadExecReload,
        portfolioLedgerIncludeAuto
    ) {
        if (selectedTab == MainTab.Portfolio) {
            refreshPortfolio(null)
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == MainTab.StrategyTest) {
            if (!ensureM15PointsForStrategyTest(preferNetwork = false)) {
                ensureM15PointsForStrategyTest(preferNetwork = true)
            }
        }
    }

    LaunchedEffect(
        portfolioM15Points,
        marketsM15Points,
        portfolioLeverage,
        portfolioCommissionPercent,
        sandboxSpreadExecReload,
        portfolioLedgerIncludeAuto,
        signalJournalFingerprint
    ) {
        syncSandboxExecutionsEnrichment()
    }

    LaunchedEffect(selectedTab, sandboxSpreadExecReload) {
        if (selectedTab == MainTab.Portfolio) {
            syncSandboxExecutionsEnrichment()
        }
    }

    LaunchedEffect(Unit) {
        coroutineScope {
            val marketsHydrate = async { hydrateMarketsFromLocalCache(selectedPeriod) }
            launch { hydrateDeferredUiState() }
            marketsHydrate.await()
        }
        initialMarketsRefreshDone = true
        if (selectedTab == MainTab.Markets) {
            scope.launch {
                refreshData(
                    showLoading = lastGoodMarkets == null,
                    launchScope = scope,
                    selectedPeriod = selectedPeriod,
                    preferBackground = lastGoodMarkets != null,
                )
            }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == MainTab.Markets && initialMarketsRefreshDone) {
            refreshData(showLoading = false, launchScope = scope, selectedPeriod = selectedPeriod)
        }
    }

    LaunchedEffect(selectedTab, sandboxSpreadExecReload, signalJournalFingerprint) {
        if (selectedTab == MainTab.Journal) {
            pushNotificationLog = withContext(Dispatchers.IO) {
                loadPushNotificationLog(context.applicationContext)
            }
        }
    }
    LaunchedEffect(realtimeEnabled, selectedPeriod) {
        if (!realtimeEnabled) return@LaunchedEffect
        while (true) {
            delay(FIXED_REALTIME_INTERVAL_MS)
            refreshData(showLoading = false, launchScope = scope, selectedPeriod = selectedPeriod)
        }
    }
    }
}
