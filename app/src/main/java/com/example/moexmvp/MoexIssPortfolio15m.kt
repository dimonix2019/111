package com.example.moexmvp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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

/** Начало текущего 15м слота (МСК), как в [appendFormingPortfolio15mBar]. */
internal fun currentM15BucketStartMillis(
    now: Instant = Instant.now(),
    zone: ZoneId = moexZoneId,
): Long {
    val zdt = now.atZone(zone)
    val bucket = zdt.withMinute((zdt.minute / 15) * 15).withSecond(0).withNano(0)
    return bucket.toInstant().toEpochMilli()
}

/**
 * 15м ряд для кнопок «Тестовая пара»: всегда догружаем хвост MOEX (включая формирующийся бар),
 * без ожидания [PORTFOLIO_M15_TAIL_MAX_AGE_MS].
 */
internal suspend fun loadPortfolio15mPointsForTestEntry(
    context: Context,
    lookbackDays: Long = marketsM15LookbackDays(Period.OneDay),
): List<DataPoint> = withContext(Dispatchers.IO) {
    withPortfolioM15LoadLock {
        val app = context.applicationContext
        val dao = PortfolioM15Database.get(app).dao()
        mergePortfolio15mRecentTailFromMoex(dao)
        val till = LocalDate.now(moexZoneId)
        val from = till.minusDays(lookbackDays)
        val queryCutoffMillis = from.atStartOfDay(moexZoneId).toInstant().toEpochMilli()
        val rows = dao.getSince(queryCutoffMillis)
        if (rows.isEmpty()) return@withPortfolioM15LoadLock emptyList()
        val points = ArrayList(rows.map { it.toDataPoint() })
        fillM15ZScoresInPlace(points, rows)
        persistM15ZScoreSnapshots(dao, rows, points)
        val bucketStart = currentM15BucketStartMillis()
        val lastTs = points.last().timestampMillis
        if (lastTs >= bucketStart) return@withPortfolioM15LoadLock points
        val today = till
        val refreshFrom = today.minusDays(2)
        val fresh = fetchPortfolio15mSpreadEntities(refreshFrom, portfolioM15MoexFetchTillDate())
        if (fresh.isNotEmpty()) {
            insertPortfolio15mEntitiesBatched(dao, fresh)
            val refreshed = dao.getSince(queryCutoffMillis)
            if (refreshed.isNotEmpty()) {
                val refreshedPoints = ArrayList(refreshed.map { it.toDataPoint() })
                fillM15ZScoresInPlace(refreshedPoints, refreshed)
                persistM15ZScoreSnapshots(dao, refreshed, refreshedPoints)
                return@withPortfolioM15LoadLock refreshedPoints
            }
        }
        points
    }
}

internal fun portfolio15mSeriesTailStale(points: List<DataPoint>): Boolean {
    val lastTs = points.lastOrNull()?.timestampMillis ?: return true
    return System.currentTimeMillis() - lastTs > PORTFOLIO_M15_TAIL_MAX_AGE_MS
}

/** Последний закрытый/формирующийся 15м бар старше ~20 мин — пора догрузить хвост. */
internal fun portfolio15mSeriesIntradayStale(points: List<DataPoint>): Boolean {
    val lastTs = points.lastOrNull()?.timestampMillis ?: return true
    return System.currentTimeMillis() - lastTs > PORTFOLIO_M15_INTRADAY_STALE_MS
}

/** Нужна догрузка MOEX: хвост устарел или последний бар не за сегодня (МСК). */
internal fun portfolio15mSeriesNeedsMoexRefresh(
    points: List<DataPoint>,
    zone: ZoneId = moexZoneId,
): Boolean {
    if (points.isEmpty()) return true
    if (portfolio15mSeriesIntradayStale(points)) return true
    val lastDay = Instant.ofEpochMilli(points.last().timestampMillis).atZone(zone).toLocalDate()
    return lastDay.isBefore(LocalDate.now(zone))
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
    wipeAllOnFullRefresh: Boolean = true,
    retentionDays: Long = PORTFOLIO_M15_CACHE_RETENTION_DAYS,
): List<DataPoint> {
    val till = LocalDate.now(moexZoneId)
    val today = till
    var points = loadPortfolio15mDataPoints(
        context, from, till, preferredMode, onProgress, wipeAllOnFullRefresh, retentionDays,
    )
    if (!portfolio15mSeriesTailStale(points)) return points

    val lastDay = points.lastOrNull()?.timestampMillis?.let { portfolioM15LastBarDayFromTs(it) }
    if (preferredMode != PortfolioM15LoadMode.FULL_REFRESH &&
        lastDay != null &&
        lastDay.isBefore(today.minusDays(1))
    ) {
        points = loadPortfolio15mDataPoints(
            context, from, till, PortfolioM15LoadMode.FULL_REFRESH, onProgress, wipeAllOnFullRefresh, retentionDays,
        )
        if (!portfolio15mSeriesTailStale(points)) return points
    }

    if (preferredMode != PortfolioM15LoadMode.INCREMENTAL) {
        points = loadPortfolio15mDataPoints(
            context, from, till, PortfolioM15LoadMode.INCREMENTAL, onProgress, wipeAllOnFullRefresh, retentionDays,
        )
        if (!portfolio15mSeriesTailStale(points)) return points
    }
    return loadPortfolio15mDataPoints(
        context, from, till, PortfolioM15LoadMode.FULL_REFRESH, onProgress, wipeAllOnFullRefresh, retentionDays,
    )
}

/**
 * 15м ряд и Z для короткого окна «Рынок» / legacy.
 * Для live-сигналов и parity с «Тест страт.» используйте [loadZStrategySignalSeries].
 */
