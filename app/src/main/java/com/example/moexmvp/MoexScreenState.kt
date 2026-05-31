package com.example.moexmvp

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

@Stable
internal class MoexScreenState(val context: Context) {
    val refreshMutex = Mutex()

    var pendingAppUpdate by mutableStateOf<AppRemoteUpdate?>(null)
    var selectedTab by mutableStateOf(MainTab.Markets)
    var confirmedPortfolioMetrics by mutableStateOf<PortfolioMetrics?>(null)
    var confirmedPortfolioTableRows by mutableStateOf<List<PortfolioConfirmedTradeTableRow>>(emptyList())
    var strategyTestCompoundReturns by mutableStateOf(false)
    var portfolioLoading by mutableStateOf(false)
    var portfolioError by mutableStateOf<String?>(null)
    var portfolioLeverage by mutableStateOf(7.0)
    var portfolioCommissionPercent by mutableStateOf(0.04)
    var realTradeEntryThreshold by mutableStateOf<Double?>(null)
    var realTradeExitThreshold by mutableStateOf<Double?>(null)
    var strategyTestEntryThreshold by mutableStateOf<Double?>(null)
    var strategyTestExitThreshold by mutableStateOf<Double?>(null)
    var selectedPeriod by mutableStateOf(Period.OneDay)
    var realtimeEnabled by mutableStateOf(true)
    var isRefreshing by mutableStateOf(false)
    var realtimeError by mutableStateOf<String?>(null)
    var previousZScoreForAlert by mutableStateOf<Double?>(null)
    var dynamicThresholds by mutableStateOf(
        loadSavedDynamicThresholds(context)
            ?: DynamicThresholds(
                entry = DEFAULT_DYNAMIC_Z_ENTRY,
                exit = DEFAULT_DYNAMIC_Z_EXIT,
                calculatedDate = null
            )
    )
    var zStrategyPosition by mutableStateOf(loadSavedStrategyPosition(context))
    var dailySignalLimit by mutableStateOf(loadDailySignalLimit(context, LocalDate.now()))
    var signalEvents by mutableStateOf(loadStrategySignalEvents(context))
    var pushNotificationLog by mutableStateOf(loadPushNotificationLog(context))
    var state by mutableStateOf<UiState>(UiState.Loading)
    var lastGoodMarkets by mutableStateOf<UiState.Success?>(null)
    var marketsStale by mutableStateOf(false)
    var marketsM15Points by mutableStateOf<List<DataPoint>>(emptyList())
    var portfolioM15Points by mutableStateOf<List<DataPoint>>(emptyList())
    var portfolioPresets by mutableStateOf(loadPortfolioPresets(context))
    var robustCandidate by mutableStateOf<DynamicThresholds?>(null)
    var walkForwardBusy by mutableStateOf(false)
    var todayPnlHint by mutableStateOf<String?>(null)
    var pendingVirtualTrade by mutableStateOf<PendingVirtualTradeProposal?>(null)
    var sandboxExecState by mutableStateOf(TinkoffSandboxStorage.resolveExecUiState(context))
    var sandboxTokenInput by mutableStateOf("")
    var sandboxAccountInput by mutableStateOf("")
    var sandboxSpreadExecReload by mutableStateOf(0)
    var sandboxSpreadExecutions by mutableStateOf(TinkoffSandboxSpreadExecLog.loadRecent(context))
    var portfolioLedgerIncludeAuto by mutableStateOf(
        TinkoffSandboxStorage.isPortfolioLedgerIncludeAuto(context)
    )
    var executeSignalsOnSandbox by mutableStateOf(
        TinkoffSandboxStorage.isExecuteSignalsOnSandbox(context)
    )
    var sandboxSpreadAutoExecute by mutableStateOf(
        TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context)
    )
    var portfolioTestBusy by mutableStateOf(false)
    var showCloseAllPortfolioDialog by mutableStateOf(false)
    var closeAllPortfolioBusy by mutableStateOf(false)
    var bgMonitorToggleEpoch by mutableStateOf(0)
    var strategyTestPortfolioMetrics by mutableStateOf<PortfolioMetrics?>(null)
    var strategyTestSimComputing by mutableStateOf(false)
    var dailyReconciliation by mutableStateOf<DailyPortfolioReconciliation?>(null)
    var marketsZStrategyTapMetrics by mutableStateOf<PortfolioMetrics?>(null)
    var initialMarketsRefreshDone by mutableStateOf(false)

    /** Быстрый старт: снимок «Рынка» + 15м из SQLite до сети MOEX. */
    suspend fun hydrateMarketsFromLocalCache(preferredPeriod: Period = Period.OneDay) {
        withContext(Dispatchers.IO) {
            val snapshot = readMarketsSnapshotIfFresh(context, preferredPeriod)
                ?: Period.entries.firstNotNullOfOrNull { readMarketsSnapshotIfFresh(context, it) }
            snapshot?.let {
                lastGoodMarkets = it
                if (state is UiState.Loading) {
                    state = it
                    marketsStale = false
                }
            }
            if (marketsM15Points.isEmpty()) {
                runCatching {
                    loadPortfolio15mPointsForSignalMonitor(context, PortfolioM15LoadMode.CACHE_ONLY)
                }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { pts ->
                    marketsM15Points = pts
                    if (portfolioM15Points.isEmpty()) portfolioM15Points = pts
                }
            }
            syncSandboxExecutionsEnrichment()
        }
    }

    /** Только дневной ряд spread для портретных графиков; Z-график фильтруется локально. */
    suspend fun refreshMarketsDailyOnly(period: Period) {
        refreshMutex.withLock {
            isRefreshing = true
            try {
                when (val next = loadState(context, period)) {
                    is UiState.Success -> {
                        lastGoodMarkets = next
                        marketsStale = false
                        state = next
                        realtimeError = null
                    }
                    is UiState.Error -> {
                        realtimeError = next.message
                        if (lastGoodMarkets != null) {
                            marketsStale = true
                            state = lastGoodMarkets!!
                        } else {
                            state = next
                        }
                    }
                    is UiState.Empty -> {
                        if (lastGoodMarkets == null) state = UiState.Empty
                    }
                    UiState.Loading -> Unit
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    suspend fun refreshPortfolioUnlocked(m15LoadHint: PortfolioM15LoadMode? = null) {
        portfolioLoading = true
        portfolioError = null
        try {
            val till = LocalDate.now(moexZoneId)
            val from = till.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
            val m15Mode = m15LoadHint ?: resolvePortfolioM15LoadMode(context)
            val loaded = loadPortfolio15mSeriesEnsuringRecentTail(context, from, m15Mode)
            if (loaded.size < 2) {
                confirmedPortfolioMetrics = null
                confirmedPortfolioTableRows = emptyList()
                portfolioError = when (m15Mode) {
                    PortfolioM15LoadMode.CACHE_ONLY ->
                        "Нет 15-мин данных в кэше. Нажмите «Обновить» для загрузки с MOEX."
                    else ->
                        "Нет 15-мин данных (ISS / сеть). Попробуйте «MOEX заново»."
                }
                if (loaded.isNotEmpty()) {
                    portfolioM15Points = loaded
                    if (marketsM15Points.isEmpty()) marketsM15Points = loaded
                }
                syncSandboxExecutionsEnrichment()
                return@refreshPortfolioUnlocked
            }
            val points = loaded
            portfolioM15Points = loaded
            if (marketsM15Points.isEmpty()) marketsM15Points = loaded
            val desc =
                "15 мин (ISS 10m→15m) · $PORTFOLIO_M15_LOOKBACK_DAYS дн. (${points.first().tradeDate}…${points.last().tradeDate})"

            val entryRt = (realTradeEntryThreshold ?: dynamicThresholds.entry)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
            val exitRt = (realTradeExitThreshold ?: dynamicThresholds.exit)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
            if (realTradeEntryThreshold == null) realTradeEntryThreshold = entryRt
            if (realTradeExitThreshold == null) realTradeExitThreshold = exitRt

            val entrySt = (strategyTestEntryThreshold ?: dynamicThresholds.entry)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
            val exitSt = (strategyTestExitThreshold ?: dynamicThresholds.exit)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
            if (strategyTestEntryThreshold == null) strategyTestEntryThreshold = entrySt
            if (strategyTestExitThreshold == null) strategyTestExitThreshold = exitSt

            val eventsAll = loadStrategySignalEvents(
                context = context,
                fromTimestampMillis = points.firstOrNull()?.timestampMillis
            )
            val ledger = loadPortfolioExecutionLedger(context)
            val ledgerIncludeAuto = TinkoffSandboxStorage.isPortfolioLedgerIncludeAuto(context)
            val filteredEvents = journalEventsForExecutionPortfolioTab(
                allEvents = eventsAll,
                ledger = ledger,
                portfolioLedgerIncludeAuto = ledgerIncludeAuto
            )
            val pushLog = withContext(Dispatchers.IO) { loadPushNotificationLog(context) }
            val executed = withContext(Dispatchers.Default) {
                buildExecutedPortfolioWithTable(
                    points = points,
                    events = filteredEvents,
                    ledger = ledger,
                    pushLog = pushLog,
                    notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                    leverage = portfolioLeverage,
                    commissionPercentPerSide = portfolioCommissionPercent,
                    periodDescription = desc
                )
            }
            confirmedPortfolioMetrics = executed.metrics
            val sandboxRaw = withContext(Dispatchers.IO) {
                TinkoffSandboxSpreadExecLog.loadRecent(context)
            }
            val (closedFromOpens, opensAfterJournalClose) = withContext(Dispatchers.Default) {
                buildClosedRowsFromSandboxOpensAndJournalExits(
                    openExecutions = sandboxRaw,
                    allJournalEvents = eventsAll,
                    points = points,
                    ledger = ledger,
                    pushLog = pushLog,
                    notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                    leverage = portfolioLeverage,
                    commissionPercentPerSide = portfolioCommissionPercent,
                    portfolioLedgerIncludeAuto = ledgerIncludeAuto
                )
            }
            val mergedClosed = mergeClosedPortfolioTableRows(executed.tableRows, closedFromOpens)
            confirmedPortfolioTableRows = filterConfirmedTableRowsByPortfolioMode(
                mergedClosed,
                ledgerIncludeAuto
            )
            sandboxSpreadExecutions = withContext(Dispatchers.IO) {
                val modeFiltered = filterSandboxExecutionsByPortfolioMode(
                    opensAfterJournalClose,
                    ledgerIncludeAuto
                )
                TinkoffSandboxSpreadExecLog.enrichForDisplay(
                    context = context,
                    executions = modeFiltered,
                    points = points,
                    notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                    leverage = portfolioLeverage,
                    commissionPercentPerSide = portfolioCommissionPercent,
                    journalEvents = eventsAll
                )
            }
            portfolioError = if (portfolio15mSeriesTailStale(points)) {
                val todayMsk = LocalDate.now(moexZoneId)
                "15м ряд обрывается на ${points.last().tradeDate} (сегодня МСК $todayMsk, нужны свежие бары с MOEX). " +
                    "Нажмите «Обновить» или «MOEX заново» при сети."
            } else {
                null
            }
        } finally {
            portfolioLoading = false
        }
    }

    suspend fun refreshPortfolio(m15LoadHint: PortfolioM15LoadMode? = null) {
        refreshMutex.withLock {
            refreshPortfolioUnlocked(m15LoadHint)
        }
    }

    suspend fun refreshData(showLoading: Boolean, launchScope: CoroutineScope, selectedPeriod: Period) {
        refreshMutex.withLock {
            if (showLoading) {
                state = UiState.Loading
            } else {
                isRefreshing = true
            }

            when (val next = loadState(context, selectedPeriod)) {
                is UiState.Success -> {
                    lastGoodMarkets = next
                    marketsStale = false
                    state = next
                    realtimeError = null
                    val fromDiskCache = next.marketsDataSource == MarketsDataSource.FifteenMinuteCache
                    val thresholdUpdate = ensureDynamicThresholds(context)
                    dynamicThresholds = thresholdUpdate.thresholds
                    val backgroundMonitorEnabled = SignalForegroundService.isBackgroundMonitorEnabled(context)
                    var loadedM15ForMarkets: List<DataPoint>? = null
                    suspend fun loadMarketsM15PointsOnce(): List<DataPoint> {
                        val cached = loadedM15ForMarkets
                        if (cached != null) return cached
                        val loaded = withContext(Dispatchers.IO) {
                            loadPortfolio15mPointsForSignalMonitor(context)
                        }
                        loadedM15ForMarkets = loaded
                        marketsM15Points = loaded
                        portfolioM15Points = loaded
                        return loaded
                    }
                    if (!fromDiskCache) {
                        runCatching { loadMarketsM15PointsOnce() }
                    } else if (showLoading || marketsM15Points.isEmpty()) {
                        runCatching { loadMarketsM15PointsOnce() }
                    }
                    if (thresholdUpdate.recalculated &&
                        DYNAMIC_Z_DAILY_RECALC_ENABLED &&
                        !backgroundMonitorEnabled &&
                        !fromDiskCache
                    ) {
                        showDynamicZThresholdsPushNotification(
                            context = context,
                            entry = dynamicThresholds.entry,
                            exit = dynamicThresholds.exit,
                            dateText = dynamicThresholds.calculatedDate ?: LocalDate.now().toString()
                        )
                    }
                    dailySignalLimit = loadDailySignalLimit(context, LocalDate.now())
                    if (!fromDiskCache) {
                        val m15ForSignal = loadMarketsM15PointsOnce()
                        if (!backgroundMonitorEnabled && m15ForSignal.size >= 2) {
                            val signalThresholds = DynamicThresholds(
                                entry = (realTradeEntryThreshold ?: dynamicThresholds.entry)
                                    .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                                exit = (realTradeExitThreshold ?: dynamicThresholds.exit)
                                    .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                                calculatedDate = dynamicThresholds.calculatedDate
                            )
                            val lastPt = m15ForSignal.last()
                            val latestZScore = lastPt.zScore
                            val latestSpreadPercent = lastPt.spreadPercent
                            val latestTimestampMillis = lastPt.timestampMillis
                            val edgeSignal = zStrategySignalOnLast15mBar(
                                points = m15ForSignal,
                                position = zStrategyPosition,
                                thresholds = signalThresholds
                            )
                            if (edgeSignal != ZStrategySignal.None &&
                                tryConsume15mStrategySignalEdge(
                                    context,
                                    latestTimestampMillis,
                                    edgeSignal
                                )
                            ) {
                            when (edgeSignal) {
                                ZStrategySignal.EnterLong -> {
                                zStrategyPosition = ZStrategyPosition.Long
                                saveStrategyPosition(context, zStrategyPosition)
                                recordStrategySignalEvent(
                                    context = context,
                                    signalType = StrategySignalType.EnterLong,
                                    zScore = latestZScore,
                                    timestampMillis = latestTimestampMillis
                                )
                                pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                                if (!backgroundMonitorEnabled && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                                    val sent = showZStrategySignalPushNotification(
                                        context = context,
                                        title = "Вход: LONG TATN / SHORT TATNP",
                                        body = String.format(
                                            Locale.US,
                                            "Z <= -%.1f (текущий Z=%.2f)",
                                            signalThresholds.entry,
                                            latestZScore
                                        ),
                                        virtualTradeTap = entryVirtualTradeTapIfManualAccept(
                                            context,
                                            StrategySignalType.EnterLong,
                                            latestZScore,
                                            latestTimestampMillis
                                        )
                                    )
                                    if (sent) {
                                        dailySignalLimit = dailySignalLimit.copy(sentCount = dailySignalLimit.sentCount + 1)
                                        saveDailySignalLimit(context, dailySignalLimit)
                                    }
                                }
                                launchScope.launch(Dispatchers.IO) {
                                    val ran = runSandboxAutoEntryIfNeeded(
                                        context.applicationContext,
                                        StrategySignalType.EnterLong,
                                        latestZScore,
                                        latestTimestampMillis,
                                        latestSpreadPercent
                                    )
                                    if (ran) {
                                        withContext(Dispatchers.Main) {
                                            pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                                            sandboxSpreadExecReload++
                                        }
                                    }
                                }
                            }

                            ZStrategySignal.EnterShort -> {
                                zStrategyPosition = ZStrategyPosition.Short
                                saveStrategyPosition(context, zStrategyPosition)
                                recordStrategySignalEvent(
                                    context = context,
                                    signalType = StrategySignalType.EnterShort,
                                    zScore = latestZScore,
                                    timestampMillis = latestTimestampMillis
                                )
                                pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                                if (!backgroundMonitorEnabled && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                                    val sent = showZStrategySignalPushNotification(
                                        context = context,
                                        title = "Вход: LONG TATNP / SHORT TATN",
                                        body = String.format(
                                            Locale.US,
                                            "Z >= +%.1f (текущий Z=%.2f)",
                                            signalThresholds.entry,
                                            latestZScore
                                        ),
                                        virtualTradeTap = entryVirtualTradeTapIfManualAccept(
                                            context,
                                            StrategySignalType.EnterShort,
                                            latestZScore,
                                            latestTimestampMillis
                                        )
                                    )
                                    if (sent) {
                                        dailySignalLimit = dailySignalLimit.copy(sentCount = dailySignalLimit.sentCount + 1)
                                        saveDailySignalLimit(context, dailySignalLimit)
                                    }
                                }
                                launchScope.launch(Dispatchers.IO) {
                                    val ran = runSandboxAutoEntryIfNeeded(
                                        context.applicationContext,
                                        StrategySignalType.EnterShort,
                                        latestZScore,
                                        latestTimestampMillis,
                                        latestSpreadPercent
                                    )
                                    if (ran) {
                                        withContext(Dispatchers.Main) {
                                            pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                                            sandboxSpreadExecReload++
                                        }
                                    }
                                }
                            }

                            ZStrategySignal.ExitLong -> {
                                zStrategyPosition = ZStrategyPosition.Flat
                                saveStrategyPosition(context, zStrategyPosition)
                                recordStrategySignalEvent(
                                    context = context,
                                    signalType = StrategySignalType.ExitLong,
                                    zScore = latestZScore,
                                    timestampMillis = latestTimestampMillis
                                )
                                clearPendingVirtualTradeProposal(context)
                                pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                                if (!backgroundMonitorEnabled && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                                    val sent = showZStrategySignalPushNotification(
                                        context = context,
                                        title = "Выход: закрыть LONG TATN / SHORT TATNP",
                                        body = String.format(
                                            Locale.US,
                                            "Z >= -%.1f (текущий Z=%.2f)",
                                            signalThresholds.exit,
                                            latestZScore
                                        )
                                    )
                                    if (sent) {
                                        dailySignalLimit = dailySignalLimit.copy(sentCount = dailySignalLimit.sentCount + 1)
                                        saveDailySignalLimit(context, dailySignalLimit)
                                    }
                                }
                            }

                            ZStrategySignal.ExitShort -> {
                                zStrategyPosition = ZStrategyPosition.Flat
                                saveStrategyPosition(context, zStrategyPosition)
                                recordStrategySignalEvent(
                                    context = context,
                                    signalType = StrategySignalType.ExitShort,
                                    zScore = latestZScore,
                                    timestampMillis = latestTimestampMillis
                                )
                                clearPendingVirtualTradeProposal(context)
                                pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                                if (!backgroundMonitorEnabled && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                                    val sent = showZStrategySignalPushNotification(
                                        context = context,
                                        title = "Выход: закрыть LONG TATNP / SHORT TATN",
                                        body = String.format(
                                            Locale.US,
                                            "Z <= +%.1f (текущий Z=%.2f)",
                                            signalThresholds.exit,
                                            latestZScore
                                        )
                                    )
                                    if (sent) {
                                        dailySignalLimit = dailySignalLimit.copy(sentCount = dailySignalLimit.sentCount + 1)
                                        saveDailySignalLimit(context, dailySignalLimit)
                                    }
                                }
                            }

                            ZStrategySignal.None -> Unit
                            }
                            }
                            previousZScoreForAlert = latestZScore
                        }
                    }
                    signalEvents = loadStrategySignalEvents(context)
                }

                is UiState.Empty -> {
                    state = UiState.Empty
                    realtimeError = null
                }

                is UiState.Error -> {
                    realtimeError = next.message
                    if (lastGoodMarkets != null) {
                        marketsStale = true
                        state = lastGoodMarkets!!
                    } else {
                        state = next
                        marketsStale = false
                    }
                }

                UiState.Loading -> Unit
            }
            isRefreshing = false
            initialMarketsRefreshDone = true
        }
    }

}
