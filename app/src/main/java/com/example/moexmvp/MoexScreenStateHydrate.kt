package com.example.moexmvp

import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

    /** Быстрый старт: снимок «Рынка» + 15м из SQLite до сети MOEX (без блокирующего overlay на «Журнал»). */
internal suspend fun MoexScreenState.hydrateMarketsFromLocalCache(preferredPeriod: Period = Period.OneDay) {
        MoexDiagnostics.log(context, "ui", "hydrate_markets start period=$preferredPeriod")
        withContext(Dispatchers.IO) {
            val snapshot = readMarketsSnapshotForDisplay(context, preferredPeriod)
                ?: Period.entries.firstNotNullOfOrNull { readMarketsSnapshotForDisplay(context, it) }
            snapshot?.let {
                lastGoodMarkets = it
                state = it
                val ageMs = marketsSnapshotAgeMillis(context, preferredPeriod)
                marketsStale = ageMs != null && ageMs > MARKETS_SNAPSHOT_TTL_MS
            }
            if (marketsM15Source().isEmpty()) {
                runCatching {
                    loadPortfolio15mPointsForSignalMonitor(
                        context,
                        PortfolioM15LoadMode.CACHE_ONLY,
                        onProgress = null,
                        lookbackDays = marketsM15LookbackDays(Period.OneDay),
                    )
                }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { pts ->
                    storeMarketsM15(pts)
                    if (portfolioM15Points.isEmpty()) portfolioM15Points = pts
                }
            }
            refreshMarketsM15SqliteChartCache(reason = "hydrate")
        }
        MarketsM15ChartDiagnostics.logStage(
            context,
            "hydrate",
            "period=$preferredPeriod ${snapshotM15Series(marketsM15Source()).toLogFields()}",
        )
        MoexDiagnostics.log(
            context,
            "ui",
            "hydrate_markets done m15=${marketsM15Source().size} state=${state::class.simpleName}",
        )
    }

    /** Подгрузка prefs/журнала после первого кадра (не блокирует ctor). */
internal suspend fun MoexScreenState.hydrateDeferredUiState() {
        withContext(Dispatchers.IO) {
            zStrategyPosition = loadSavedStrategyPosition(context)
            dailySignalLimit = loadDailySignalLimit(context, LocalDate.now())
            signalEvents = loadStrategySignalEvents(context)
            pushNotificationLog = loadPushNotificationLog(context)
            val mode = TinkoffSandboxStorage.getExecutionMode(context)
            sandboxExecState = TinkoffSandboxStorage.resolveExecUiState(context, mode)
            portfolioLedgerIncludeAuto = TinkoffSandboxStorage.isPortfolioLedgerIncludeAuto(context)
            executeSignalsOnSandbox = TinkoffSandboxStorage.isExecuteSignalsOnSandbox(context)
            sandboxSpreadAutoExecute = TinkoffSandboxStorage.isSandboxSpreadAutoExecute(context)
            strategyTestAccountSizeRub = loadStrategyTestAccountSizeRub(context)
            strategyTestCapitalUsagePercent = loadStrategyTestCapitalUsagePercent(context)
            strategyTestMaxLossDdPercent = loadStrategyTestMaxLossDdPercent(context)
        }
    }