internal suspend fun loadPortfolio15mPointsForSignalMonitor(
    context: Context,
    mode: PortfolioM15LoadMode = PortfolioM15LoadMode.INCREMENTAL,
    onProgress: DataLoadProgressCallback = null,
    lookbackDays: Long = marketsM15LookbackDays(Period.OneDay),
    retentionDays: Long = PORTFOLIO_M15_CACHE_RETENTION_DAYS,
): List<DataPoint> {
    val app = context.applicationContext
    val till = LocalDate.now(moexZoneId)
    val from = till.minusDays(lookbackDays)
    return when (mode) {
        PortfolioM15LoadMode.CACHE_ONLY ->
            loadPortfolio15mDataPoints(app, from, till, mode, onProgress, true, retentionDays)
        else ->
            loadPortfolio15mSeriesEnsuringRecentTail(
                app, from, mode, onProgress, true, retentionDays,
            )
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
    wipeAllOnFullRefresh: Boolean = true,
    retentionDays: Long = PORTFOLIO_M15_CACHE_RETENTION_DAYS,
    /** true для «Тест страт.» при открытии вкладки — только SQLite, без догрузки хвоста с MOEX. */
    skipMoexTailMerge: Boolean = false,
): List<DataPoint> = withContext(Dispatchers.IO) {
    withPortfolioM15LoadLock {
        val dao = PortfolioM15Database.get(context).dao()
        val zone = moexZoneId
        val moexFetchTill = portfolioM15MoexFetchTillDate()
        val retentionCutoffMillis = till.minusDays(retentionDays).atStartOfDay(zone).toInstant().toEpochMilli()
        val queryCutoffMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()

        dao.deleteOlderThan(retentionCutoffMillis)

        val cacheInDb = dao.countSince(queryCutoffMillis)
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
        yield()

        when (mode) {
            PortfolioM15LoadMode.CACHE_ONLY -> Unit

            PortfolioM15LoadMode.FULL_REFRESH -> {
                if (wipeAllOnFullRefresh) {
                    dao.deleteAll()
                }
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
                            yield()
                        },
                    )
                    insertPortfolio15mEntitiesBatched(dao, fresh)
                }
            }
        }

        val lastTsAfterLoad = dao.maxTsMillis() ?: 0L
        val tailAgeMs = System.currentTimeMillis() - lastTsAfterLoad
        val tailStillStale = tailAgeMs > PORTFOLIO_M15_TAIL_MAX_AGE_MS ||
            tailAgeMs > PORTFOLIO_M15_INTRADAY_STALE_MS
        if (!skipMoexTailMerge && mode != PortfolioM15LoadMode.FULL_REFRESH && tailStillStale) {
            mergePortfolio15mRecentTailFromMoex(dao, onProgress)
        }

        val cacheTotal = dao.countSince(queryCutoffMillis).coerceAtLeast(1)
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
        val rows = dao.getSince(queryCutoffMillis)
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
        val points = ArrayList<DataPoint>(rows.size)
        for (entity in rows) {
            points.add(entity.toDataPoint())
        }
        val recalculated = fillM15ZScoresInPlace(points, rows)
        persistM15ZScoreSnapshots(dao, rows, points)
        if (!recalculated) {
            MoexDiagnostics.log(
                context.applicationContext,
                "m15_z",
                "cache_hit persisted_z rows=${rows.size}",
            )
        }
        onProgress?.invoke(DataLoadProgress.idle())
        points
    }
}

/**
 * Лёгкая догрузка MOEX (2 дня) + пересчёт Z на формирующемся 15м баре.
 * Вызывается каждую минуту на экране, даже если хвост «свежий» (<20 мин).
 */
internal suspend fun loadPortfolio15mLiveFormingTailLocked(
    context: Context,
    lookbackDays: Long,
): List<DataPoint>? {
    val dao = PortfolioM15Database.get(context.applicationContext).dao()
    mergePortfolio15mLiveFormingBarFromMoex(dao)
    clearM15LiveTailPersistedZ(dao)
    val till = LocalDate.now(moexZoneId)
    val from = till.minusDays(lookbackDays)
    val queryCutoffMillis = from.atStartOfDay(moexZoneId).toInstant().toEpochMilli()
    val rows = dao.getSince(queryCutoffMillis)
    if (rows.size < 2) return null
    val points = ArrayList(rows.map { it.toDataPoint() })
    val recalculated = fillM15ZScoresInPlace(points, rows)
    persistM15ZScoreSnapshots(dao, rows, points)
    val last = points.last()
    MoexDiagnostics.log(
        context.applicationContext,
        "m15_z",
        "live_tail z=${"%.2f".format(Locale.US, last.zScore)} " +
            "spread=${"%.2f".format(Locale.US, last.spreadPercent)} " +
            "bar=${last.tradeDate} changed=$recalculated",
    )
    return points
}

internal suspend fun refreshPortfolio15mLiveFormingTailFromMoex(
    context: Context,
    lookbackDays: Long,
): List<DataPoint>? = withContext(Dispatchers.IO) {
    withPortfolioM15LoadLock {
        loadPortfolio15mLiveFormingTailLocked(context, lookbackDays)
    }
}

/** Не блокирует UI, если lock занят монитором сигналов. */
internal suspend fun tryRefreshPortfolio15mLiveFormingTailFromMoex(
    context: Context,
    lookbackDays: Long,
): List<DataPoint>? = withContext(Dispatchers.IO) {
    tryWithPortfolioM15LoadLock {
        loadPortfolio15mLiveFormingTailLocked(context, lookbackDays)
    }
}
