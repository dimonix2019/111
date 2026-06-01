package com.example.moexmvp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal suspend fun MoexScreenState.reportDataLoadProgress(progress: DataLoadProgress?) {
    withContext(Dispatchers.Main.immediate) {
        when {
            progress?.active == true -> dataLoadProgress = progress
            progress == null && dataLoadSessions == 0 -> dataLoadProgress = null
        }
    }
}

internal fun MoexScreenState.dataLoadProgressSink(): DataLoadProgressCallback = { progress ->
    reportDataLoadProgress(progress)
}

/** Держит прогресс на экране, пока идёт загрузка (в т.ч. фоновая MOEX). */
internal suspend fun <T> MoexScreenState.withDataLoadSession(block: suspend () -> T): T {
    withContext(Dispatchers.Main.immediate) { dataLoadSessions++ }
    try {
        return block()
    } finally {
        withContext(Dispatchers.Main.immediate) {
            dataLoadSessions = (dataLoadSessions - 1).coerceAtLeast(0)
            if (dataLoadSessions == 0) {
                dataLoadProgress = null
            }
        }
    }
}

internal val MoexScreenState.isDataLoadActive: Boolean
    get() = dataLoadSessions > 0 || dataLoadProgress?.active == true

/** 15м для вкладки «Рынок» — только окно выбранного периода (1D…3M), не весь кэш. */
internal suspend fun MoexScreenState.loadM15ForMarkets(
    mode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
    chartPeriod: Period = marketsZChartPeriod.coerceToMarketsUiPeriod(),
): List<DataPoint> = withDataLoadSession {
    val lookback = marketsM15LookbackDays(chartPeriod)
    val till = LocalDate.now(moexZoneId)
    val from = till.minusDays(lookback)
    withContext(Dispatchers.IO) {
        loadPortfolio15mSeriesEnsuringRecentTail(
            context = context,
            from = from,
            preferredMode = mode,
            onProgress = dataLoadProgressSink(),
            wipeAllOnFullRefresh = false,
            retentionDays = PORTFOLIO_M15_CACHE_RETENTION_DAYS,
        )
    }
}

/** 15м для «Тест страт.» — полные 255 дн. симуляции. */
internal suspend fun MoexScreenState.loadM15ForStrategyTest(
    mode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
): List<DataPoint> = withDataLoadSession {
    val till = LocalDate.now(moexZoneId)
    val from = till.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
    withContext(Dispatchers.IO) {
        loadPortfolio15mSeriesEnsuringRecentTail(
            context = context,
            from = from,
            preferredMode = mode,
            onProgress = dataLoadProgressSink(),
            wipeAllOnFullRefresh = false,
            retentionDays = PORTFOLIO_M15_CACHE_RETENTION_DAYS,
        )
    }
}

internal suspend fun MoexScreenState.loadM15SeriesForPortfolio(
    m15Mode: PortfolioM15LoadMode,
): List<DataPoint> = withDataLoadSession {
    val till = LocalDate.now(moexZoneId)
    val from = till.minusDays(PORTFOLIO_TAB_M15_LOOKBACK_DAYS)
    withContext(Dispatchers.IO) {
        loadPortfolio15mSeriesEnsuringRecentTail(
            context = context,
            from = from,
            preferredMode = m15Mode,
            onProgress = dataLoadProgressSink(),
            wipeAllOnFullRefresh = false,
            retentionDays = PORTFOLIO_M15_CACHE_RETENTION_DAYS,
        )
    }
}

internal fun MoexScreenState.marketsM15SpanDays(): Long {
    if (marketsM15Points.size < 2) return 0L
    val zone = moexZoneId
    val first = java.time.Instant.ofEpochMilli(marketsM15Points.first().timestampMillis)
        .atZone(zone).toLocalDate()
    val last = java.time.Instant.ofEpochMilli(marketsM15Points.last().timestampMillis)
        .atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(first, last) + 1
}

/** Догрузить 15м, если в памяти меньше дней, чем нужно для [period]. */
internal suspend fun MoexScreenState.ensureMarketsM15ForPeriod(
    period: Period,
    mode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
) {
    val uiPeriod = period.coerceToMarketsUiPeriod()
    if (uiPeriod != marketsZChartPeriod) marketsZChartPeriod = uiPeriod
    val needed = marketsM15LookbackDays(uiPeriod)
    if (marketsM15SpanDays() + 3 >= needed && marketsM15Points.size >= 2 &&
        !portfolio15mSeriesTailStale(marketsM15Points)
    ) {
        return
    }
    val loaded = loadM15ForMarkets(mode, period)
    if (loaded.size >= 2) {
        marketsM15Points = loaded
        if (portfolioM15Points.isEmpty()) portfolioM15Points = loaded
    }
}
