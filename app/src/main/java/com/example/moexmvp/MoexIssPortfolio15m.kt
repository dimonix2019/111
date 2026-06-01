package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.sqrt

internal suspend fun fetchPortfolio15mSpreadEntities(
    from: LocalDate,
    till: LocalDate
): List<PortfolioM15SpreadEntity> = withContext(Dispatchers.IO) {
    val zone = ZoneId.of("Europe/Moscow")
    val (tatnBars, tatnpBars) = coroutineScope {
        val d1 = async { loadCandleBars("TATN", from, till, interval = 10) }
        val d2 = async { loadCandleBars("TATNP", from, till, interval = 10) }
        Pair(d1.await(), d2.await())
    }
    val tatn15 = aggregateTo15MinuteBars(tatnBars).associateBy { it.timestamp }
    val tatnp15 = aggregateTo15MinuteBars(tatnpBars).associateBy { it.timestamp }
    val times = tatn15.keys.intersect(tatnp15.keys).sorted()
    if (times.isEmpty()) return@withContext emptyList()
    val closed = times.mapNotNull { ts ->
        val c1 = tatn15[ts]?.close ?: return@mapNotNull null
        val c2 = tatnp15[ts]?.close ?: return@mapNotNull null
        if (c2 == 0.0) return@mapNotNull null
        val spread = (c1 / c2 - 1.0) * 100.0
        PortfolioM15SpreadEntity(
            tsMillis = ts.atZone(zone).toInstant().toEpochMilli(),
            tatnClose = c1,
            tatnpClose = c2,
            spreadPercent = spread,
            diff = c1 - c2
        )
    }
    appendFormingPortfolio15mBar(closed, tatnBars, tatnpBars)
}

/** Незакрытый 15м слот по 10м свечам (хвост не отстаёт на период). */
internal fun appendFormingPortfolio15mBar(
    entities: List<PortfolioM15SpreadEntity>,
    tatn10: List<CandleBar>,
    tatnp10: List<CandleBar>,
): List<PortfolioM15SpreadEntity> {
    if (entities.isEmpty() || tatn10.isEmpty() || tatnp10.isEmpty()) return entities
    val zone = moexZoneId
    val now = java.time.ZonedDateTime.now(zone)
    val bucket = now.withMinute((now.minute / 15) * 15).withSecond(0).withNano(0).toLocalDateTime()
    val bucketMillis = bucket.atZone(zone).toInstant().toEpochMilli()
    if (entities.last().tsMillis >= bucketMillis) return entities

    val tatnByTime = tatn10.associateBy { it.timestamp }
    val tatnpByTime = tatnp10.associateBy { it.timestamp }
    val nowLdt = now.toLocalDateTime()
    val alignedTimes = tatnByTime.keys.intersect(tatnpByTime.keys)
        .filter { !it.isBefore(bucket) && !it.isAfter(nowLdt) }
        .sorted()
    if (alignedTimes.isEmpty()) return entities

    val lastTatn = alignedTimes.mapNotNull { tatnByTime[it]?.close }.lastOrNull() ?: return entities
    val lastTatnp = alignedTimes.mapNotNull { tatnpByTime[it]?.close }.lastOrNull() ?: return entities
    if (lastTatnp == 0.0) return entities

    val spread = (lastTatn / lastTatnp - 1.0) * 100.0
    return entities + PortfolioM15SpreadEntity(
        tsMillis = bucketMillis,
        tatnClose = lastTatn,
        tatnpClose = lastTatnp,
        spreadPercent = spread,
        diff = lastTatn - lastTatnp
    )
}

/** MOEX ISS: параметр till — как правило до начала следующего календарного дня (включить сегодня). */
internal fun portfolioM15MoexFetchTillDate(): LocalDate =
    LocalDate.now(moexZoneId).plusDays(1)

internal fun portfolio15mSeriesTailStale(points: List<DataPoint>): Boolean {
    val lastTs = points.lastOrNull()?.timestampMillis ?: return true
    return System.currentTimeMillis() - lastTs > PORTFOLIO_M15_TAIL_MAX_AGE_MS
}

