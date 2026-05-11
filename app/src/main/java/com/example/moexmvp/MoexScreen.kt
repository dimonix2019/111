package com.example.moexmvp

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun MoexScreen() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(MainTab.Markets) }
    /** Закрытые сделки с подтверждённым входом и выходом в журнале. */
    var confirmedPortfolioMetrics by remember { mutableStateOf<PortfolioMetrics?>(null) }
    /** Симуляция по порогам |Z| на 15м ряду. */
    var strategyTestPortfolioMetrics by remember { mutableStateOf<PortfolioMetrics?>(null) }
    var portfolioLoading by remember { mutableStateOf(false) }
    var portfolioError by remember { mutableStateOf<String?>(null) }
    var strategyViewMode by remember { mutableStateOf(StrategyViewMode.Executed) }
    var portfolioLeverage by remember { mutableStateOf(7.0) }
    var portfolioCommissionPercent by remember { mutableStateOf(0.04) }
    var portfolioEntryThreshold by remember { mutableStateOf<Double?>(null) }
    var portfolioExitThreshold by remember { mutableStateOf<Double?>(null) }
    var selectedPeriod by remember { mutableStateOf(Period.OneDay) }
    var realtimeEnabled by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var realtimeError by remember { mutableStateOf<String?>(null) }
    var previousZScoreForAlert by remember { mutableStateOf<Double?>(null) }
    var dynamicThresholds by remember(context) {
        mutableStateOf(
            loadSavedDynamicThresholds(context)
                ?: DynamicThresholds(
                    entry = DEFAULT_DYNAMIC_Z_ENTRY,
                    exit = DEFAULT_DYNAMIC_Z_EXIT,
                    calculatedDate = null
                )
        )
    }
    var zStrategyPosition by remember(context) { mutableStateOf(loadSavedStrategyPosition(context)) }
    var dailySignalLimit by remember(context) {
        mutableStateOf(loadDailySignalLimit(context, LocalDate.now()))
    }
    var signalEvents by remember(context) {
        mutableStateOf(loadStrategySignalEvents(context))
    }
    var state by remember { mutableStateOf<UiState>(UiState.Loading) }
    var lastGoodMarkets by remember { mutableStateOf<UiState.Success?>(null) }
    var marketsStale by remember { mutableStateOf(false) }
    var portfolioPresets by remember { mutableStateOf(loadPortfolioPresets(context)) }
    var robustCandidate by remember { mutableStateOf<DynamicThresholds?>(null) }
    var walkForwardBusy by remember { mutableStateOf(false) }
    var todayPnlHint by remember { mutableStateOf<String?>(null) }
    var pendingVirtualTrade by remember { mutableStateOf<PendingVirtualTradeProposal?>(null) }
    val refreshMutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    val chartSuccess = (state as? UiState.Success) ?: lastGoodMarkets
    val staleMarkets = marketsStale || (realtimeError != null && chartSuccess != null)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    suspend fun refreshPortfolio(m15LoadHint: PortfolioM15LoadMode? = null) {
        portfolioLoading = true
        portfolioError = null
        try {
            val till = LocalDate.now()
            val from = till.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
            val m15Mode = m15LoadHint ?: run {
                val cnt = PortfolioM15Database.get(context).dao().count()
                if (cnt > 0) PortfolioM15LoadMode.CACHE_ONLY else PortfolioM15LoadMode.INCREMENTAL
            }
            val loaded = loadPortfolio15mDataPoints(context, from, till, m15Mode)
            if (loaded.size < 2) {
                confirmedPortfolioMetrics = null
                strategyTestPortfolioMetrics = null
                portfolioError = when (m15Mode) {
                    PortfolioM15LoadMode.CACHE_ONLY ->
                        "Нет 15-мин данных в кэше. Нажмите «Обновить» для загрузки с MOEX."
                    else ->
                        "Нет 15-мин данных (ISS / сеть). Попробуйте «MOEX заново»."
                }
                return@refreshPortfolio
            }
            val points = loaded
            val desc =
                "15 мин (ISS 10m→15m) · $PORTFOLIO_M15_LOOKBACK_DAYS дн. (${points.first().tradeDate}…${points.last().tradeDate})"

            val entryThreshold = (portfolioEntryThreshold ?: dynamicThresholds.entry)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
            val exitThreshold = (portfolioExitThreshold ?: dynamicThresholds.exit)
                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
            if (portfolioEntryThreshold == null) portfolioEntryThreshold = entryThreshold
            if (portfolioExitThreshold == null) portfolioExitThreshold = exitThreshold
            val portfolioThresholds = DynamicThresholds(
                entry = entryThreshold,
                exit = exitThreshold,
                calculatedDate = dynamicThresholds.calculatedDate
            )
            val events = loadStrategySignalEvents(
                context = context,
                fromTimestampMillis = points.firstOrNull()?.timestampMillis
            )
            confirmedPortfolioMetrics = buildExecutedPortfolioMetrics(
                points = points,
                events = events,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = portfolioLeverage,
                commissionPercentPerSide = portfolioCommissionPercent,
                periodDescription = desc
            )
            strategyTestPortfolioMetrics = buildZStrategyPortfolioMetrics(
                points = points,
                thresholds = portfolioThresholds,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = portfolioLeverage,
                commissionPercentPerSide = portfolioCommissionPercent,
                periodDescription = desc
            )
            portfolioError = null
        } finally {
            portfolioLoading = false
        }
    }

    LaunchedEffect(
        selectedTab,
        dynamicThresholds.entry,
        dynamicThresholds.exit,
        portfolioLeverage,
        portfolioCommissionPercent,
        portfolioEntryThreshold,
        portfolioExitThreshold
    ) {
        if (selectedTab == MainTab.Portfolio || selectedTab == MainTab.StrategyTest) {
            refreshPortfolio(null)
        }
    }

    suspend fun refreshData(showLoading: Boolean) {
        refreshMutex.withLock {
            if (showLoading) {
                state = UiState.Loading
            } else {
                isRefreshing = true
            }

            when (val next = loadState(selectedPeriod)) {
                is UiState.Success -> {
                    lastGoodMarkets = next
                    marketsStale = false
                    state = next
                    realtimeError = null
                    val thresholdUpdate = ensureDynamicThresholds(context)
                    dynamicThresholds = thresholdUpdate.thresholds
                    val backgroundMonitorEnabled = SignalForegroundService.isBackgroundMonitorEnabled(context)
                    if (thresholdUpdate.recalculated && !backgroundMonitorEnabled) {
                        showDynamicZThresholdsPushNotification(
                            context = context,
                            entry = dynamicThresholds.entry,
                            exit = dynamicThresholds.exit,
                            dateText = dynamicThresholds.calculatedDate ?: LocalDate.now().toString()
                        )
                    }
                    dailySignalLimit = loadDailySignalLimit(context, LocalDate.now())
                    val latestPoint = next.points.lastOrNull()
                    val latestZScore = latestPoint?.zScore
                    if (latestZScore != null) {
                        val latestTimestampMillis = latestPoint.timestampMillis
                        val prevZ = previousZScoreForAlert
                        when (
                            determineZStrategySignal(
                                previousZ = prevZ,
                                currentZ = latestZScore,
                                position = zStrategyPosition,
                                thresholds = dynamicThresholds
                            )
                        ) {
                            ZStrategySignal.EnterLong -> {
                                zStrategyPosition = ZStrategyPosition.Long
                                saveStrategyPosition(context, zStrategyPosition)
                                if (!backgroundMonitorEnabled && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                                    val sent = showZStrategySignalPushNotification(
                                        context = context,
                                        title = "Вход: LONG TATN / SHORT TATNP",
                                        body = String.format(
                                            Locale.US,
                                            "Z <= -%.1f (текущий Z=%.2f)",
                                            dynamicThresholds.entry,
                                            latestZScore
                                        )
                                    )
                                    if (sent) {
                                        recordStrategySignalEvent(
                                            context = context,
                                            signalType = StrategySignalType.EnterLong,
                                            zScore = latestZScore,
                                            timestampMillis = latestTimestampMillis
                                        )
                                        dailySignalLimit = dailySignalLimit.copy(sentCount = dailySignalLimit.sentCount + 1)
                                        saveDailySignalLimit(context, dailySignalLimit)
                                    }
                                }
                            }

                            ZStrategySignal.EnterShort -> {
                                zStrategyPosition = ZStrategyPosition.Short
                                saveStrategyPosition(context, zStrategyPosition)
                                if (!backgroundMonitorEnabled && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                                    val sent = showZStrategySignalPushNotification(
                                        context = context,
                                        title = "Вход: LONG TATNP / SHORT TATN",
                                        body = String.format(
                                            Locale.US,
                                            "Z >= +%.1f (текущий Z=%.2f)",
                                            dynamicThresholds.entry,
                                            latestZScore
                                        )
                                    )
                                    if (sent) {
                                        recordStrategySignalEvent(
                                            context = context,
                                            signalType = StrategySignalType.EnterShort,
                                            zScore = latestZScore,
                                            timestampMillis = latestTimestampMillis
                                        )
                                        dailySignalLimit = dailySignalLimit.copy(sentCount = dailySignalLimit.sentCount + 1)
                                        saveDailySignalLimit(context, dailySignalLimit)
                                    }
                                }
                            }

                            ZStrategySignal.ExitLong -> {
                                zStrategyPosition = ZStrategyPosition.Flat
                                saveStrategyPosition(context, zStrategyPosition)
                                if (!backgroundMonitorEnabled && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                                    val sent = showZStrategySignalPushNotification(
                                        context = context,
                                        title = "Выход: закрыть LONG TATN / SHORT TATNP",
                                        body = String.format(
                                            Locale.US,
                                            "Z >= -%.1f (текущий Z=%.2f)",
                                            dynamicThresholds.exit,
                                            latestZScore
                                        )
                                    )
                                    if (sent) {
                                        recordStrategySignalEvent(
                                            context = context,
                                            signalType = StrategySignalType.ExitLong,
                                            zScore = latestZScore,
                                            timestampMillis = latestTimestampMillis
                                        )
                                        dailySignalLimit = dailySignalLimit.copy(sentCount = dailySignalLimit.sentCount + 1)
                                        saveDailySignalLimit(context, dailySignalLimit)
                                    }
                                }
                            }

                            ZStrategySignal.ExitShort -> {
                                zStrategyPosition = ZStrategyPosition.Flat
                                saveStrategyPosition(context, zStrategyPosition)
                                if (!backgroundMonitorEnabled && dailySignalLimit.sentCount < DAILY_SIGNAL_MAX_PER_DAY) {
                                    val sent = showZStrategySignalPushNotification(
                                        context = context,
                                        title = "Выход: закрыть LONG TATNP / SHORT TATN",
                                        body = String.format(
                                            Locale.US,
                                            "Z <= +%.1f (текущий Z=%.2f)",
                                            dynamicThresholds.exit,
                                            latestZScore
                                        )
                                    )
                                    if (sent) {
                                        recordStrategySignalEvent(
                                            context = context,
                                            signalType = StrategySignalType.ExitShort,
                                            zScore = latestZScore,
                                            timestampMillis = latestTimestampMillis
                                        )
                                        dailySignalLimit = dailySignalLimit.copy(sentCount = dailySignalLimit.sentCount + 1)
                                        saveDailySignalLimit(context, dailySignalLimit)
                                    }
                                }
                            }

                            ZStrategySignal.None -> Unit
                        }
                        previousZScoreForAlert = latestZScore
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
        }
    }

    LaunchedEffect(selectedPeriod) {
        refreshData(showLoading = true)
    }

    LaunchedEffect(realtimeEnabled, selectedPeriod) {
        if (!realtimeEnabled) return@LaunchedEffect
        while (true) {
            delay(FIXED_REALTIME_INTERVAL_MS)
            refreshData(showLoading = false)
        }
    }

    val dataSourceLabel = if (staleMarkets) {
        MarketsDataSource.OfflineStale
    } else {
        MarketsDataSource.Network
    }

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
                        saveDynamicThresholds(context, cand)
                        dynamicThresholds = cand
                        robustCandidate = null
                        Toast.makeText(context, "Пороги применены", Toast.LENGTH_SHORT).show()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp)
    ) {
        MainTabSelector(
            selected = selectedTab,
            onSelect = { selectedTab = it }
        )
        pendingVirtualTrade?.let { proposal ->
            PendingVirtualTradeProposalCard(
                proposal = proposal,
                onAccept = {
                    clearPendingVirtualTradeProposal(context)
                    pendingVirtualTrade = null
                    Toast.makeText(
                        context,
                        "Принято. Учёт вручную; подключение Тинькофф Invest API — отдельный шаг.",
                        Toast.LENGTH_LONG
                    ).show()
                },
                onReject = {
                    clearPendingVirtualTradeProposal(context)
                    pendingVirtualTrade = null
                    Toast.makeText(context, "Отклонено.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (selectedTab == MainTab.Markets) {
            StrategyViewModeSelector(
                mode = strategyViewMode,
                onModeChange = { strategyViewMode = it }
            )
        }

        when (selectedTab) {
            MainTab.Journal -> {
                JournalTabContent(events = signalEvents)
            }

            MainTab.About -> {
                AboutTabContent()
            }

            MainTab.Portfolio -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ConfirmedPortfolioTabContent(
                            metrics = confirmedPortfolioMetrics,
                            portfolioLoading = portfolioLoading,
                            portfolioError = portfolioError,
                            onRefresh = { scope.launch { refreshPortfolio(PortfolioM15LoadMode.INCREMENTAL) } },
                            onMoex15mFullReload = { scope.launch { refreshPortfolio(PortfolioM15LoadMode.FULL_REFRESH) } },
                            leverage = portfolioLeverage,
                            commissionPercentPerSide = portfolioCommissionPercent,
                            onLeverageChange = { portfolioLeverage = it },
                            onCommissionChange = { portfolioCommissionPercent = it }
                        )
                    }
                }
            }

            MainTab.StrategyTest -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        StrategyTestTabContent(
                            metrics = strategyTestPortfolioMetrics,
                            portfolioLoading = portfolioLoading,
                            portfolioError = portfolioError,
                            onRefresh = { scope.launch { refreshPortfolio(PortfolioM15LoadMode.INCREMENTAL) } },
                            onMoex15mFullReload = { scope.launch { refreshPortfolio(PortfolioM15LoadMode.FULL_REFRESH) } },
                            leverage = portfolioLeverage,
                            commissionPercentPerSide = portfolioCommissionPercent,
                            entryThreshold = (portfolioEntryThreshold ?: dynamicThresholds.entry)
                                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                            exitThreshold = (portfolioExitThreshold ?: dynamicThresholds.exit)
                                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                            onLeverageChange = { portfolioLeverage = it },
                            onCommissionChange = { portfolioCommissionPercent = it },
                            onEntryThresholdChange = { newEntry ->
                                portfolioEntryThreshold = newEntry.coerceIn(
                                    PORTFOLIO_Z_THRESHOLD_MIN,
                                    PORTFOLIO_Z_THRESHOLD_MAX
                                )
                            },
                            onExitThresholdChange = { newExit ->
                                portfolioExitThreshold = newExit.coerceIn(
                                    PORTFOLIO_Z_THRESHOLD_MIN,
                                    PORTFOLIO_Z_THRESHOLD_MAX
                                )
                            },
                            presets = portfolioPresets,
                            onApplyPreset = { p ->
                                portfolioLeverage = p.leverage
                                portfolioCommissionPercent = p.commissionPercentPerSide
                                portfolioEntryThreshold = p.entryThreshold
                                portfolioExitThreshold = p.exitThreshold
                            },
                            onDeletePreset = { id ->
                                portfolioPresets = deletePortfolioPreset(context, id)
                            },
                            onSavePreset = { name ->
                                portfolioPresets = addPortfolioPreset(
                                    context = context,
                                    name = name,
                                    leverage = portfolioLeverage,
                                    commissionPercentPerSide = portfolioCommissionPercent,
                                    entryThreshold = (portfolioEntryThreshold ?: dynamicThresholds.entry)
                                        .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                                    exitThreshold = (portfolioExitThreshold ?: dynamicThresholds.exit)
                                        .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
                                )
                            },
                            onWalkForward = {
                                scope.launch {
                                    walkForwardBusy = true
                                    try {
                                        val till = LocalDate.now()
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
                            walkForwardBusy = walkForwardBusy
                        )
                    }
                }
            }

            MainTab.Markets -> {
                Column(Modifier.weight(1f)) {
                    val last = chartSuccess?.points?.lastOrNull()
                    MarketsSummaryStrip(
                        z = last?.zScore,
                        spread = last?.spreadPercent,
                        position = zStrategyPosition,
                        signalsToday = dailySignalLimit.sentCount,
                        signalsMax = DAILY_SIGNAL_MAX_PER_DAY,
                        todayPnlSpreadHint = todayPnlHint,
                        lastLoadedAt = chartSuccess?.loadedAt ?: "—",
                        dataSource = dataSourceLabel,
                        stale = staleMarkets,
                        onMoexRefresh = {
                            scope.launch { refreshData(showLoading = state !is UiState.Success) }
                        }
                    )
                    if (realtimeError != null && chartSuccess != null) {
                        Text(
                            text = "Предупреждение: $realtimeError",
                            color = Color(0xFFEF9A9A),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    MarketsPullRefreshBox(
                        refreshing = isRefreshing,
                        onRefresh = { scope.launch { refreshData(showLoading = false) } },
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 8.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                        item {
                            Text(
                                text = "TATN / TATNP (MOEX ISS)",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }
                        item {
                            PeriodSelector(
                                selected = selectedPeriod,
                                onSelect = {
                                    selectedPeriod = it
                                    previousZScoreForAlert = null
                                }
                            )
                        }
                        item {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Z-strategy thresholds: entry +/-%.1f, exit +/-%.1f%s",
                                    dynamicThresholds.entry,
                                    dynamicThresholds.exit,
                                    dynamicThresholds.calculatedDate?.let { " (updated $it)" } ?: ""
                                ),
                                color = Color(0xFFE0E0E0),
                                fontSize = 12.sp
                            )
                        }
                        item {
                            Text(
                                text = if (strategyViewMode == StrategyViewMode.Executed) {
                                    "Маркеры: журнал сигналов · вкладка «Портфель» — подтверждённые сделки"
                                } else {
                                    "Маркеры: пересечение порогов · симуляция на вкладке «Тест страт.»"
                                },
                                fontSize = 11.sp,
                                color = Color(0xFFBDBDBD)
                            )
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val message = String.format(
                                            Locale.US,
                                            "Пороги: вход +/-%.1f, выход +/-%.1f",
                                            dynamicThresholds.entry,
                                            dynamicThresholds.exit
                                        )
                                        showZStrategySignalPushNotification(
                                            context = context,
                                            title = "Тест уведомления",
                                            body = message
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.NotificationsActive,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Test")
                                    }
                                }
                                val monitorEnabled = SignalForegroundService.isBackgroundMonitorEnabled(context)
                                Button(
                                    onClick = {
                                        if (monitorEnabled) {
                                            SignalForegroundService.stop(context)
                                        } else {
                                            SignalForegroundService.start(context)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (monitorEnabled) Color(0xFF2E7D32) else Color(0xFF2196F3),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (monitorEnabled) {
                                                Icons.Filled.PauseCircle
                                            } else {
                                                Icons.Filled.PlayCircle
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (monitorEnabled) "BG ON" else "BG OFF")
                                    }
                                }
                            }
                        }
                        item {
                            RealtimeControls(
                                enabled = realtimeEnabled,
                                isRefreshing = isRefreshing,
                                onToggle = { realtimeEnabled = !realtimeEnabled }
                            )
                        }
                        if (chartSuccess != null) {
                            val c = chartSuccess
                            item {
                                ChartCard(
                                    title = "График 4: Z-score спрэда",
                                    series = listOf(
                                        ChartSeries(
                                            name = "Z-score",
                                            color = Color(0xFF80D8FF),
                                            values = c.points.map { it.zScore },
                                            lineWidth = 2.4f
                                        )
                                    ),
                                    labels = c.points.map { it.tradeDate },
                                    chartHeightDp = 130,
                                    referenceLines = buildZScoreReferenceLines(dynamicThresholds),
                                    pointMarkers = if (strategyViewMode == StrategyViewMode.Executed) {
                                        buildZScoreSignalMarkersFromEvents(
                                            points = c.points,
                                            events = signalEvents
                                        )
                                    } else {
                                        buildZScoreSignalMarkersFromCrossings(
                                            points = c.points,
                                            thresholds = dynamicThresholds
                                        )
                                    }
                                )
                            }
                            item {
                                ChartCard(
                                    title = "График 2: spread = (TATN / TATNP - 1) * 100",
                                    series = listOf(
                                        ChartSeries(
                                            "Spread %",
                                            Color(0xFF69F0AE),
                                            c.points.map { it.spreadPercent }
                                        )
                                    ),
                                    labels = c.points.map { it.tradeDate },
                                    chartHeightDp = 130,
                                    rightAxisPercentBase = c.points.minOfOrNull { it.spreadPercent },
                                    yScale = YAxisScale.Auto
                                )
                            }
                        } else {
                            when (val st = state) {
                                is UiState.Loading -> item { LoadingState() }
                                is UiState.Error -> item {
                                    ErrorState(st.message) {
                                        scope.launch { refreshData(showLoading = true) }
                                    }
                                }
                                is UiState.Empty -> item { EmptyState() }
                                else -> Unit
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
