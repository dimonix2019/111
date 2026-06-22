package com.example.moexmvp

import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/** Пересборка таблицы/метрик портфеля из 15м ряда (in-memory / SQLite). */
internal suspend fun MoexScreenState.rebuildPortfolioUiFromPoints(pointsHint: List<DataPoint>? = null) {
    val points = pointsHint?.takeIf { it.size >= 2 } ?: portfolioExecutionReplayPoints()
    if (points.size < 2) return
    val desc =
        "15 мин (ISS 10m→15m) · ${portfolioLookbackPeriodLabel(portfolioLookbackDays)} (${points.first().tradeDate}…${points.last().tradeDate})"

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
        fromTimestampMillis = points.firstOrNull()?.timestampMillis,
    )
    val ledger = loadPortfolioExecutionLedger(context)
    val ledgerIncludeAuto = TinkoffSandboxStorage.isPortfolioLedgerIncludeAuto(context)
    val filteredEvents = journalEventsForExecutionPortfolioTab(
        allEvents = eventsAll,
        ledger = ledger,
        portfolioLedgerIncludeAuto = ledgerIncludeAuto,
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
            periodDescription = desc,
        )
    }
    confirmedPortfolioMetrics = buildPortfolioTabSimulationMetricsForDisplay(
        entryThreshold = entryRt,
        exitThreshold = exitRt,
        dynamicCalculatedDate = dynamicThresholds.calculatedDate,
        leverage = portfolioLeverage,
        commissionPercentPerSide = portfolioCommissionPercent,
    ) ?: executed.metrics
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
            portfolioLedgerIncludeAuto = ledgerIncludeAuto,
            pnlLeverage = portfolioPnlLeverageMultiplier(currentExecutionMode(context), portfolioLeverage),
        )
    }
    val prodBrokerClosed = if (currentExecutionMode(context) == TinkoffExecutionMode.Prod) {
        withContext(Dispatchers.IO) {
            buildClosedRowsFromProdBrokerLog(
                records = TinkoffClosedSpreadExecLog.loadRecent(context),
                journalEvents = eventsAll,
                pushLog = pushLog,
                commissionPercentPerSide = portfolioCommissionPercent,
            )
        }
    } else {
        emptyList()
    }
    val mergedClosed = mergePortfolioClosedTableRowsForMode(
        mode = currentExecutionMode(context),
        fromReplay = executed.tableRows,
        fromOpens = closedFromOpens,
        fromProdBroker = prodBrokerClosed,
    )
    confirmedPortfolioTableRows = filterConfirmedTableRowsByPortfolioMode(
        mergedClosed,
        ledgerIncludeAuto,
        executionMode = currentExecutionMode(context),
    )
    val modeFiltered = filterSandboxExecutionsByPortfolioMode(
        opensAfterJournalClose,
        ledgerIncludeAuto,
    )
    val brokerLegPnl = if (currentExecutionMode(context) == TinkoffExecutionMode.Prod) {
        modeFiltered.firstOrNull()?.signalType?.let { signal ->
            withContext(Dispatchers.IO) { loadProdSpreadBrokerSnapshot(context, signal) }
        }
    } else {
        null
    }
    val pnlLeverage = portfolioPnlLeverageMultiplier(currentExecutionMode(context), portfolioLeverage)
    sandboxSpreadExecutions = withContext(Dispatchers.IO) {
        TinkoffSandboxSpreadExecLog.enrichForDisplay(
            context = context,
            executions = modeFiltered,
            points = points,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = portfolioLeverage,
            commissionPercentPerSide = portfolioCommissionPercent,
            journalEvents = eventsAll,
            pnlLeverage = pnlLeverage,
            brokerLegPnl = brokerLegPnl,
        )
    }
    portfolioError = portfolioStaleMoexWarning(
        points = portfolioM15Points.ifEmpty { points },
        prodBrokerPnlReady = brokerLegPnl != null,
    )
    portfolioTabUiBuiltKey = portfolioTabUiSessionKey()
}

/**
 * Открытие вкладки «Портфель»: in-memory → rebuild; SQLite только если ряда нет;
 * MOEX INCREMENTAL — только если хвост устарел.
 */
