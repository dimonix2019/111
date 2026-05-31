package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Размер пакета INSERT в Room (лимит SQLite ~999 bind-переменных на запрос). */
internal const val PORTFOLIO_M15_ROOM_INSERT_BATCH = 80

/** Загрузка MOEX по окнам, чтобы не обрывать соединение на 255 днях. */
internal const val PORTFOLIO_M15_FETCH_CHUNK_DAYS = 21L

/** Догрузка хвоста 15м с MOEX только если кэш устарел (не при каждом INCREMENTAL). */
internal const val PORTFOLIO_M15_TAIL_REFETCH_DAYS = 7L

/** Если последний бар в кэше старше — не CACHE_ONLY, а догрузка с MOEX. */
internal const val PORTFOLIO_M15_CACHE_STALE_MS = 6L * 60L * 60L * 1000L

private val portfolioM15LoadMutex = Mutex()

internal suspend fun <T> withPortfolioM15LoadLock(block: suspend () -> T): T =
    portfolioM15LoadMutex.withLock { block() }

/**
 * Загрузка спрэда 15м с MOEX кусками по [chunkDays] календарных дней.
 */
internal suspend fun fetchPortfolio15mSpreadEntitiesChunked(
    from: LocalDate,
    till: LocalDate,
    chunkDays: Long = PORTFOLIO_M15_FETCH_CHUNK_DAYS
): List<PortfolioM15SpreadEntity> {
    if (from.isAfter(till)) return emptyList()
    val byTs = LinkedHashMap<Long, PortfolioM15SpreadEntity>()
    var chunkStart = from
    while (!chunkStart.isAfter(till)) {
        val chunkEnd = minOf(chunkStart.plusDays(chunkDays - 1), till)
        val part = fetchPortfolio15mSpreadEntities(chunkStart, chunkEnd)
        part.forEach { byTs[it.tsMillis] = it }
        chunkStart = chunkEnd.plusDays(1)
    }
    return byTs.values.sortedBy { it.tsMillis }
}

internal suspend fun insertPortfolio15mEntitiesBatched(
    dao: PortfolioM15Dao,
    entities: List<PortfolioM15SpreadEntity>
) {
    if (entities.isEmpty()) return
    entities.chunked(PORTFOLIO_M15_ROOM_INSERT_BATCH).forEach { batch ->
        dao.insertAll(batch)
    }
}

/** Полная перезагрузка окна: каждый чанк сразу в SQLite (не держим год в памяти). */
internal suspend fun downloadPortfolio15mFullRangeToDao(
    dao: PortfolioM15Dao,
    from: LocalDate,
    moexFetchTill: LocalDate
) {
    dao.deleteAll()
    var chunkStart = from
    while (!chunkStart.isAfter(moexFetchTill)) {
        val chunkEnd = minOf(chunkStart.plusDays(PORTFOLIO_M15_FETCH_CHUNK_DAYS - 1), moexFetchTill)
        val part = fetchPortfolio15mSpreadEntities(chunkStart, chunkEnd)
        insertPortfolio15mEntitiesBatched(dao, part)
        chunkStart = chunkEnd.plusDays(1)
    }
}

/** Принудительная догрузка хвоста (последние [PORTFOLIO_M15_TAIL_REFETCH_DAYS] дн.) в SQLite. */
internal suspend fun mergePortfolio15mRecentTailFromMoex(dao: PortfolioM15Dao) {
    val moexTill = portfolioM15MoexFetchTillDate()
    val tailFrom = LocalDate.now(moexZoneId).minusDays(PORTFOLIO_M15_TAIL_REFETCH_DAYS)
    if (tailFrom.isAfter(moexTill)) return
    val tail = fetchPortfolio15mSpreadEntitiesChunked(
        from = tailFrom,
        till = moexTill,
        chunkDays = 7L
    )
    insertPortfolio15mEntitiesBatched(dao, tail)
}

/**
 * Режим загрузки 15м по последнему бару в кэше (без Room).
 * Если последний календарный день старше вчера (МСК) — FULL_REFRESH (типичный обрыв ~13.05).
 */
internal fun resolvePortfolioM15LoadModeForLastBar(
    lastBarDay: LocalDate?,
    todayMoscow: LocalDate,
    lastTsAgeMs: Long
): PortfolioM15LoadMode {
    if (lastBarDay == null) return PortfolioM15LoadMode.INCREMENTAL
    if (lastBarDay.isBefore(todayMoscow.minusDays(1))) return PortfolioM15LoadMode.FULL_REFRESH
    if (lastBarDay.isBefore(todayMoscow)) return PortfolioM15LoadMode.INCREMENTAL
    val stale = lastTsAgeMs > PORTFOLIO_M15_CACHE_STALE_MS
    return if (stale) PortfolioM15LoadMode.INCREMENTAL else PortfolioM15LoadMode.CACHE_ONLY
}

internal fun portfolioM15LastBarDayFromTs(lastTsMillis: Long?): LocalDate? {
    if (lastTsMillis == null) return null
    return Instant.ofEpochMilli(lastTsMillis).atZone(moexZoneId).toLocalDate()
}
