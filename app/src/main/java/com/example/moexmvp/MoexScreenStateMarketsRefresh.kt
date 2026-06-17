package com.example.moexmvp

import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

    /** Только дневной ряд spread для портретных графиков; Z-график фильтруется локально. */
internal suspend fun MoexScreenState.refreshMarketsDailyOnly(period: Period) {
        refreshMutex.withLock {
            isRefreshing = true
            try {
                withDataLoadSession {
                reportDataLoadProgress(
                    DataLoadProgress(
                        phase = DataLoadPhase.MarketsDaily,
                        marketsPeriodLabel = period.label,
                        detail = "дневной спрэд TATN/TATNP",
                    )
                )
                if (marketsSnapshotFreshEnough(context, period)) {
                    readMarketsSnapshotIfFresh(context, period)?.let { cached ->
                        lastGoodMarkets = cached
                        marketsStale = false
                        state = cached
                        realtimeError = null
                        return@withDataLoadSession
                    }
                }
                readMarketsSnapshotForDisplay(context, period)?.let { cached ->
                    lastGoodMarkets = cached
                    state = cached
                    val age = marketsSnapshotAgeMillis(context, period)
                    marketsStale = age != null && age > MARKETS_SNAPSHOT_TTL_MS
                }
                when (val next = loadState(context, period)) {
                    is UiState.Success -> {
                        lastGoodMarkets = next
                        marketsStale = false
                        state = next
                        realtimeError = null
                    }
                    is UiState.Error -> {
                        realtimeError = next.message
                        if (lastGoodMarkets != null) {
                            marketsStale = true
                            state = lastGoodMarkets!!
                        } else {
                            state = next
                        }
                    }
                    is UiState.Empty -> {
                        if (lastGoodMarkets == null) state = UiState.Empty
                    }
                    UiState.Loading -> Unit
                }
                }
            } finally {
                isRefreshing = false
            }
        }
    }

/** 1м TATN/TATNP за сегодня (МСК) + журнал [quotes] при новых барах. */
internal suspend fun MoexScreenState.refreshMarketsIntraday1mQuotes(reason: String) {
    if (!activityResumed) return
    if (MoexMemoryPressure.shouldPauseMarkets1mQuotesRefresh(memoryPressureLevel)) return
    try {
        val snap = withContext(Dispatchers.IO) { fetchMarketsIntraday1mLive() }
        if (snap.tatn.isEmpty() && snap.tatnp.isEmpty()) {
            MoexDiagnostics.log(context, "quotes", "empty 1m fetch reason=$reason")
            return
        }
        val prevLast = marketsIntraday1mLastBarMillis
        marketsIntraday1mTatn = snap.tatn
        marketsIntraday1mTatnp = snap.tatnp
        marketsIntraday1mLastBarMillis = maxOf(snap.tatnLastBarMillis, snap.tatnpLastBarMillis)
        marketsIntraday1mFetchedAtMillis = snap.fetchedAtMillis
        marketsIntraday1mEpoch++
        val m15Last = marketsM15Source().lastOrNull()
        MarketsQuotesDiagnostics.logQuoteUpdate(context, snap, m15Last, reason)
        if (marketsIntraday1mLastBarMillis > prevLast) {
            refreshM15LiveFormingTail(reason = "1m_new_bar_$reason")
        } else if (m15Last != null &&
            marketsIntraday1mLastBarMillis > m15Last.timestampMillis + 5 * 60_000L
        ) {
            refreshM15LiveFormingTail(reason = "1m_ahead_$reason")
        }
    } catch (t: Throwable) {
        MoexDiagnostics.logError(context, "quotes", t, "fetch failed reason=$reason")
    }
}

/** Принудительный INCREMENTAL 15м + live Z (каждые 5 мин на «Рынок»). */
internal suspend fun MoexScreenState.refreshMarketsM15ZForceIncremental(reason: String) {
    if (!activityResumed || selectedTab != MainTab.Markets) return
    if (MoexMemoryPressure.shouldPauseMarkets1mQuotesRefresh(memoryPressureLevel)) return
    try {
        withContext(Dispatchers.IO) {
            clearM15LiveTailPersistedZ(PortfolioM15Database.get(context.applicationContext).dao())
        }
        val loaded = loadM15ForMarkets(
            mode = PortfolioM15LoadMode.INCREMENTAL,
            wrapInSession = false,
        )
        if (loaded.size < 2) {
            MoexDiagnostics.log(context, "m15_z", "force_incr empty reason=$reason")
            return
        }
        portfolioM15Points = loaded
        publishMarketsLiveZFromPoints(loaded)
        storeMarketsM15(loaded)
        MoexDiagnostics.log(
            context,
            "m15_z",
            "force_incr z=${"%.2f".format(Locale.US, loaded.last().zScore)} " +
                "bar=${loaded.last().tradeDate} reason=$reason",
        )
    } catch (t: Throwable) {
        MoexDiagnostics.logError(context, "m15_z", t, "force_incr failed reason=$reason")
    }
}

/** После refreshData / pull-refresh: 1м + живой хвост 15м Z. */
internal suspend fun MoexScreenState.refreshMarketsLiveQuotesBundle(reason: String) {
    if (selectedTab != MainTab.Markets) return
    refreshMarketsIntraday1mQuotes(reason = reason)
    refreshM15LiveFormingTail(reason = reason)
}