internal suspend fun MoexScreenState.ensurePortfolioTabLoaded() {
    if (currentExecutionMode(context) == TinkoffExecutionMode.Prod) {
        refreshProdOpenTradesFromBroker()
    }
    val uiKey = portfolioTabUiSessionKey()
    if (portfolioTabUiBuiltKey == uiKey && portfolioM15Points.size >= 2) {
        if (portfolio15mSeriesIntradayStale(portfolioM15Points)) {
            refreshPortfolioM15TailSilent()
        } else {
            refreshM15LiveFormingTail(reason = "portfolio_tab")
        }
        return
    }

    val memoryOk = portfolioM15Points.size >= 2 &&
        m15PointsCoverPortfolioLookback(portfolioM15Points, portfolioLookbackDays)

    if (memoryOk && !portfolio15mSeriesIntradayStale(portfolioM15Points)) {
        rebuildPortfolioUiFromPoints(portfolioM15Points)
        return
    }

    if (memoryOk) {
        refreshPortfolio(PortfolioM15LoadMode.INCREMENTAL, trackProgress = false)
        return
    }

    val mode = PortfolioM15LoadMode.CACHE_ONLY
    refreshPortfolio(mode, trackProgress = false)
}

/**
 * Каждые 15–30 с: 10м→15м с MOEX + пересчёт Z на хвосте ~2 ч (без persisted).
 */
internal suspend fun MoexScreenState.refreshM15LiveFormingTail(reason: String) {
    if (!activityResumed) return
    if (MoexMemoryPressure.shouldPauseMarkets1mQuotesRefresh(memoryPressureLevel)) return
    val lookback = when (selectedTab) {
        MainTab.Markets -> marketsM15LookbackDays(selectedPeriod.coerceToMarketsUiPeriod())
            .coerceAtLeast(portfolioLookbackDays)
        else -> portfolioLookbackDays
    }
    val loaded = try {
        refreshPortfolio15mLiveFormingTailFromMoex(context, lookback)
    } catch (t: Throwable) {
        MoexDiagnostics.logError(context, "m15_z", t, "live_tail failed reason=$reason")
        null
    } ?: run {
        MoexDiagnostics.log(context, "m15_tail", "live_forming_skip no_rows lookback=$lookback reason=$reason")
        return
    }
    MoexDiagnostics.log(context, "m15_tail", "live_forming reason=$reason tab=${selectedTab.label}")
    portfolioM15Points = loaded
    publishMarketsLiveZFromPoints(loaded)
    when (selectedTab) {
        MainTab.Markets -> storeMarketsM15(loaded)
        MainTab.Portfolio -> {
            if (marketsM15Source().isEmpty()) storeMarketsM15(loaded)
            rebuildPortfolioUiFromPoints(loaded)
        }
        MainTab.Journal -> {
            if (marketsM15Source().isEmpty()) storeMarketsM15(loaded)
        }
        else -> Unit
    }
}

/** Фоновая догрузка хвоста 15м (MOEX INCREMENTAL) без progress overlay. */
internal suspend fun MoexScreenState.refreshPortfolioM15TailSilent() {
    refreshMutex.withLock {
        if (portfolioM15Points.size < 2 || !portfolio15mSeriesIntradayStale(portfolioM15Points)) return@withLock
        portfolioLoading = true
        try {
            val loaded = loadM15SeriesForPortfolio(
                PortfolioM15LoadMode.INCREMENTAL,
                trackProgress = false,
            )
            if (loaded.size >= 2) {
                portfolioM15Points = loaded
                if (marketsM15Source().isEmpty()) storeMarketsM15(loaded)
                rebuildPortfolioUiFromPoints(loaded)
            }
        } finally {
            portfolioLoading = false
        }
    }
}

