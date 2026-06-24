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

/**
 * Session cache + SQLite: по каждому 15м слоту берём более полный ряд.
 * SQLite часто содержит дневные бары, которых нет в устаревшем session cache.
 */
internal fun mergeM15SessionWithSqliteForChart(
    session: List<DataPoint>,
    sqlite: List<DataPoint>,
): List<DataPoint> {
    if (sqlite.isEmpty()) return session
    if (session.isEmpty()) return sqlite
    val byLabel = linkedMapOf<String, DataPoint>()
    session.forEach { byLabel[it.tradeDate] = it }
    sqlite.forEach { byLabel[it.tradeDate] = it }
    return ensureAscendingM15Points(byLabel.values.sortedBy { it.timestampMillis })
}

internal fun m15TodayBarCount(points: List<DataPoint>, zone: ZoneId = moexZoneId): Int {
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    return points.count { it.timestampMillis >= todayStart }
}

/** MOEX 10м→15м за сегодня: нет баров в кэше, дыра внутри дня или утро неполное. */
internal fun m15TodayOverlayNeedsMoexFetch(
    zBase: List<DataPoint>,
    todayProbe: List<DataPoint>,
    zone: ZoneId = moexZoneId,
): Boolean {
    if (m15SeriesHasIntradayTradingGap(todayProbe)) return true
    val todayStart = m15SessionOpenMillis(LocalDate.now(zone), zone)
    if (zBase.none { it.timestampMillis >= todayStart }) return true
    val expected = m15ExpectedTodayBarsSoFar(zone = zone)
    if (expected >= 3 && todayProbe.size < expected - 2) return true
    val firstToday = todayProbe.firstOrNull()?.timestampMillis ?: return false
    if (firstToday > todayStart + 15 * 60_000L && expected >= 2) return true
    return false
}

/** Чтение 255д 15м из SQLite (без сети). */
internal suspend fun MoexScreenState.loadMarketsM15FromSqliteCache(): List<DataPoint> =
    loadM15ForMarkets(
        mode = PortfolioM15LoadMode.CACHE_ONLY,
        wrapInSession = false,
    )

/**
 * Обновить SQLite-снимок для Z-графика; при дыре в session — commit из SQLite.
 * Догрузка хвоста с MOEX — отдельно через [requestMarketsM15TailStale] / catchup.
 */
internal suspend fun MoexScreenState.refreshMarketsM15SqliteChartCache(reason: String) {
    val fromSqlite = loadMarketsM15FromSqliteCache()
    if (fromSqlite.size < 2) return
    withContext(Dispatchers.Main.immediate) {
        marketsM15SqliteChartCache = fromSqlite
        marketsM15SqliteChartEpoch++
    }
    val session = marketsM15Source()
    if (session.size < 2) {
        commitMarketsM15ToUi(fromSqlite, reason = "sqlite_hydrate_$reason")
        return
    }
    val zone = moexZoneId
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val sessionToday = session.filter { it.timestampMillis >= todayStart }
    val sqliteToday = fromSqlite.filter { it.timestampMillis >= todayStart }
    val sessionGap = m15SeriesHasIntradayTradingGap(sessionToday)
    val sqliteGap = m15SeriesHasIntradayTradingGap(sqliteToday)
    val sqliteRicher = sqliteToday.size > sessionToday.size ||
        (sessionGap && !sqliteGap) ||
        fromSqlite.size > session.size
    if (sqliteRicher) {
        commitMarketsM15ToUi(fromSqlite, reason = "sqlite_chart_$reason")
    }
    MarketsM15ChartDiagnostics.logStage(
        context,
        "sqlite_refresh",
        buildString {
            append("reason=$reason ")
            append("session{${snapshotM15Series(session).toLogFields()}} ")
            append("sqlite{${snapshotM15Series(fromSqlite).toLogFields()}} ")
            append("synced=$sqliteRicher")
        },
    )
    logMarketsM15ChartBuild(marketsZChartPeriod.coerceToMarketsUiPeriod())
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
    if (m15TodayOverlayNeedsMoexFetch(zBase, todayProbe)) {
        if (isMoexNetworkAvailable(context)) {
            fetchTodayM15ChartOverlayFromMoex10m(zBase)?.let { moexToday ->
                overlay = mergeTodayM15ChartOverlays(overlay, moexToday)
            }
        }
    }
    withContext(Dispatchers.Main.immediate) {
        marketsM15TodayChartOverlay = overlay
        marketsM15ChartOverlayEpoch++
    }
    MarketsM15ChartDiagnostics.logStage(
        context,
        "overlay_refresh",
        buildString {
            append("overlay_bars=${overlay.size} ")
            append("1m_tatn=${snap?.tatn?.size ?: 0} 1m_tatnp=${snap?.tatnp?.size ?: 0} ")
            append("probe{${snapshotM15Series(todayProbe).toLogFields()}} ")
            append("still_gap=${m15SeriesHasIntradayTradingGap(todayProbe)} ")
            append("moex_today_fetch=${m15TodayOverlayNeedsMoexFetch(zBase, todayProbe)}")
        },
    )
    logMarketsM15ChartBuild(marketsZChartPeriod.coerceToMarketsUiPeriod())
}

internal fun MoexScreenState.buildMarketsM15PointsForZChart(period: Period): List<DataPoint> {
    val session = marketsM15Source()
    val sqlite = marketsM15SqliteChartCache
    val base = mergeM15SessionWithSqliteForChart(session, sqlite)
    val merged = applyTodayM15OverlayForChart(base, marketsM15TodayChartOverlay)
    val filtered = filterM15PointsForMarketsPeriod(merged, period)
    val zBase = mergeM15SessionWithSqliteForChart(session, sqlite)
    val withZ = if (zBase.size >= Z_SCORE_ROLLING_MIN_BARS) {
        recalcM15ZForChartDisplayWindow(filtered, zBase)
    } else {
        filtered
    }
    logMarketsM15ChartBuild(period)
    return withZ
}
