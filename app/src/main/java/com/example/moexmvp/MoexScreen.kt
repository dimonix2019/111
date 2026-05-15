package com.example.moexmvp

import android.app.Activity
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@Composable
internal fun MoexScreen() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(MainTab.Markets) }
    val configuration = LocalConfiguration.current
    val landscapeMarketsChartsOnly =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            selectedTab == MainTab.Markets
    /** Закрытые сделки по тем же правилам 15м Z, что «Тест страт.» (без капитализации). */
    var confirmedPortfolioMetrics by remember { mutableStateOf<PortfolioMetrics?>(null) }
    /** Симуляция по порогам |Z| на 15м ряду. */
    var strategyTestPortfolioMetrics by remember { mutableStateOf<PortfolioMetrics?>(null) }
    /** Реинвестирование PnL в размер следующей сделки (только симуляция «Тест страт.»). */
    var strategyTestCompoundReturns by remember { mutableStateOf(false) }
    var portfolioLoading by remember { mutableStateOf(false) }
    var portfolioError by remember { mutableStateOf<String?>(null) }
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
    var sandboxExecState by remember {
        mutableStateOf(TinkoffSandboxStorage.resolveExecUiState(context))
    }
    /** Поля песочницы держим здесь: при переключении вкладок `TinkoffSandboxTabContent` пересоздаётся, иначе токен «терялся» из UI до повторной загрузки. */
    var sandboxTokenInput by remember { mutableStateOf("") }
    var sandboxAccountInput by remember { mutableStateOf("") }
    /** Сдвигается после успешного «Принять» на песочнице — портфель перечитывает блок «2 ноги». */
    var sandboxSpreadExecReload by remember { mutableStateOf(0) }
    var bgMonitorToggleEpoch by remember { mutableStateOf(0) }
    val refreshMutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    val chartSuccess = (state as? UiState.Success) ?: lastGoodMarkets
    val staleMarkets = marketsStale || (realtimeError != null && chartSuccess != null)

    val marketsZStrategyTapMetrics = remember(
        chartSuccess?.points,
        dynamicThresholds.entry,
        dynamicThresholds.exit,
        dynamicThresholds.calculatedDate,
        portfolioLeverage,
        portfolioCommissionPercent,
        selectedPeriod
    ) {
        val pts = chartSuccess?.points
        if (pts == null || pts.size < 2) return@remember null
        buildZStrategyPortfolioMetrics(
            points = pts,
            thresholds = dynamicThresholds,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = portfolioLeverage,
            commissionPercentPerSide = portfolioCommissionPercent,
            periodDescription = "${selectedPeriod.label} · тап Z",
            compoundReturns = false
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        fun hydrateVirtualTradeAndSandboxUi() {
            val act = context as? Activity
            applyVirtualTradeTapIntent(context, act?.intent)
            restorePendingVirtualTradeFromJournalIfNeeded(context)
            pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
            sandboxExecState = TinkoffSandboxStorage.resolveExecUiState(context)
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

    LaunchedEffect(dynamicThresholds.entry, dynamicThresholds.exit) {
        if (portfolioEntryThreshold == null || portfolioExitThreshold == null) {
            val saved = loadPortfolioZThresholds(context, dynamicThresholds)
            if (portfolioEntryThreshold == null) portfolioEntryThreshold = saved.entry
            if (portfolioExitThreshold == null) portfolioExitThreshold = saved.exit
        }
    }

    LaunchedEffect(portfolioEntryThreshold, portfolioExitThreshold) {
        val entry = portfolioEntryThreshold ?: return@LaunchedEffect
        val exit = portfolioExitThreshold ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            savePortfolioZThresholds(context, entry, exit)
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
            confirmedPortfolioMetrics = buildZStrategyPortfolioMetrics(
                points = points,
                thresholds = portfolioThresholds,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = portfolioLeverage,
                commissionPercentPerSide = portfolioCommissionPercent,
                periodDescription = "$desc · 15м Z (как «Тест страт.»)",
                compoundReturns = false
            )
            strategyTestPortfolioMetrics = buildZStrategyPortfolioMetrics(
                points = points,
                thresholds = portfolioThresholds,
                notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                leverage = portfolioLeverage,
                commissionPercentPerSide = portfolioCommissionPercent,
                periodDescription = desc,
                compoundReturns = strategyTestCompoundReturns
            )
            val simPosition = strategyTestPortfolioMetrics?.openPosition?.direction
            zStrategyPosition = simPosition ?: ZStrategyPosition.Flat
            saveStrategyPosition(context, zStrategyPosition)
            portfolioError = null
        } finally {
            portfolioLoading = false
        }
    }

    val signalJournalFingerprint = signalEvents.size to signalEvents.sumOf { it.timestampMillis + it.signalType.ordinal * 31L }

    val dailyReconciliation = remember(
        signalJournalFingerprint,
        confirmedPortfolioMetrics,
        strategyTestPortfolioMetrics,
        portfolioEntryThreshold,
        portfolioExitThreshold,
        dynamicThresholds.entry,
        dynamicThresholds.exit
    ) {
        buildDailyPortfolioReconciliation(
            day = LocalDate.now(ZoneId.of("Europe/Moscow")),
            journalEvents = signalEvents,
            confirmed = confirmedPortfolioMetrics,
            simulation = strategyTestPortfolioMetrics,
            simEntryThreshold = portfolioEntryThreshold ?: dynamicThresholds.entry,
            simExitThreshold = portfolioExitThreshold ?: dynamicThresholds.exit
        )
    }

    LaunchedEffect(
        selectedTab,
        dynamicThresholds.entry,
        dynamicThresholds.exit,
        portfolioLeverage,
        portfolioCommissionPercent,
        portfolioEntryThreshold,
        portfolioExitThreshold,
        signalJournalFingerprint,
        strategyTestCompoundReturns
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
                    if (thresholdUpdate.recalculated && !backgroundMonitorEnabled && !fromDiskCache) {
                        showDynamicZThresholdsPushNotification(
                            context = context,
                            entry = dynamicThresholds.entry,
                            exit = dynamicThresholds.exit,
                            dateText = dynamicThresholds.calculatedDate ?: LocalDate.now().toString()
                        )
                    }
                    dailySignalLimit = loadDailySignalLimit(context, LocalDate.now())
                    if (!fromDiskCache) {
                        val signalThresholds = DynamicThresholds(
                            entry = (portfolioEntryThreshold ?: dynamicThresholds.entry)
                                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                            exit = (portfolioExitThreshold ?: dynamicThresholds.exit)
                                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX),
                            calculatedDate = dynamicThresholds.calculatedDate
                        )
                        val m15ForSignal = withContext(Dispatchers.IO) {
                            val till = LocalDate.now()
                            loadPortfolio15mDataPoints(
                                context,
                                till.minusDays(10),
                                till,
                                PortfolioM15LoadMode.INCREMENTAL
                            )
                        }
                        if (m15ForSignal.size >= 2) {
                            val lastPt = m15ForSignal.last()
                            val latestZScore = lastPt.zScore
                            val latestTimestampMillis = lastPt.timestampMillis
                            when (
                                zStrategySignalOnLast15mBar(
                                    points = m15ForSignal,
                                    position = zStrategyPosition,
                                    thresholds = signalThresholds
                                )
                            ) {
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
                                        virtualTradeTap = VirtualTradeTapIntent(
                                            signalType = StrategySignalType.EnterLong,
                                            zScore = latestZScore,
                                            timestampMillis = latestTimestampMillis
                                        )
                                    )
                                    if (sent) {
                                        dailySignalLimit = dailySignalLimit.copy(sentCount = dailySignalLimit.sentCount + 1)
                                        saveDailySignalLimit(context, dailySignalLimit)
                                    }
                                }
                                scope.launch(Dispatchers.IO) {
                                    val ran = runSandboxAutoEntryIfNeeded(
                                        context.applicationContext,
                                        StrategySignalType.EnterLong,
                                        latestZScore,
                                        latestTimestampMillis
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
                                        virtualTradeTap = VirtualTradeTapIntent(
                                            signalType = StrategySignalType.EnterShort,
                                            zScore = latestZScore,
                                            timestampMillis = latestTimestampMillis
                                        )
                                    )
                                    if (sent) {
                                        dailySignalLimit = dailySignalLimit.copy(sentCount = dailySignalLimit.sentCount + 1)
                                        saveDailySignalLimit(context, dailySignalLimit)
                                    }
                                }
                                scope.launch(Dispatchers.IO) {
                                    val ran = runSandboxAutoEntryIfNeeded(
                                        context.applicationContext,
                                        StrategySignalType.EnterShort,
                                        latestZScore,
                                        latestTimestampMillis
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

    val dataSourceLabel = when {
        staleMarkets -> MarketsDataSource.OfflineStale
        chartSuccess?.marketsDataSource == MarketsDataSource.FifteenMinuteCache ->
            MarketsDataSource.FifteenMinuteCache
        else -> MarketsDataSource.Network
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
            .padding(if (landscapeMarketsChartsOnly) 4.dp else 12.dp)
    ) {
        if (!landscapeMarketsChartsOnly) {
            MainTabSelector(
                selected = selectedTab,
                onSelect = { selectedTab = it }
            )
            pendingVirtualTrade?.let { proposal ->
            PendingVirtualTradeProposalCard(
                proposal = proposal,
                sandboxState = sandboxExecState,
                onAccept = {
                    scope.launch {
                        val st = TinkoffSandboxStorage.resolveExecUiState(context)
                        sandboxExecState = st
                        when (st) {
                            SandboxExecUiState.Ready -> {
                                val tok = TinkoffSandboxStorage.getToken(context)
                                val acc = TinkoffSandboxStorage.getAccountId(context)
                                if (tok.isNullOrBlank() || acc.isNullOrBlank()) {
                                    Toast.makeText(
                                        context,
                                        "Нет токена или счёта в «Песочница».",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@launch
                                }
                                try {
                                    val nowMs = System.currentTimeMillis()
                                    val (skipDupJournal, legs) = withContext(Dispatchers.IO) {
                                        val legsInner = tinkoffSandboxExecuteSpreadEntryDetailed(
                                            tok,
                                            acc,
                                            proposal.signalType
                                        )
                                        clearPendingVirtualTradeProposal(context, proposal)
                                        TinkoffSandboxSpreadExecLog.record(
                                            context,
                                            proposal.signalType,
                                            proposal.zScore,
                                            nowMs
                                        )
                                        val recent = loadStrategySignalEvents(context)
                                        val last = recent.lastOrNull()
                                        val ageMs = last?.let { nowMs - it.timestampMillis }
                                        val skipDup = last != null &&
                                            ageMs != null &&
                                            ageMs >= 0L &&
                                            ageMs < 600_000L &&
                                            last.signalType == proposal.signalType &&
                                            kotlin.math.abs(last.zScore - proposal.zScore) < 0.08
                                        if (!skipDup) {
                                            recordStrategySignalEvent(
                                                context = context,
                                                signalType = proposal.signalType,
                                                zScore = proposal.zScore,
                                                timestampMillis = nowMs,
                                                skipJournalWallDedup = true,
                                                savePendingVirtualTradeIfEntry = false
                                            )
                                        }
                                        Pair(skipDup, legsInner)
                                    }
                                    notifySandboxSpreadLegExecutionResults(
                                        context,
                                        legs,
                                        DEFAULT_PORTFOLIO_NOTIONAL_RUB,
                                        TinkoffSandboxStorage.getSandboxNotifyLeverage(context)
                                    )
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
                                        "В песочнице отправлены 2 заявки (две ноги спрэда). $tail",
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
                            SandboxExecUiState.Off, SandboxExecUiState.MissingCredentials -> {
                                clearPendingVirtualTradeProposal(context, proposal)
                                pendingVirtualTrade = null
                                val msg = when (st) {
                                    SandboxExecUiState.Off ->
                                        "Принято без заявок. Включите «Исполнять на демо-счёте» во вкладке «Песочница»."
                                    SandboxExecUiState.MissingCredentials ->
                                        "Принято без заявок: сохраните sandbox-токен и счёт во вкладке «Песочница»."
                                    else -> ""
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onReject = {
                    clearPendingVirtualTradeProposal(context, proposal)
                    pendingVirtualTrade = null
                    Toast.makeText(context, "Отклонено.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        }
        when (selectedTab) {
            MainTab.Journal -> {
                JournalTabContent(
                    events = signalEvents,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onClearHistoryRequest = {
                        clearStrategySignalJournalAndLocalStrategyState(context)
                        signalEvents = loadStrategySignalEvents(context)
                        pendingVirtualTrade = loadPendingVirtualTradeProposal(context)
                        zStrategyPosition = ZStrategyPosition.Flat
                        sandboxSpreadExecReload++
                        Toast.makeText(
                            context,
                            "Журнал очищен; позиция Z — FLAT; карточка «Принять» и блок песочницы в портфеле сброшены.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }

            MainTab.About -> {
                AboutTabContent(modifier = Modifier.weight(1f).fillMaxWidth())
            }

            MainTab.Sandbox -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    TinkoffSandboxTabContent(
                        tokenInput = sandboxTokenInput,
                        onTokenInputChange = { sandboxTokenInput = it },
                        accountInput = sandboxAccountInput,
                        onAccountInputChange = { sandboxAccountInput = it },
                        onSandboxPrefsChanged = {
                            sandboxExecState = TinkoffSandboxStorage.resolveExecUiState(context)
                        },
                        onSandboxAccountRecreated = { sandboxSpreadExecReload++ }
                    )
                }
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
                            leverage = portfolioLeverage,
                            commissionPercentPerSide = portfolioCommissionPercent,
                            onLeverageChange = { portfolioLeverage = it },
                            onCommissionChange = { portfolioCommissionPercent = it },
                            sandboxSpreadExecReload = sandboxSpreadExecReload,
                            dailyReconciliation = dailyReconciliation
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
                            compoundReturns = strategyTestCompoundReturns,
                            onCompoundReturnsChange = { strategyTestCompoundReturns = it },
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
                            walkForwardBusy = walkForwardBusy,
                            dailyReconciliation = dailyReconciliation
                        )
                    }
                }
            }

            MainTab.Markets -> {
                Column(Modifier.weight(1f)) {
                    if (!landscapeMarketsChartsOnly) {
                        val last = chartSuccess?.points?.lastOrNull()
                        val demoTail = TinkoffSandboxStorage.getAccountId(context)?.takeLast(8).orEmpty()
                        val sandboxDemoHint = when (sandboxExecState) {
                            SandboxExecUiState.Off -> null
                            SandboxExecUiState.MissingCredentials ->
                                "Т‑Инвест песочница: сохраните токен и счёт (вкладка «Песочница»), чтобы слать заявки по «Принять»."
                            SandboxExecUiState.Ready ->
                                "Демо-счёт Т‑Инвест · …$demoTail · «Принять» → покупка 1 лота + продажа 1 лота (спрэд TATN/TATNP)."
                        }
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
                            sandboxDemoHint = sandboxDemoHint,
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
                    }
                    MarketsPullRefreshBox(
                        refreshing = isRefreshing,
                        onRefresh = { scope.launch { refreshData(showLoading = false) } },
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = if (landscapeMarketsChartsOnly) 0.dp else 8.dp)
                    ) {
                        if (landscapeMarketsChartsOnly) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Портрет — вкладки и сводка. Масштаб: два пальца, сдвиг, двойной тап — сброс.",
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 10.sp
                                )
                                PeriodSelector(
                                    selected = selectedPeriod,
                                    onSelect = {
                                        selectedPeriod = it
                                        previousZScoreForAlert = null
                                    }
                                )
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                ) {
                                    val safeMax = if (maxHeight.value.isFinite()) maxHeight else 360.dp
                                    val slotH = ((safeMax - 4.dp) / 2).coerceAtLeast(120.dp)
                                    val chartH = slotH.value.roundToInt().coerceIn(110, 800)
                                    if (chartSuccess != null) {
                                        val c = chartSuccess
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            ChartCard(
                                                title = "Z-score спрэда",
                                                series = listOf(
                                                    ChartSeries(
                                                        name = "Z-score",
                                                        color = Color(0xFF80D8FF),
                                                        values = c.points.map { it.zScore },
                                                        lineWidth = 2.6f
                                                    )
                                                ),
                                                labels = c.points.map { it.tradeDate },
                                                chartHeightDp = chartH,
                                                referenceLines = buildZScoreReferenceLines(dynamicThresholds),
                                                pointMarkers = buildZScoreSignalMarkersFromEvents(
                                                    points = c.points,
                                                    events = signalEvents
                                                ),
                                                showLegend = false,
                                                enableZoomPan = true,
                                                markerScale = 1.5f,
                                                showZoomHint = true,
                                                tradeTapHintFormatter = { idx ->
                                                    formatZStrategyTradeTapHint(idx, c.points, marketsZStrategyTapMetrics)
                                                }
                                            )
                                            ChartCard(
                                                title = "Spread %",
                                                series = listOf(
                                                    ChartSeries(
                                                        "Spread %",
                                                        Color(0xFF69F0AE),
                                                        c.points.map { it.spreadPercent }
                                                    )
                                                ),
                                                labels = c.points.map { it.tradeDate },
                                                chartHeightDp = chartH,
                                                rightAxisPercentBase = c.points.minOfOrNull { it.spreadPercent },
                                                yScale = YAxisScale.Auto,
                                                showLegend = false,
                                                enableZoomPan = true,
                                                markerScale = 1f,
                                                showZoomHint = false
                                            )
                                        }
                                    } else {
                                        when (val st = state) {
                                            is UiState.Loading -> LoadingState()
                                            is UiState.Error -> ErrorState(st.message) {
                                                scope.launch { refreshData(showLoading = true) }
                                            }
                                            is UiState.Empty -> EmptyState()
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                        } else {
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
                            val monitorEnabled = remember(bgMonitorToggleEpoch) {
                                SignalForegroundService.isBackgroundMonitorEnabled(context)
                            }
                            val testEntryZ = (portfolioEntryThreshold ?: dynamicThresholds.entry)
                                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
                            val testExitZ = (portfolioExitThreshold ?: dynamicThresholds.exit)
                                .coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val body = String.format(
                                            Locale.US,
                                            "Тест сигнала MOEX. Текущие пороги Z: вход ±%.2f, выход ±%.2f.",
                                            testEntryZ,
                                            testExitZ
                                        )
                                        showZStrategySignalPushNotification(
                                            context = context,
                                            title = "Тест сигнала",
                                            body = body
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF455A64),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.NotificationsActive,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Тест", fontSize = 12.sp)
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (monitorEnabled) {
                                            SignalForegroundService.stop(context)
                                        } else {
                                            SignalForegroundService.start(context)
                                        }
                                        bgMonitorToggleEpoch++
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
                                        Text(if (monitorEnabled) "BG вкл" else "BG выкл", fontSize = 12.sp)
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
                                            lineWidth = 2.8f
                                        )
                                    ),
                                    labels = c.points.map { it.tradeDate },
                                    chartHeightDp = 228,
                                    referenceLines = buildZScoreReferenceLines(dynamicThresholds),
                                    pointMarkers = buildZScoreSignalMarkersFromEvents(
                                        points = c.points,
                                        events = signalEvents
                                    ),
                                    showLegend = false,
                                    enableZoomPan = true,
                                    markerScale = 1.35f,
                                    showZoomHint = true,
                                    tradeTapHintFormatter = { idx ->
                                        formatZStrategyTradeTapHint(idx, c.points, marketsZStrategyTapMetrics)
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
                                    chartHeightDp = 208,
                                    rightAxisPercentBase = c.points.minOfOrNull { it.spreadPercent },
                                    yScale = YAxisScale.Auto,
                                    showLegend = false,
                                    enableZoomPan = true,
                                    markerScale = 1f,
                                    showZoomHint = false
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
}
