package com.example.moexmvp

import java.time.LocalDate

/** Размер пакета INSERT в Room (лимит SQLite ~999 bind-переменных на запрос). */
internal const val PORTFOLIO_M15_ROOM_INSERT_BATCH = 80

/** Загрузка MOEX по окнам, чтобы не обрывать соединение на 255 днях. */
internal const val PORTFOLIO_M15_FETCH_CHUNK_DAYS = 21L

/** Всегда перекачиваем последние N дней отдельными чанками после основной загрузки. */
internal const val PORTFOLIO_M15_TAIL_REFETCH_DAYS = 45L

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
