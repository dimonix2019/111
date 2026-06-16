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
    if (MoexMemoryPressure.shouldPauseAutoRefresh(memoryPressureLevel)) return
    val snap = withContext(Dispatchers.IO) { fetchMarketsIntraday1mDay() }
    if (snap.tatn.isEmpty() && snap.tatnp.isEmpty()) {
        MoexDiagnostics.log(context, "quotes", "empty 1m day fetch reason=$reason")
        return
    }
    marketsIntraday1mTatn = snap.tatn
    marketsIntraday1mTatnp = snap.tatnp
    marketsIntraday1mEpoch++
    val m15Last = marketsM15Source().lastOrNull()
    MarketsQuotesDiagnostics.logQuoteUpdate(context, snap, m15Last, reason)
    val newest1m = maxOf(snap.tatnLastBarMillis, snap.tatnpLastBarMillis)
    if (m15Last != null && newest1m > m15Last.timestampMillis + 5 * 60_000L) {
        refreshM15LiveFormingTail(reason = "1m_ahead_$reason")
    }
}
