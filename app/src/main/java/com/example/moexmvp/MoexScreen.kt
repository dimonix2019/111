package com.example.moexmvp

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

@Composable
internal fun MoexScreen() {
    val context = LocalContext.current
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
    val refreshMutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    suspend fun refreshData(showLoading: Boolean) {
        refreshMutex.withLock {
            if (showLoading) {
                state = UiState.Loading
            } else {
                isRefreshing = true
            }

            when (val next = loadState(selectedPeriod)) {
                is UiState.Success -> {
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
                    // Do not wipe an already visible chart on background realtime refresh.
                    if (showLoading || state !is UiState.Success) {
                        state = next
                    } else {
                        realtimeError = next.message
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(12.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            refreshData(showLoading = state !is UiState.Success)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh")
                }
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
                    Text("Test")
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
                    Text(if (monitorEnabled) "BG ON" else "BG OFF")
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
        if (realtimeError != null && state is UiState.Success) {
            item {
                Text(
                    text = "Realtime warning: $realtimeError",
                    color = Color(0xFFEF9A9A),
                    fontSize = 12.sp
                )
            }
        }
        when (val current = state) {
            is UiState.Loading -> item {
                LoadingState()
            }

            is UiState.Error -> item {
                ErrorState(current.message) {
                    scope.launch { refreshData(showLoading = true) }
                }
            }

            is UiState.Empty -> item {
                EmptyState()
            }

            is UiState.Success -> {
                item {
                    SummaryBlock(
                        points = current.points,
                        loadedAt = current.loadedAt
                    )
                }
                item {
                    ChartCard(
                        title = "График 4: Z-score спрэда",
                        series = listOf(
                            ChartSeries(
                                name = "Z-score",
                                color = Color(0xFF80D8FF),
                                values = current.points.map { it.zScore },
                                lineWidth = 2.4f
                            )
                        ),
                        labels = current.points.map { it.tradeDate },
                        chartHeightDp = 130,
                        referenceLines = buildZScoreReferenceLines(dynamicThresholds),
                        pointMarkers = buildZScoreSignalMarkers(
                            points = current.points,
                            events = signalEvents,
                            thresholds = dynamicThresholds
                        )
                    )
                }
                item {
                    ChartCard(
                        title = "График 2: spread = (TATN / TATNP - 1) * 100",
                        series = listOf(
                            ChartSeries("Spread %", Color(0xFF69F0AE), current.points.map { it.spreadPercent })
                        ),
                        labels = current.points.map { it.tradeDate },
                        chartHeightDp = 130,
                        rightAxisPercentBase = current.points.minOfOrNull { it.spreadPercent },
                        yScale = YAxisScale.Auto
                    )
                }
                item {
                    ChartCard(
                        title = "График 3: diff = TATN - TATNP",
                        series = listOf(
                            ChartSeries("Diff", Color(0xFFE1BEE7), current.points.map { it.diff })
                        ),
                        labels = current.points.map { it.tradeDate },
                        chartHeightDp = 130
                    )
                }
                item {
                    CandlestickChartCard(
                        title = "График 1A: свечи TATN",
                        candles = current.tatnCandles
                    )
                }
                item {
                    CandlestickChartCard(
                        title = "График 1B: свечи TATNP",
                        candles = current.tatnpCandles
                    )
                }
            }
        }
    }
}
