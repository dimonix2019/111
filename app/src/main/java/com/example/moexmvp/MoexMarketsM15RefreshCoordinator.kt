package com.example.moexmvp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** Тип MOEX 15м refresh на «Рынок» (выше priority = важнее). */
internal enum class MarketsM15RefreshKind(val priority: Int) {
    CATCHUP(1),
    TAIL_STALE(2),
    FORCE_INCR(3),
}

internal data class MarketsM15RefreshRequest(
    val kind: MarketsM15RefreshKind,
    val reason: String,
    val debounceMs: Long = 0L,
    val clearLiveTailZ: Boolean = false,
)

private val marketsM15CoordinatorMutex = Mutex()

/**
 * Single-flight: один MOEX 15м refresh за раз; параллельные запросы сливаются по max priority.
 */
internal fun MoexScreenState.requestMarketsM15Refresh(
    scope: CoroutineScope,
    request: MarketsM15RefreshRequest,
) {
    if (!activityResumed || selectedTab != MainTab.Markets) return
    if (MoexMemoryPressure.shouldPauseMarkets1mQuotesRefresh(memoryPressureLevel)) return
    scope.launch(Dispatchers.Main.immediate) {
        marketsM15CoordinatorMutex.withLock {
            marketsM15RefreshPending = mergeMarketsM15RefreshRequest(marketsM15RefreshPending, request)
            if (marketsM15RefreshJob?.isActive == true) return@withLock
            marketsM15RefreshJob = scope.launch(Dispatchers.IO) {
                runMarketsM15RefreshLoop()
            }
        }
    }
}

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
    requestMarketsM15Refresh(
        scope,
        MarketsM15RefreshRequest(
            kind = MarketsM15RefreshKind.CATCHUP,
            reason = "catchup_$reason",
            debounceMs = debounceMs,
        ),
    )
}

internal fun MoexScreenState.requestMarketsM15ForceIncremental(
    scope: CoroutineScope,
    reason: String,
) {
    requestMarketsM15Refresh(
        scope,
        MarketsM15RefreshRequest(
            kind = MarketsM15RefreshKind.FORCE_INCR,
            reason = "force_incr_$reason",
            clearLiveTailZ = true,
        ),
    )
}

internal fun MoexScreenState.requestMarketsM15TailStale(
    scope: CoroutineScope,
    reason: String,
) {
    val src = marketsM15Source().takeIf { it.size >= 2 } ?: portfolioM15Points
    if (src.size < 2 || !portfolio15mSeriesNeedsMoexRefresh(src)) return
    requestMarketsM15Refresh(
        scope,
        MarketsM15RefreshRequest(
            kind = MarketsM15RefreshKind.TAIL_STALE,
            reason = "tail_stale_$reason",
        ),
    )
}

internal fun mergeMarketsM15RefreshRequest(
    current: MarketsM15RefreshRequest?,
    incoming: MarketsM15RefreshRequest,
): MarketsM15RefreshRequest {
    if (current == null) return incoming
    val winner = if (incoming.kind.priority >= current.kind.priority) incoming else current
    val loser = if (winner === incoming) current else incoming
    return winner.copy(
        reason = "${loser.reason}|${winner.reason}",
        debounceMs = maxOf(current.debounceMs, incoming.debounceMs),
        clearLiveTailZ = current.clearLiveTailZ || incoming.clearLiveTailZ,
    )
}

private suspend fun MoexScreenState.runMarketsM15RefreshLoop() {
    while (true) {
        val request = marketsM15CoordinatorMutex.withLock {
            marketsM15RefreshPending.also { marketsM15RefreshPending = null }
        } ?: break
        if (request.debounceMs > 0L) delay(request.debounceMs)
        if (!activityResumed || selectedTab != MainTab.Markets) break
        runCatching {
            executeMarketsM15Refresh(request)
        }.onFailure { t ->
            MoexDiagnostics.logError(context, "m15_refresh", t, "failed ${request.kind} ${request.reason}")
        }
        val hasMore = marketsM15CoordinatorMutex.withLock {
            marketsM15RefreshPending != null
        }
        if (!hasMore) break
    }
}

private suspend fun MoexScreenState.executeMarketsM15Refresh(request: MarketsM15RefreshRequest): Boolean {
    if (request.clearLiveTailZ) {
        withContext(Dispatchers.IO) {
            clearM15LiveTailPersistedZ(PortfolioM15Database.get(context.applicationContext).dao())
        }
    }
    val loaded = when (request.kind) {
        MarketsM15RefreshKind.CATCHUP -> {
            tryRefreshPortfolio15mLiveFormingTailFromMoex(context, PORTFOLIO_M15_LOOKBACK_DAYS)
        }
        MarketsM15RefreshKind.TAIL_STALE,
        MarketsM15RefreshKind.FORCE_INCR,
        -> loadM15ForMarkets(
            mode = PortfolioM15LoadMode.INCREMENTAL,
            wrapInSession = false,
        )
    }
    if (loaded == null || loaded.size < 2) {
        if (request.kind == MarketsM15RefreshKind.CATCHUP) {
            val fallback = loadM15ForMarkets(
                mode = PortfolioM15LoadMode.INCREMENTAL,
                wrapInSession = false,
            )
            if (fallback.size >= 2) {
                commitMarketsM15ToUi(fallback, reason = "${request.reason}_incr_fallback")
                MoexDiagnostics.log(
                    context,
                    "m15_refresh",
                    "ok kind=CATCHUP→INCR bar=${fallback.last().tradeDate} reason=${request.reason}",
                )
                return true
            }
        }
        MoexDiagnostics.log(
            context,
            "m15_refresh",
            "skip kind=${request.kind} empty reason=${request.reason}",
        )
        return false
    }
    commitMarketsM15ToUi(loaded, reason = request.reason)
    MoexDiagnostics.log(
        context,
        "m15_refresh",
        "ok kind=${request.kind} bar=${loaded.last().tradeDate} z=${
            "%.2f".format(Locale.US, loaded.last().zScore)
        } reason=${request.reason}",
    )
    return true
}

/** 255д база для rolling Z (parity с монитором / «Тест страт.»). */
internal suspend fun MoexScreenState.resolveMarketsM15PointsForLiveZ(
    primary: List<DataPoint>,
): List<DataPoint> {
    if (m15PointsCoverPortfolioLookback(primary, PORTFOLIO_M15_LOOKBACK_DAYS)) {
        return primary
    }
    return withContext(Dispatchers.IO) {
        loadZStrategySignalSeries(context, PortfolioM15LoadMode.CACHE_ONLY)
    }.takeIf { it.size >= Z_SCORE_ROLLING_MIN_BARS } ?: primary
}
