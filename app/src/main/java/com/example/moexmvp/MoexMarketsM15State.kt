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
        marketsM15SqliteChartCache = points
        marketsM15DataEpoch++
        marketsM15SqliteChartEpoch++
        bumpMarketsLoadedAtFromM15(points)
    }
}

/**
 * Единая точка обновления 15м «Рынок»: канонический ряд + live Z overlay.
 * Все refresh-пути должны вызывать это вместо разрозненных store/publish.
 */
internal suspend fun MoexScreenState.commitMarketsM15ToUi(
    points: List<DataPoint>,
    snap: MarketsIntraday1mSnapshot? = cachedMarketsIntraday1mSnapshot(),
    reason: String = "commit",
    alsoPortfolio: Boolean = true,
) {
    if (points.size < 2) return
    val canonicalIssues = validateMarketsM15CanonicalSeries(points)
    if (canonicalIssues.isNotEmpty()) {
        MoexDiagnostics.log(
            context,
            "m15_pipe",
            "canonical_warn reason=$reason ${formatMarketsM15PipelineIssues(canonicalIssues)}",
        )
    }
    storeMarketsM15(points)
    if (alsoPortfolio) {
        portfolioM15Points = points
    }
    publishMarketsLiveZFromPoints(points, snap = snap)
    refreshMarketsM15TodayChartOverlay(snap)
    val overlayIssues = validateMarketsUiSnapshot(
        cache = marketsM15Source(),
        portfolio = portfolioM15Points,
        liveZ = marketsLiveZScore,
        liveBarAt = marketsLiveZBarAt,
    ).filter { it.code != "dup_label" && it.code != "gap" }
    if (overlayIssues.isNotEmpty()) {
        MoexDiagnostics.log(
            context,
            "m15_pipe",
            "overlay_warn reason=$reason ${formatMarketsM15PipelineIssues(overlayIssues)}",
        )
    }
}

internal suspend fun MoexScreenState.clearMarketsM15Session() {
    withContext(Dispatchers.Main.immediate) {
        marketsM15SessionCache = emptyList()
        marketsM15DataEpoch++
    }
}
