package com.example.moexmvp

import android.content.Context
import java.time.LocalDate

/**
 * Единый 15м+Z ряд для live-сигналов (монитор, UI «Портфель», снимок Z).
 * Тот же календарный охват [PORTFOLIO_M15_LOOKBACK_DAYS], что симуляция «Тест страт.» —
 * иначе rolling Z на одном баре может отличаться от sim и journal parity ломается.
 */
internal suspend fun loadZStrategySignalSeries(
    context: Context,
    mode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
    onProgress: DataLoadProgressCallback = null,
): List<DataPoint> {
    val app = context.applicationContext
    val from = LocalDate.now(moexZoneId).minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
    return loadPortfolio15mSeriesEnsuringRecentTail(
        context = app,
        from = from,
        preferredMode = mode,
        onProgress = onProgress,
        wipeAllOnFullRefresh = false,
        retentionDays = PORTFOLIO_M15_CACHE_RETENTION_DAYS,
    )
}
