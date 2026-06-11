package com.example.moexmvp

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate

@Stable
internal class MoexScreenState(val context: Context) {
    val refreshMutex = Mutex()

    var pendingAppUpdate by mutableStateOf<AppRemoteUpdate?>(null)
    var selectedTab by mutableStateOf(
        if (SignalForegroundService.isBackgroundMonitorEnabled(context)) {
            MainTab.Journal
        } else {
            MainTab.Markets
        }
    )
    var confirmedPortfolioMetrics by mutableStateOf<PortfolioMetrics?>(null)
    var confirmedPortfolioTableRows by mutableStateOf<List<PortfolioConfirmedTradeTableRow>>(emptyList())
    var strategyTestCompoundReturns by mutableStateOf(false)
    var portfolioLoading by mutableStateOf(false)
    var portfolioError by mutableStateOf<String?>(null)
    var portfolioLookbackDays by mutableStateOf(loadPortfolioLookbackDays(context))
    var portfolioLeverage by mutableStateOf(7.0)
    var portfolioCommissionPercent by mutableStateOf(0.04)
    var realTradeEntryThreshold by mutableStateOf<Double?>(null)
    var realTradeExitThreshold by mutableStateOf<Double?>(null)
    var strategyTestEntryThreshold by mutableStateOf<Double?>(null)
    var strategyTestExitThreshold by mutableStateOf<Double?>(null)
    var selectedPeriod by mutableStateOf(Period.OneDay)
    /** Период 15м Z-графика (портрет/альбом); смена не вызывает refresh MOEX — только фильтр кэша. */
    var marketsZChartPeriod by mutableStateOf(Period.OneDay)
    var realtimeEnabled by mutableStateOf(true)
    /** ON_RESUME / ON_PAUSE — останавливает авто-опрос «Рынок» в фоне. */
    var activityResumed by mutableStateOf(false)
    /** Последний onTrimMemory (ComponentCallbacks2) для адаптивного опроса и сброса кэшей. */
    var memoryPressureLevel by mutableStateOf(0)
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
    var zStrategyPosition by mutableStateOf(ZStrategyPosition.Flat)
    var dailySignalLimit by mutableStateOf(DailySignalLimit(date = LocalDate.now().toString(), sentCount = 0))
    var signalEvents by mutableStateOf(emptyList<StrategySignalEvent>())
    var pushNotificationLog by mutableStateOf(emptyList<PushNotificationLogEntry>())
    var state by mutableStateOf<UiState>(UiState.Loading)
    var lastGoodMarkets by mutableStateOf<UiState.Success?>(null)
    var marketsStale by mutableStateOf(false)
    /** @deprecated Используйте [marketsM15SessionCache] + [storeMarketsM15]; оставлено для миграции читателей. */
    var marketsM15Points by mutableStateOf<List<DataPoint>>(emptyList())
    var marketsM15SessionCache: List<DataPoint> = emptyList()
    var marketsM15DataEpoch by mutableStateOf(0)
    var portfolioM15Points by mutableStateOf<List<DataPoint>>(emptyList())
    /** Полный 15м ряд (~255д) для симуляции — не в Compose state (OOM). */
    var strategyTestM15SessionCache: List<DataPoint> = emptyList()
    /** Хвост ~1M для Z-графика на «Тест страт.» */
    var strategyTestM15ChartTail by mutableStateOf<List<DataPoint>>(emptyList())
    var portfolioPresets by mutableStateOf(loadPortfolioPresets(context))
    var robustCandidate by mutableStateOf<DynamicThresholds?>(null)
    var walkForwardBusy by mutableStateOf(false)
    var pendingVirtualTrade by mutableStateOf<PendingVirtualTradeProposal?>(null)
    var sandboxExecState by mutableStateOf(SandboxExecUiState.Off)
    var sandboxTokenInput by mutableStateOf("")
    var sandboxAccountInput by mutableStateOf("")
    var sandboxSpreadExecReload by mutableStateOf(0)
    var sandboxSpreadExecutions by mutableStateOf<List<SandboxSpreadExecUi>>(emptyList())
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
    var strategyTestM15Loading by mutableStateOf(false)
    var strategyTestError by mutableStateOf<String?>(null)
    var strategyTestChartMarkers by mutableStateOf<List<ChartPointMarker>>(emptyList())
    var strategyTestChartTradeSegments by mutableStateOf<List<TradingViewTradeSegment>>(emptyList())
    var strategyTestTradeRiskAssessments by mutableStateOf<List<StrategyTestTradeRiskAssessment>>(emptyList())
    var strategyTestDurationSummary by mutableStateOf<StrategyTestDurationSummary?>(null)
    var strategyTestSpreadHourlyVolatility by mutableStateOf<SpreadHourlyVolatilityReport?>(null)
    var strategyTestLastSimKey: Long = 0L
    var strategyTestVisibleSessionCache: StrategyTestVisibleSnapshot? = null
    /** Отмена устаревших загрузок/симуляций при смене вкладки или новом запросе. */
    var strategyTestWorkGeneration = 0
    var dailyReconciliation by mutableStateOf<DailyPortfolioReconciliation?>(null)
    var marketsZStrategyTapMetrics by mutableStateOf<PortfolioMetrics?>(null)
    var initialMarketsRefreshDone by mutableStateOf(false)
    /** Прогресс загрузки 15м (кэш / MOEX) и дневного ряда — для прогресс-бара на UI. */
    var dataLoadProgress by mutableStateOf<DataLoadProgress?>(null)
    /** Счётчик активных загрузок; пока > 0, прогресс-бар не скрывается. */
    var dataLoadSessions by mutableStateOf(0)
    /** UI портфеля собран для [portfolioTabUiSessionKey]; 0 = нужен rebuild. */
    var portfolioTabUiBuiltKey: Long = 0L
    /** Период 15м «Рынок», для которого уже есть in-memory ряд. */
    var marketsM15LoadedPeriod: Period? = null
}