internal suspend fun resolvePortfolioM15LoadMode(context: Context): PortfolioM15LoadMode {
    val dao = PortfolioM15Database.get(context).dao()
    if (dao.count() == 0) return PortfolioM15LoadMode.INCREMENTAL
    val lastTs = dao.maxTsMillis() ?: return PortfolioM15LoadMode.INCREMENTAL
    val today = LocalDate.now(moexZoneId)
    val lastDay = portfolioM15LastBarDayFromTs(lastTs)
    val lastTsAgeMs = System.currentTimeMillis() - lastTs
    return resolvePortfolioM15LoadModeForLastBar(lastDay, today, lastTsAgeMs)
}

/**
 * Загрузка 15м ряда с догрузкой до «сегодня» (МСК): INCREMENTAL, при необходимости FULL_REFRESH.
 */
internal suspend fun loadPortfolio15mSeriesEnsuringRecentTail(
    context: Context,
    from: LocalDate,
    preferredMode: PortfolioM15LoadMode,
    onProgress: DataLoadProgressCallback = null,
): List<DataPoint> {
    val till = LocalDate.now(moexZoneId)
    val today = till
    var points = loadPortfolio15mDataPoints(context, from, till, preferredMode, onProgress)
    if (!portfolio15mSeriesTailStale(points)) return points

    val lastDay = points.lastOrNull()?.timestampMillis?.let { portfolioM15LastBarDayFromTs(it) }
    if (preferredMode != PortfolioM15LoadMode.FULL_REFRESH &&
        lastDay != null &&
        lastDay.isBefore(today.minusDays(1))
    ) {
        points = loadPortfolio15mDataPoints(context, from, till, PortfolioM15LoadMode.FULL_REFRESH, onProgress)
        if (!portfolio15mSeriesTailStale(points)) return points
    }

    if (preferredMode != PortfolioM15LoadMode.INCREMENTAL) {
        points = loadPortfolio15mDataPoints(context, from, till, PortfolioM15LoadMode.INCREMENTAL, onProgress)
        if (!portfolio15mSeriesTailStale(points)) return points
    }
    return loadPortfolio15mDataPoints(context, from, till, PortfolioM15LoadMode.FULL_REFRESH, onProgress)
}

/**
 * 15м ряд и Z для фонового монитора, UI-сигналов и тестовых входов —
 * тот же кэш ISS 10m→15m и lookback, что вкладка «Портфель».
 */
internal suspend fun loadPortfolio15mPointsForSignalMonitor(
    context: Context,
    mode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
    onProgress: DataLoadProgressCallback = null,
): List<DataPoint> {
    val app = context.applicationContext
    val till = LocalDate.now(moexZoneId)
    val from = till.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
    return when (mode) {
        PortfolioM15LoadMode.CACHE_ONLY ->
            loadPortfolio15mDataPoints(app, from, till, mode, onProgress)
        else ->
            loadPortfolio15mSeriesEnsuringRecentTail(app, from, mode, onProgress)
    }
}

/**
 * 15m portfolio series with local Room cache on device.
 *
 * INCREMENTAL: merge cache with a short tail fetch from MOEX.
 * FULL_REFRESH: wipe cache and download the full from..till window.
 * CACHE_ONLY: read SQLite only (no network).
 */