/** Догрузка 15м с MOEX, если хвост устарел или нет баров за сегодня (МСК). */
internal suspend fun MoexScreenState.refreshM15TailIfIntradayStale(reason: String) {
    if (selectedTab != MainTab.Markets) {
        refreshM15LiveFormingTail(reason = "${reason}_forming")
    }
    val src = when (selectedTab) {
        MainTab.Markets -> marketsM15Source().takeIf { it.size >= 2 } ?: portfolioM15Points
        else -> portfolioM15Points.takeIf { it.size >= 2 } ?: marketsM15Source()
    }
    if (src.size < 2 || !portfolio15mSeriesNeedsMoexRefresh(src)) return
    MoexDiagnostics.log(context, "m15_tail", "refresh reason=$reason tab=${selectedTab.label}")
    when (selectedTab) {
        MainTab.Markets -> {
            val loaded = loadM15ForMarkets(
                mode = PortfolioM15LoadMode.INCREMENTAL,
                wrapInSession = false,
            )
            if (loaded.size >= 2) {
                storeMarketsM15(loaded)
                portfolioM15Points = loaded
                publishMarketsLiveZFromPoints(loaded)
            }
        }
        MainTab.Portfolio -> refreshPortfolioM15TailSilent()
        else -> {
            refreshMutex.withLock {
                val loaded = loadM15SeriesForPortfolio(
                    PortfolioM15LoadMode.INCREMENTAL,
                    trackProgress = false,
                )
                if (loaded.size >= 2) {
                    portfolioM15Points = loaded
                    if (marketsM15Source().isEmpty()) storeMarketsM15(loaded)
                }
            }
        }
    }
}

/** После восстановления сети / onResume: догрузить 15м и при необходимости обновить вкладку «Рынок». */
internal suspend fun MoexScreenState.refreshAfterConnectivityRestore(
    reason: String,
    launchScope: CoroutineScope,
) {
    MoexDiagnostics.log(context, "network", "refresh_after_connectivity reason=$reason tab=${selectedTab.label}")
    refreshM15TailIfIntradayStale(reason = reason)
    if (selectedTab == MainTab.Markets && activityResumed) {
        val period = selectedPeriod.coerceToMarketsUiPeriod()
        val m15 = marketsM15Source()
        if (m15.size >= 2 && portfolio15mSeriesNeedsMoexRefresh(m15)) {
            refreshData(
                showLoading = false,
                launchScope = launchScope,
                selectedPeriod = period,
                refreshPolicy = MarketsRefreshPolicy.UserInitiated,
            )
        } else if (m15.size >= 2) {
            bumpMarketsLoadedAtFromM15(m15)
        }
    }
}

internal suspend fun MoexScreenState.refreshPortfolioUnlocked(
    m15LoadHint: PortfolioM15LoadMode? = null,
    trackProgress: Boolean = shouldTrackDataLoadProgress(),
) {
        portfolioLoading = true
        portfolioError = null
        try {
            val m15Mode = m15LoadHint ?: resolvePortfolioM15LoadMode(context)
            val loaded = loadM15SeriesForPortfolio(m15Mode, trackProgress = trackProgress)
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
                    if (marketsM15Source().isEmpty()) storeMarketsM15(loaded)
                }
                syncSandboxExecutionsEnrichment()
                portfolioTabUiBuiltKey = 0L
                return@refreshPortfolioUnlocked
            }
            portfolioM15Points = loaded
            if (marketsM15Source().isEmpty()) storeMarketsM15(loaded)
            rebuildPortfolioUiFromPoints(loaded)
        } finally {
            portfolioLoading = false
        }
    }

internal suspend fun MoexScreenState.refreshPortfolio(
    m15LoadHint: PortfolioM15LoadMode? = null,
    trackProgress: Boolean = shouldTrackDataLoadProgress(),
) {
        refreshMutex.withLock {
            refreshPortfolioUnlocked(m15LoadHint, trackProgress)
        }
    }

