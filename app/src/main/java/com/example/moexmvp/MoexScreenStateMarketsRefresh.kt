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
