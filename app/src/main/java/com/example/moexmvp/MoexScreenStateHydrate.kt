package com.example.moexmvp

import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

    /** Быстрый старт: снимок «Рынка» + 15м из SQLite до сети MOEX. */
internal suspend fun MoexScreenState.hydrateMarketsFromLocalCache(preferredPeriod: Period = Period.OneDay) {
        withContext(Dispatchers.IO) {
            val snapshot = readMarketsSnapshotForDisplay(context, preferredPeriod)
                ?: Period.entries.firstNotNullOfOrNull { readMarketsSnapshotForDisplay(context, it) }
            snapshot?.let {
                lastGoodMarkets = it
                state = it
                val ageMs = marketsSnapshotAgeMillis(context, preferredPeriod)
                marketsStale = ageMs != null && ageMs > MARKETS_SNAPSHOT_TTL_MS
            }
            if (marketsM15Points.isEmpty()) {
                runCatching {
                    loadPortfolio15mPointsForSignalMonitor(context, PortfolioM15LoadMode.CACHE_ONLY)
                }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { pts ->
                    marketsM15Points = pts
                    if (portfolioM15Points.isEmpty()) portfolioM15Points = pts
                }
            }
        }
    }

    /** Подгрузка prefs/журнала после первого кадра (не блокирует ctor). */
internal suspend fun MoexScreenState.hydrateDeferredUiState() {
        withContext(Dispatchers.IO) {
            zStrategyPosition = loadSavedStrategyPosition(context)
            dailySignalLimit = loadDailySignalLimit(context, LocalDate.now())
            signalEvents = loadStrategySignalEvents(context)
            pushNotificationLog = loadPushNotificationLog(context)
            sandboxExecState = TinkoffSandboxStorage.resolveExecUiState(context)
            portfolioLedgerIncludeAuto = TinkoffSandboxStorage.isPortfolioLedgerIncludeAuto(context)
            executeSignalsOnSandbox = TinkoffSandboxStorage.isExecuteSignalsOnSandbox(context)
            sandboxSpreadAutoExecute = TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context)
        }
    }