internal suspend fun loadPortfolio15mDataPoints(
    context: Context,
    from: LocalDate,
    till: LocalDate,
    mode: PortfolioM15LoadMode,
    onProgress: DataLoadProgressCallback = null,
): List<DataPoint> = withContext(Dispatchers.IO) {
    withPortfolioM15LoadLock {
        val dao = PortfolioM15Database.get(context).dao()
        val zone = moexZoneId
        val moexFetchTill = portfolioM15MoexFetchTillDate()
        val cutoffMillis = till.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()

        dao.deleteOlderThan(cutoffMillis)

        val cacheInDb = dao.countSince(cutoffMillis)
        val moexTargetEstimate = when (mode) {
            PortfolioM15LoadMode.CACHE_ONLY -> 0
            PortfolioM15LoadMode.INCREMENTAL -> {
                val lastTs = dao.maxTsMillis()
                val mergeFrom = if (lastTs == null) {
                    from
                } else {
                    val lastDay = Instant.ofEpochMilli(lastTs).atZone(zone).toLocalDate()
                    maxOf(from, lastDay.minusDays(PORTFOLIO_M15_INCREMENTAL_OVERLAP_DAYS))
                }
                estimateM15BarCount(mergeFrom, moexFetchTill)
            }
            PortfolioM15LoadMode.FULL_REFRESH ->
                estimateM15BarCount(from, moexFetchTill)
        }

        onProgress?.invoke(
            DataLoadProgress(
                phase = DataLoadPhase.CacheRead,
                cacheBarsLoaded = 0,
                cacheBarsTotal = cacheInDb.coerceAtLeast(1),
                moexBarsTotal = moexTargetEstimate,
                detail = when (mode) {
                    PortfolioM15LoadMode.CACHE_ONLY -> "чтение кэша"
                    else -> "подготовка"
                },
            )
        )

        when (mode) {
            PortfolioM15LoadMode.CACHE_ONLY -> Unit

            PortfolioM15LoadMode.FULL_REFRESH -> {
                downloadPortfolio15mFullRangeToDao(dao, from, moexFetchTill, onProgress)
            }

            PortfolioM15LoadMode.INCREMENTAL -> {
                val lastTs = dao.maxTsMillis()
                val mergeFrom = if (lastTs == null) {
                    from
                } else {
                    val lastDay = Instant.ofEpochMilli(lastTs).atZone(zone).toLocalDate()
                    val overlapStart = lastDay.minusDays(PORTFOLIO_M15_INCREMENTAL_OVERLAP_DAYS)
                    maxOf(from, overlapStart)
                }
                if (mergeFrom <= moexFetchTill) {
                    val moexTarget = estimateM15BarCount(mergeFrom, moexFetchTill)
                    var barsDownloaded = 0
                    val fresh = fetchPortfolio15mSpreadEntitiesChunked(
                        mergeFrom,
                        moexFetchTill,
                        onChunk = { chunkIndex, chunkTotalCount, barsInChunk, _ ->
                            barsDownloaded += barsInChunk
                            onProgress?.invoke(
                                DataLoadProgress(
                                    phase = DataLoadPhase.MoexDownload,
                                    cacheBarsLoaded = cacheInDb,
                                    cacheBarsTotal = cacheInDb.coerceAtLeast(1),
                                    moexBarsLoaded = barsDownloaded.coerceAtMost(moexTarget),
                                    moexBarsTotal = moexTarget,
                                    moexChunkIndex = chunkIndex,
                                    moexChunkTotal = chunkTotalCount,
                                    detail = "догрузка MOEX",
                                )
                            )
                        },
                    )
                    insertPortfolio15mEntitiesBatched(dao, fresh)
                }
            }
        }

        val lastTsAfterLoad = dao.maxTsMillis() ?: 0L
        val tailStillStale = System.currentTimeMillis() - lastTsAfterLoad > PORTFOLIO_M15_TAIL_MAX_AGE_MS
        if (mode != PortfolioM15LoadMode.FULL_REFRESH && tailStillStale) {
            mergePortfolio15mRecentTailFromMoex(dao, onProgress)
        }

        val cacheTotal = dao.countSince(cutoffMillis).coerceAtLeast(1)
        onProgress?.invoke(
            DataLoadProgress(
                phase = DataLoadPhase.CacheRead,
                cacheBarsLoaded = cacheTotal,
                cacheBarsTotal = cacheTotal,
                moexBarsLoaded = if (mode == PortfolioM15LoadMode.CACHE_ONLY) 0 else moexTargetEstimate,
                moexBarsTotal = moexTargetEstimate,
                detail = "чтение кэша SQLite",
            )
        )
        val rows = dao.getSince(cutoffMillis)
        if (rows.isEmpty()) return@withPortfolioM15LoadLock emptyList()

        onProgress?.invoke(
            DataLoadProgress(
                phase = DataLoadPhase.ApplyingZ,
                cacheBarsLoaded = rows.size,
                cacheBarsTotal = rows.size.coerceAtLeast(cacheTotal),
                moexBarsLoaded = moexTargetEstimate.coerceAtLeast(rows.size),
                moexBarsTotal = moexTargetEstimate.coerceAtLeast(rows.size),
                detail = "расчёт Z-score",
            )
        )
        applyZScoresDefault(rows.map { it.toDataPoint() })
    }
}

