package com.example.moexmvp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Отложенная догрузка MOEX 15м (тяжёлая): не в hot-path 1м опроса и не блокирует монитор сделок.
 */
internal fun MoexScreenState.scheduleMarketsM15MoexCatchup(
    scope: CoroutineScope,
    reason: String,
    debounceMs: Long = M15_MOEX_UI_CATCHUP_DEBOUNCE_MS,
) {
    if (!activityResumed || selectedTab != MainTab.Markets) return
    if (MoexMemoryPressure.shouldPauseMarkets1mQuotesRefresh(memoryPressureLevel)) return
    val m15Last = marketsM15Source().lastOrNull() ?: return
    val bucketMs = currentM15BucketStartMillis()
    val sealedLagMs = bucketMs - m15Last.timestampMillis
    val needsCatchup = sealedLagMs >= 15 * 60_000L ||
        (marketsIntraday1mLastBarMillis > 0L &&
            marketsIntraday1mLastBarMillis > m15Last.timestampMillis + 20 * 60_000L)
    if (!needsCatchup) return
    if (marketsM15CatchupJob?.isActive == true) return
    marketsM15CatchupJob = scope.launch(Dispatchers.IO) {
        delay(debounceMs)
        if (!activityResumed || selectedTab != MainTab.Markets) return@launch
        val lookback = marketsM15LookbackDays(selectedPeriod.coerceToMarketsUiPeriod())
            .coerceAtLeast(portfolioLookbackDays)
        val loaded = tryRefreshPortfolio15mLiveFormingTailFromMoex(context, lookback)
        if (loaded == null) {
            MoexDiagnostics.log(context, "m15_z", "catchup_deferred lock_busy reason=$reason")
            return@launch
        }
        if (loaded.size < 2) return@launch
        commitMarketsM15ToUi(loaded, reason = "catchup_$reason")
        MoexDiagnostics.log(context, "m15_z", "catchup_ok reason=$reason bar=${loaded.last().tradeDate}")
    }
}
