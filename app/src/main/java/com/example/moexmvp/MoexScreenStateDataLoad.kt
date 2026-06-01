package com.example.moexmvp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

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

internal suspend fun MoexScreenState.loadM15ForMonitor(
    mode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
): List<DataPoint> = withDataLoadSession {
    withContext(Dispatchers.IO) {
        loadPortfolio15mPointsForSignalMonitor(context, mode, dataLoadProgressSink())
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
            retentionDays = PORTFOLIO_M15_LOOKBACK_DAYS,
        )
    }
}
