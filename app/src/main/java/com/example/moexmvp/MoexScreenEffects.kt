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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
    val configuration = LocalConfiguration.current
    with(screen) {
        val chartSuccess = (state as? UiState.Success) ?: lastGoodMarkets
    LaunchedEffect(selectedTab) {
        if (selectedTab != MainTab.StrategyTest) {
            strategyTestWorkGeneration++
            detachStrategyTestVisibleState()
            return@LaunchedEffect
        }
        if (restoreStrategyTestVisibleFromSession()) {
            return@LaunchedEffect
        }
        if (strategyTestVisibleResultsFresh()) {
            return@LaunchedEffect
        }
        if (strategyTestM15SessionCache.sufficientForStrategyTestSimulation()) {
            scheduleStrategyTestTabWork(reason = "tab_open_cache", preferNetwork = false)
            return@LaunchedEffect
        }
        scheduleStrategyTestTabWork(reason = "tab_open", preferNetwork = false)
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
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    activityResumed = true
                    memoryPressureLevel = 0
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
                Lifecycle.Event.ON_PAUSE -> activityResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        selectedTab,
        activityResumed,
        strategyTestEntryThreshold,
        strategyTestExitThreshold,
        portfolioLeverage,
        portfolioCommissionPercent,
        strategyTestCompoundReturns,
    ) {
        if (selectedTab != MainTab.StrategyTest || !activityResumed) return@LaunchedEffect
        if (strategyTestEntryThreshold == null || strategyTestExitThreshold == null) return@LaunchedEffect
        if (!strategyTestM15SessionCache.sufficientForStrategyTestSimulation()) return@LaunchedEffect
        delay(STRATEGY_TEST_RESIM_DEBOUNCE_MS)
        if (selectedTab != MainTab.StrategyTest || !activityResumed) return@LaunchedEffect
        if (strategyTestEntryThreshold == null || strategyTestExitThreshold == null) return@LaunchedEffect
        if (strategyTestM15Loading || strategyTestSimComputing) return@LaunchedEffect
        if (!strategyTestM15SessionCache.sufficientForStrategyTestSimulation()) return@LaunchedEffect
        if (strategyTestVisibleResultsFresh()) return@LaunchedEffect
        scheduleStrategyTestResimOnly(reason = "params_change")
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
        MoexDiagnostics.log(
            context,
            "config",
            "thresholds real=${rt.entry}/${rt.exit} test=${st.entry}/${st.exit}",
        )
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

    LaunchedEffect(portfolioLookbackDays) {
        withContext(Dispatchers.IO) {
            savePortfolioLookbackDays(context, portfolioLookbackDays)
        }
    }

    LaunchedEffect(
        realTradeEntryThreshold,
        realTradeExitThreshold,
        strategyTestM15SessionCache.size,
        portfolioM15Points.size,
        portfolioLookbackDays,
        portfolioLeverage,
        portfolioCommissionPercent,
        dynamicThresholds.calculatedDate,
        portfolioLoading,
    ) {
        if (portfolioLoading) return@LaunchedEffect
        val entry = realTradeEntryThreshold ?: return@LaunchedEffect
        val exit = realTradeExitThreshold ?: return@LaunchedEffect
        if (m15SeriesForZSimulation().isEmpty()) return@LaunchedEffect
        confirmedPortfolioMetrics = withContext(Dispatchers.Default) {
            buildPortfolioTabSimulationMetricsForDisplay(
                entryThreshold = entry,
                exitThreshold = exit,
                dynamicCalculatedDate = dynamicThresholds.calculatedDate,
                leverage = portfolioLeverage,
                commissionPercentPerSide = portfolioCommissionPercent,
            )
        }
    }

    LaunchedEffect(strategyTestEntryThreshold, strategyTestExitThreshold) {
        val entry = strategyTestEntryThreshold ?: return@LaunchedEffect
        val exit = strategyTestExitThreshold ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            saveStrategyTestZThresholds(context, entry, exit)
        }
    }

    val signalJournalFingerprint = signalEvents.size to signalEvents.sumOf { it.timestampMillis + it.signalType.ordinal * 31L }

    LaunchedEffect(
        selectedTab,
        signalJournalFingerprint,
        confirmedPortfolioMetrics,
        strategyTestPortfolioMetrics,
        portfolioLookbackDays,
        strategyTestM15SessionCache.size,
        strategyTestEntryThreshold,
        strategyTestExitThreshold,
        realTradeEntryThreshold,
        realTradeExitThreshold,
        portfolioLeverage,
        portfolioCommissionPercent,
        strategyTestCompoundReturns,
        dynamicThresholds.entry,
        dynamicThresholds.exit,
        dynamicThresholds.calculatedDate,
    ) {
        if (selectedTab != MainTab.StrategyTest && selectedTab != MainTab.Portfolio) return@LaunchedEffect
        delay(350)
        if (selectedTab != MainTab.StrategyTest && selectedTab != MainTab.Portfolio) return@LaunchedEffect
        dailyReconciliation = withContext(Dispatchers.Default) {
            val today = LocalDate.now(ZoneId.of("Europe/Moscow"))
            val points = m15SeriesForZSimulation()
            if (points.size < 2) return@withContext null

            val portfolioEntry = (realTradeEntryThreshold ?: dynamicThresholds.entry)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
            val portfolioExit = (realTradeExitThreshold ?: dynamicThresholds.exit)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
            val testEntry = strategyTestEntryThreshold ?: dynamicThresholds.entry
            val testExit = strategyTestExitThreshold ?: dynamicThresholds.exit
            val testThresholds = DynamicThresholds(testEntry, testExit, dynamicThresholds.calculatedDate)
            val portfolioThresholds = DynamicThresholds(
                portfolioEntry,
                portfolioExit,
                dynamicThresholds.calculatedDate,
            )

            val confirmedToday = buildTodaySimPortfolioMetricsFromDayOpen(
                points = points,
                day = today,
                thresholds = portfolioThresholds,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = portfolioLeverage,
                commissionPercentPerSide = portfolioCommissionPercent,
                compoundReturns = false,
            )
            val simToday = buildTodaySimPortfolioMetricsFromDayOpen(
                points = points,
                day = today,
                thresholds = testThresholds,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = portfolioLeverage,
                commissionPercentPerSide = portfolioCommissionPercent,
                compoundReturns = strategyTestCompoundReturns,
            )

            val journalPos = inferJournalPositionBeforeDay(signalEvents, today)
            val simPosAtOpen = inferSimPositionAtDayOpen(
                points = points,
                day = today,
                thresholds = testThresholds,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = portfolioLeverage,
                commissionPercentPerSide = portfolioCommissionPercent,
            )
            val simSignals = collectZStrategySignalEdgesOnCalendarDay(
                points = points,
                day = today,
                initialPosition = simPosAtOpen,
                thresholds = testThresholds,
            ).size

            buildDailyPortfolioReconciliation(
                day = today,
                journalEvents = signalEvents,
                confirmed = confirmedToday,
                simulation = simToday,
                simEntryThreshold = testEntry,
                simExitThreshold = testExit,
                journalPositionAtDayOpen = journalPos,
                simPositionAtDayOpen = simPosAtOpen,
                simSignalsToday = simSignals,
                simExitMode = ZStrategyExitMode.FixedThreshold,
                simZPeakTrailZ = DEFAULT_STRATEGY_TEST_Z_PEAK_TRAIL,
            )
        }
    }

    LaunchedEffect(selectedTab, portfolioLookbackDays) {
        if (selectedTab != MainTab.Portfolio) return@LaunchedEffect
        ensurePortfolioTabLoaded()
    }

    LaunchedEffect(
        selectedTab,
        signalJournalFingerprint,
        sandboxSpreadExecReload,
        portfolioLeverage,
        portfolioCommissionPercent,
        realTradeEntryThreshold,
        realTradeExitThreshold,
        portfolioLedgerIncludeAuto,
        portfolioM15Points.size,
        strategyTestM15SessionCache.size,
        dynamicThresholds.calculatedDate,
    ) {
        if (selectedTab != MainTab.Portfolio) return@LaunchedEffect
        if (portfolioM15Points.size < 2) return@LaunchedEffect
        val uiKey = portfolioTabUiSessionKey()
        if (portfolioTabUiBuiltKey == uiKey) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            rebuildPortfolioUiFromPoints(portfolioM15Points)
        }
    }

    LaunchedEffect(
        selectedTab,
        portfolioM15Points,
        marketsM15DataEpoch,
        portfolioLeverage,
        portfolioCommissionPercent,
        sandboxSpreadExecReload,
        portfolioLedgerIncludeAuto,
        signalJournalFingerprint,
    ) {
        if (selectedTab != MainTab.Portfolio && selectedTab != MainTab.Markets) return@LaunchedEffect
        syncSandboxExecutionsEnrichment()
    }

    LaunchedEffect(selectedTab) {
        MoexDiagnostics.log(context, "ui", "tab=${selectedTab.label}")
        if (selectedTab != MainTab.Markets && selectedTab != MainTab.Portfolio && selectedTab != MainTab.StrategyTest) {
            clearStaleDataLoadProgress()
        }
    }

    LaunchedEffect(dataLoadSessions, dataLoadProgress?.phase) {
        if (dataLoadSessions == 0 && dataLoadProgress?.active == true) {
            dataLoadProgress = null
            return@LaunchedEffect
        }
        if (dataLoadSessions <= 0 && dataLoadProgress?.active != true) return@LaunchedEffect
        delay(90_000)
        if (dataLoadSessions > 0 || dataLoadProgress?.active == true) {
            MoexDiagnostics.log(context, "ui", "data_load_watchdog reset sessions=$dataLoadSessions phase=${dataLoadProgress?.phase}")
            forceResetDataLoadUi()
        }
    }

    LaunchedEffect(Unit) {
        selectedPeriod = selectedPeriod.coerceToMarketsUiPeriod()
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

    LaunchedEffect(selectedTab, marketsZChartPeriod) {
        if (selectedTab != MainTab.Markets || !initialMarketsRefreshDone) return@LaunchedEffect
        val period = marketsZChartPeriod.coerceToMarketsUiPeriod()
        if (lastGoodMarkets == null) {
            refreshData(showLoading = true, launchScope = scope, selectedPeriod = period)
            return@LaunchedEffect
        }
        if (marketsM15CoversPeriod(period) && marketsM15LoadedPeriod == period) {
            if (portfolio15mSeriesTailStale(marketsM15Source())) {
                scope.launch {
                    ensureMarketsM15ForPeriod(period, trackProgress = false)
                }
            }
            return@LaunchedEffect
        }
        ensureMarketsM15ForPeriod(
            period,
            mode = PortfolioM15LoadMode.CACHE_ONLY,
            trackProgress = false,
        )
        if (portfolio15mSeriesTailStale(marketsM15Source())) {
            scope.launch {
                ensureMarketsM15ForPeriod(period, trackProgress = false)
            }
        }
    }

    LaunchedEffect(selectedTab, configuration.orientation) {
        if (selectedTab == MainTab.Markets) {
            val coerced = marketsZChartPeriod.coerceToMarketsUiPeriod()
            if (coerced != marketsZChartPeriod) marketsZChartPeriod = coerced
            if (coerced != selectedPeriod) selectedPeriod = coerced
        }
    }

    LaunchedEffect(selectedTab, sandboxSpreadExecReload) {
        if (selectedTab != MainTab.Journal) return@LaunchedEffect
        if (sandboxSpreadExecReload == 0) return@LaunchedEffect
        pushNotificationLog = withContext(Dispatchers.IO) {
            loadPushNotificationLog(context.applicationContext)
        }
    }
    LaunchedEffect(realtimeEnabled, selectedPeriod, selectedTab, activityResumed, memoryPressureLevel) {
        if (!realtimeEnabled || selectedTab != MainTab.Markets || !activityResumed) return@LaunchedEffect
        while (true) {
            val interval = MoexMemoryPressure.autoPollIntervalMs(memoryPressureLevel)
            if (interval <= 0L) return@LaunchedEffect
            delay(interval)
            if (!mayRefreshMarkets(MarketsRefreshPolicy.AutoPoll)) continue
            refreshData(
                showLoading = false,
                launchScope = scope,
                selectedPeriod = selectedPeriod,
                refreshPolicy = MarketsRefreshPolicy.AutoPoll,
            )
        }
    }
    }
}
