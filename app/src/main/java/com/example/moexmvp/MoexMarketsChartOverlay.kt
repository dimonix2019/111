package com.example.moexmvp

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Сортировка меток 1м HH:mm по времени (не лексикографически). */
internal fun intraday1mLabelSortKey(label: String): Int {
    val t = LocalTime.parse(label.trim(), intradayLabelFormatter)
    return t.hour * 60 + t.minute
}

/** Сегодняшние 15м бары из replay 1м (только для Z-графика). */
internal fun buildTodayM15OverlayFromIntraday1m(
    m15Points: List<DataPoint>,
    snap: MarketsIntraday1mSnapshot,
    zone: ZoneId = moexZoneId,
): List<DataPoint> {
    val aligned = alignIntraday1mCloseSeries(snap.tatn, snap.tatnp) ?: return emptyList()
    val replayed = replayTodayM15FromIntraday1m(m15Points, aligned, zone) ?: return emptyList()
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    return replayed.filter { it.timestampMillis >= todayStart }
}

/** MOEX 10м→15м за сегодня (fallback, если 1м replay не закрыл дыру). */
internal suspend fun fetchTodayM15ChartOverlayFromMoex10m(
    m15History: List<DataPoint>,
): List<DataPoint>? = withContext(Dispatchers.IO) {
    val today = LocalDate.now(moexZoneId)
    val entities = fetchPortfolio15mSpreadEntities(today, portfolioM15MoexFetchTillDate())
    buildTodayM15OverlayFromMoexEntities(m15History, entities)
}

internal fun buildTodayM15OverlayFromMoexEntities(
    m15History: List<DataPoint>,
    todayEntities: List<PortfolioM15SpreadEntity>,
    zone: ZoneId = moexZoneId,
): List<DataPoint>? {
    if (todayEntities.isEmpty() || m15History.size < Z_SCORE_ROLLING_MIN_BARS) return null
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    var base = m15History.filter { it.timestampMillis < todayStart }
    if (base.size < Z_SCORE_ROLLING_MIN_BARS) {
        base = m15History.dropLastWhile { it.timestampMillis >= todayStart }
    }
    if (base.size < Z_SCORE_ROLLING_MIN_BARS) return null
    val todayPoints = todayEntities.map { it.toDataPoint() }
    val combined = (base + todayPoints).toMutableList()
    applyZScoresDefaultInPlace(combined)
    return combined.filter { it.timestampMillis >= todayStart }
}

/**
 * Канонический MOEX-ряд + overlay сегодня (1м/MOEX 10м).
 * Overlay побеждает в совпадающих 15м слотах.
 */
internal fun applyTodayM15OverlayForChart(
    canonical: List<DataPoint>,
    overlayToday: List<DataPoint>,
): List<DataPoint> {
    if (canonical.isEmpty() || overlayToday.isEmpty()) return canonical
    val zone = moexZoneId
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val beforeToday = canonical.filter { it.timestampMillis < todayStart }
    val byLabel = linkedMapOf<String, DataPoint>()
    canonical.filter { it.timestampMillis >= todayStart }.forEach { byLabel[it.tradeDate] = it }
    overlayToday.forEach { byLabel[it.tradeDate] = it }
    return ensureAscendingM15Points(beforeToday + byLabel.values.sortedBy { it.timestampMillis })
}

internal fun mergeM15WithToday1mBackfillForChart(
    canonical: List<DataPoint>,
    aligned: AlignedIntraday1mQuotes?,
    zone: ZoneId = moexZoneId,
): List<DataPoint> {
    if (canonical.isEmpty() || aligned == null) return canonical
    val replayed = replayTodayM15FromIntraday1m(canonical, aligned, zone) ?: return canonical
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val overlay = replayed.filter { it.timestampMillis >= todayStart }
    return applyTodayM15OverlayForChart(canonical, overlay)
}

/** Обновить overlay Z-графика (вызывается после 1м poll и commit 15м). */
internal suspend fun MoexScreenState.refreshMarketsM15TodayChartOverlay(
    snap: MarketsIntraday1mSnapshot? = cachedMarketsIntraday1mSnapshot(),
) {
    val zBase = resolveMarketsM15PointsForLiveZ(marketsM15Source())
    if (zBase.size < Z_SCORE_ROLLING_MIN_BARS) return
    var overlay = snap?.let { buildTodayM15OverlayFromIntraday1m(zBase, it) }.orEmpty()
    val probe = applyTodayM15OverlayForChart(zBase, overlay)
    val todayStart = LocalDate.now(moexZoneId).atStartOfDay(moexZoneId).toInstant().toEpochMilli()
    val todayProbe = probe.filter { it.timestampMillis >= todayStart }
    if (m15SeriesHasIntradayTradingGap(todayProbe)) {
        if (isMoexNetworkAvailable(context)) {
            fetchTodayM15ChartOverlayFromMoex10m(zBase)?.let { moexToday ->
                if (moexToday.size > overlay.size) overlay = moexToday
            }
        }
    }
    withContext(Dispatchers.Main.immediate) {
        marketsM15TodayChartOverlay = overlay
        marketsM15ChartOverlayEpoch++
    }
}

internal fun MoexScreenState.buildMarketsM15PointsForZChart(period: Period): List<DataPoint> {
    val merged = applyTodayM15OverlayForChart(
        marketsM15Source(),
        marketsM15TodayChartOverlay,
    )
    return filterM15PointsForMarketsPeriod(merged, period)
}
