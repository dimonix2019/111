package com.example.moexmvp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 15м для «Рынок»: полный lookback вне Compose state (3M ≈ 4k баров — OOM в mutableStateOf). */
internal fun MoexScreenState.marketsM15Source(): List<DataPoint> =
    marketsM15SessionCache.takeIf { it.isNotEmpty() } ?: marketsM15Points

internal suspend fun MoexScreenState.storeMarketsM15(points: List<DataPoint>) {
    if (points.isEmpty()) return
    withContext(Dispatchers.Main.immediate) {
        marketsM15SessionCache = points
        marketsM15DataEpoch++
        bumpMarketsLoadedAtFromM15(points)
    }
}

internal suspend fun MoexScreenState.clearMarketsM15Session() {
    withContext(Dispatchers.Main.immediate) {
        marketsM15SessionCache = emptyList()
        marketsM15DataEpoch++
    }
}