internal suspend fun MoexScreenState.refreshData(
        showLoading: Boolean,
        launchScope: CoroutineScope,
        selectedPeriod: Period,
        preferBackground: Boolean = false,
        refreshPolicy: MarketsRefreshPolicy = MarketsRefreshPolicy.UserInitiated,
    ) {
        if (!mayRefreshMarkets(refreshPolicy)) {
            MoexDiagnostics.log(
                context,
                "ui",
                "refreshData skip policy=$refreshPolicy tab=${selectedTab.label} resumed=$activityResumed mem=$memoryPressureLevel",
            )
            return
        }
        MoexDiagnostics.log(context, "ui", "refreshData start period=$selectedPeriod showLoading=$showLoading")
        MoexDiagnostics.logMemory(context, "refreshData_start")
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
                        if (marketsM15Source().size < 2) {
                            loadM15ForMarkets(
                                PortfolioM15LoadMode.CACHE_ONLY,
                                wrapInSession = false,
                            ).takeIf { it.size >= 2 }?.let { loaded ->
                                storeMarketsM15(loaded)
                                if (portfolioM15Points.isEmpty()) portfolioM15Points = loaded
                            }
                        }
                        when {
                            marketsM15Source().size < 2 -> {
                                val loaded = loadM15ForMarkets(wrapInSession = false)
                                if (loaded.size >= 2) {
                                    storeMarketsM15(loaded)
                                    portfolioM15Points = loaded
                                }
                            }
                            portfolio15mSeriesIntradayStale(marketsM15Source()) -> {
                                val loaded = loadM15ForMarkets(
                                    mode = PortfolioM15LoadMode.INCREMENTAL,
                                    wrapInSession = false,
                                )
                                if (loaded.size >= 2) {
                                    storeMarketsM15(loaded)
                                    if (portfolioM15Points.isEmpty()) portfolioM15Points = loaded
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
                        val loaded = loadM15ForMarkets(
                            mode = PortfolioM15LoadMode.INCREMENTAL,
                            wrapInSession = false,
                        )
                        loadedM15ForMarkets = loaded
                        storeMarketsM15(loaded)
                        portfolioM15Points = loaded
                        return loaded
                    }
                    val m15CachedReady = marketsM15Source().size >= 2 &&
                        !portfolio15mSeriesIntradayStale(marketsM15Source())
                    val deferM15Network = preferBackground && m15CachedReady
                    if (deferM15Network) {
                        launchScope.launch {
                            val loaded = loadM15ForMarkets(wrapInSession = false)
                            if (loaded.size >= 2) {
                                loadedM15ForMarkets = loaded
                                storeMarketsM15(loaded)
                                portfolioM15Points = loaded
                            }
                        }
                    } else if (!fromDiskCache || marketsM15Source().isEmpty()) {
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
                    val monitorStale = backgroundMonitorEnabled && MoexWatchdog.isServiceHeartbeatStale(context)
                    val runPortfolioSignals = !backgroundMonitorEnabled || monitorStale
                    if (runPortfolioSignals) {
                        val m15SignalMode = if (deferM15Network || fromDiskCache) {
                            PortfolioM15LoadMode.CACHE_ONLY
                        } else {
                            PortfolioM15LoadMode.INCREMENTAL
                        }
                        val m15Raw = withContext(Dispatchers.IO) {
                            loadZStrategySignalSeries(context, m15SignalMode)
                        }
                        if (m15Raw.size >= 2) {
                            val m15ForSignal = prepareM15PointsForZStrategySignalDetection(m15Raw)
                            val signalThresholds = loadRealTradeZThresholds(context, dynamicThresholds)
                            maybeBackfillMissedLiveZSignalsAfterStaleZFix(context, m15ForSignal, signalThresholds)
                            val signalLastProcessed = resolveLastProcessed15mBarTimestampForReplay(context)
                            val (signalEdges, replayPosition) = collectZStrategy15mSignalEdgesSinceProcessedBar(
                                points = m15ForSignal,
                                lastProcessedBarTimestampMillis = signalLastProcessed,
                                initialPosition = zStrategyPosition,
                                thresholds = signalThresholds,
                            )
                            zStrategyReplayBarIndexRange(m15ForSignal, signalLastProcessed)?.let { range ->
                                persistM15LiveBarSnapshots(
                                    context,
                                    range.map { m15ForSignal[it] },
                                )
                            }
                            for (edge in signalEdges) {
                                val edgeSignal = edge.signal
                                val bar = edge.bar
                                val latestZScore = bar.zScore
                                val latestSpreadPercent = bar.spreadPercent
                                val latestTimestampMillis = bar.timestampMillis
                                if (is15mStrategySignalEdgeConsumed(context, latestTimestampMillis, edgeSignal)) {
                                    zStrategyPosition = edge.positionAfter
                                    continue
                                }
                                val signalType = zStrategySignalToStrategySignalType(edgeSignal)
                                val recorded = recordStrategySignalEvent(
                                    context = context,
                                    signalType = signalType,
                                    zScore = latestZScore,
                                    timestampMillis = latestTimestampMillis,
                                )
                                mark15mStrategySignalEdgeConsumed(context, latestTimestampMillis, edgeSignal)
                                zStrategyPosition = edge.positionAfter
                                saveStrategyPosition(context, zStrategyPosition)
                                if (!recorded) continue
                                when (edgeSignal) {
                                ZStrategySignal.EnterLong -> {
                                pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                                if (runPortfolioSignals && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
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
                                pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                                if (runPortfolioSignals && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
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
                                clearPendingVirtualTradeProposal(context)
                                pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                                if (runPortfolioSignals && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
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
                                launchScope.launch(Dispatchers.IO) {
                                    val ran = runSandboxAutoExitIfNeeded(
                                        context.applicationContext,
                                        StrategySignalType.ExitLong,
                                        latestZScore,
                                        latestTimestampMillis,
                                    )
                                    if (ran) {
                                        withContext(Dispatchers.Main) {
                                            sandboxSpreadExecReload++
                                        }
                                    }
                                }
                            }

                            ZStrategySignal.ExitShort -> {
                                clearPendingVirtualTradeProposal(context)
                                pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                                if (runPortfolioSignals && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
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
                                launchScope.launch(Dispatchers.IO) {
                                    val ran = runSandboxAutoExitIfNeeded(
                                        context.applicationContext,
                                        StrategySignalType.ExitShort,
                                        latestZScore,
                                        latestTimestampMillis,
                                    )
                                    if (ran) {
                                        withContext(Dispatchers.Main) {
                                            sandboxSpreadExecReload++
                                        }
                                    }
                                }
                            }

                            ZStrategySignal.None -> Unit
                            }
                            }
                            if (shouldAdvanceLastProcessed15mBar(m15ForSignal, signalLastProcessed)) {
                                saveLastProcessed15mBarTimestamp(context, m15ForSignal.last().timestampMillis)
                            }
                            if (replayPosition != zStrategyPosition) {
                                zStrategyPosition = replayPosition
                                saveStrategyPosition(context, zStrategyPosition)
                            }
                            previousZScoreForAlert = m15ForSignal.last().zScore
                        }
                    }
                    signalEvents = loadStrategySignalEvents(context)
                    portfolioTabUiBuiltKey = 0L
                    launchScope.launch {
                        refreshPortfolioAfterJournalChange(refreshTailIfStale = true)
                    }
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
                if (!mayRefreshMarkets(refreshPolicy)) return@launch
                isRefreshing = true
                try {
                    runCatching { performRefresh() }
                        .onFailure { t ->
                            MoexDiagnostics.logError(context, "ui", t, "refreshData bg performRefresh")
                        }
                } finally {
                    isRefreshing = false
                }
            }
            return
        }

        if (!blockUi) isRefreshing = true
        try {
            runCatching { performRefresh() }
                .onFailure { t ->
                    MoexDiagnostics.logError(context, "ui", t, "refreshData performRefresh")
                    if (MoexDiagnostics.isTransientNetworkError(t)) {
                        realtimeError = "Сеть недоступна или нестабильна"
                        lastGoodMarkets?.let {
                            marketsStale = true
                            state = it
                        }
                    } else if (lastGoodMarkets != null) {
                        marketsStale = true
                        state = lastGoodMarkets!!
                        realtimeError = t.message?.take(120)
                    }
                }
            runCatching {
                refreshMarketsLiveQuotesBundle(reason = "refreshData", scope = launchScope)
            }.onFailure { t ->
                MoexDiagnostics.logError(context, "quotes", t, "refreshData live_quotes")
            }
        } finally {
            isRefreshing = false
            MoexDiagnostics.log(
                context,
                "ui",
                "refreshData done m15=${marketsM15Source().size} tab=${selectedTab.label} error=${realtimeError?.take(80)}",
            )
            MoexDiagnostics.logMemory(context, "refreshData_done")
        }
    }
