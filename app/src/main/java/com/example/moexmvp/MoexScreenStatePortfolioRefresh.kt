package com.example.moexmvp

import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

internal suspend fun MoexScreenState.refreshPortfolioUnlocked(m15LoadHint: PortfolioM15LoadMode? = null) {
        portfolioLoading = true
        portfolioError = null
        try {
            val m15Mode = m15LoadHint ?: resolvePortfolioM15LoadMode(context)
            val loaded = loadM15SeriesForPortfolio(m15Mode)
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
                "15 мин (ISS 10m→15m) · $PORTFOLIO_TAB_M15_LOOKBACK_DAYS дн. (${points.first().tradeDate}…${points.last().tradeDate})"

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

internal suspend fun MoexScreenState.refreshPortfolio(m15LoadHint: PortfolioM15LoadMode? = null) {
        refreshMutex.withLock {
            refreshPortfolioUnlocked(m15LoadHint)
        }
    }

internal suspend fun MoexScreenState.refreshData(
        showLoading: Boolean,
        launchScope: CoroutineScope,
        selectedPeriod: Period,
        preferBackground: Boolean = false,
    ) {
        val hasDisplayCache = lastGoodMarkets != null
        val blockUi = showLoading && !hasDisplayCache

        suspend fun performRefresh() {
            withDataLoadSession {
                val progressSink = dataLoadProgressSink()
                val skipDailyNetwork = lastGoodMarkets != null &&
                    marketsSnapshotFreshEnough(context, selectedPeriod)
                refreshMutex.withLock {
                    if (blockUi) {
                        state = UiState.Loading
                    }

                    if (skipDailyNetwork) {
                        lastGoodMarkets?.let { cached ->
                            state = cached
                            marketsStale = false
                        }
                        if (marketsM15Points.size < 2) {
                            loadM15ForMonitor(PortfolioM15LoadMode.CACHE_ONLY)
                                .takeIf { it.size >= 2 }?.let { loaded ->
                                marketsM15Points = loaded
                                if (portfolioM15Points.isEmpty()) portfolioM15Points = loaded
                            }
                        }
                        when {
                            marketsM15Points.size < 2 -> {
                                val loaded = loadM15ForMonitor()
                                if (loaded.size >= 2) {
                                    marketsM15Points = loaded
                                    portfolioM15Points = loaded
                                }
                            }
                            portfolio15mSeriesTailStale(marketsM15Points) -> {
                                launchScope.launch {
                                    val loaded = loadM15ForMonitor()
                                    if (loaded.size >= 2) {
                                        marketsM15Points = loaded
                                        if (portfolioM15Points.isEmpty()) portfolioM15Points = loaded
                                    }
                                }
                            }
                        }
                        return@withLock
                    }

                    reportDataLoadProgress(
                        DataLoadProgress(
                            phase = DataLoadPhase.MarketsDaily,
                            marketsPeriodLabel = selectedPeriod.label,
                            detail = "дневной спрэд TATN/TATNP",
                        )
                    )
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
                            loadPortfolio15mPointsForSignalMonitor(
                                context,
                                onProgress = progressSink,
                            )
                        }
                        loadedM15ForMarkets = loaded
                        marketsM15Points = loaded
                        portfolioM15Points = loaded
                        return loaded
                    }
                    val m15CachedReady = marketsM15Points.size >= 2 &&
                        !portfolio15mSeriesTailStale(marketsM15Points)
                    val deferM15Network = preferBackground && m15CachedReady
                    if (deferM15Network) {
                        launchScope.launch {
                            val loaded = loadM15ForMonitor()
                            if (loaded.size >= 2) {
                                loadedM15ForMarkets = loaded
                                marketsM15Points = loaded
                                portfolioM15Points = loaded
                            }
                        }
                    } else if (!fromDiskCache || marketsM15Points.isEmpty()) {
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
                    if (!fromDiskCache && !deferM15Network) {
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
                }
                initialMarketsRefreshDone = true
            }
        }

        if (hasDisplayCache && preferBackground) {
            initialMarketsRefreshDone = true
            launchScope.launch {
                isRefreshing = true
                try {
                    performRefresh()
                } finally {
                    isRefreshing = false
                }
            }
            return
        }

        if (!blockUi) isRefreshing = true
        try {
            performRefresh()
        } finally {
            isRefreshing = false
        }
    }
