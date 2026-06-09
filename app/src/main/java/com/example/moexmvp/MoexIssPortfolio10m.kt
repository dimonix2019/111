package com.example.moexmvp

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** ~10:00–18:50 МСК, нативные 10м бары MOEX ISS (оценка для прогресс-бара). */
internal const val PORTFOLIO_M10_BARS_PER_CALENDAR_DAY_ESTIMATE = 51

/**
 * Спрэд TATN/TATNP на **нативных 10м** закрытиях MOEX (без агрегации в 15м).
 * Тот же [PortfolioM15SpreadEntity], что и для кэша 15м — только шаг бара 10 мин.
 */
internal fun aligned10mSpreadEntities(
    tatn10: List<CandleBar>,
    tatnp10: List<CandleBar>,
): List<PortfolioM15SpreadEntity> {
    val zone = moexZoneId
    val tatnByTime = tatn10.associateBy { it.timestamp }
    val tatnpByTime = tatnp10.associateBy { it.timestamp }
    val times = tatnByTime.keys.intersect(tatnpByTime.keys).sorted()
    if (times.isEmpty()) return emptyList()
    return times.mapNotNull { ts ->
        val c1 = tatnByTime[ts]?.close ?: return@mapNotNull null
        val c2 = tatnpByTime[ts]?.close ?: return@mapNotNull null
        if (c2 == 0.0) return@mapNotNull null
        val spread = (c1 / c2 - 1.0) * 100.0
        PortfolioM15SpreadEntity(
            tsMillis = ts.atZone(zone).toInstant().toEpochMilli(),
            tatnClose = c1,
            tatnpClose = c2,
            spreadPercent = spread,
            diff = c1 - c2,
        )
    }
}

internal suspend fun fetchPortfolio10mSpreadEntities(
    from: LocalDate,
    till: LocalDate,
): List<PortfolioM15SpreadEntity> = withContext(Dispatchers.IO) {
    val (tatnBars, tatnpBars) = coroutineScope {
        val d1 = async { loadCandleBars("TATN", from, till, interval = 10) }
        val d2 = async { loadCandleBars("TATNP", from, till, interval = 10) }
        Pair(d1.await(), d2.await())
    }
    appendFormingPortfolio10mBar(
        aligned10mSpreadEntities(tatnBars, tatnpBars),
        tatnBars,
        tatnpBars,
    )
}

/** Незакрытый 10м слот по последним 10м свечам (хвост не отстаёт). */
internal fun appendFormingPortfolio10mBar(
    entities: List<PortfolioM15SpreadEntity>,
    tatn10: List<CandleBar>,
    tatnp10: List<CandleBar>,
): List<PortfolioM15SpreadEntity> {
    if (entities.isEmpty() || tatn10.isEmpty() || tatnp10.isEmpty()) return entities
    val zone = moexZoneId
    val now = java.time.ZonedDateTime.now(zone)
    val bucket = now.withMinute((now.minute / 10) * 10).withSecond(0).withNano(0).toLocalDateTime()
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
        diff = lastTatn - lastTatnp,
    )
}

internal suspend fun fetchPortfolio10mSpreadEntitiesChunked(
    from: LocalDate,
    till: LocalDate,
    chunkDays: Long = PORTFOLIO_M15_FETCH_CHUNK_DAYS,
    onChunk: (suspend (chunkIndex: Int, chunkTotal: Int, barsInChunk: Int, barsTotal: Int) -> Unit)? = null,
): List<PortfolioM15SpreadEntity> {
    if (from.isAfter(till)) return emptyList()
    val byTs = LinkedHashMap<Long, PortfolioM15SpreadEntity>()
    val chunkTotal = countMoexDateChunks(from, till, chunkDays)
    val moexTarget = estimateM10BarCount(from, till)
    var barsTotal = 0
    var chunkIndex = 0
    var chunkStart = from
    while (!chunkStart.isAfter(till)) {
        chunkIndex++
        val chunkEnd = minOf(chunkStart.plusDays(chunkDays - 1), till)
        val part = fetchPortfolio10mSpreadEntities(chunkStart, chunkEnd)
        part.forEach { byTs[it.tsMillis] = it }
        barsTotal += part.size
        onChunk?.invoke(chunkIndex, chunkTotal, part.size, barsTotal.coerceAtMost(moexTarget))
        yield()
        chunkStart = chunkEnd.plusDays(1)
    }
    return byTs.values.sortedBy { it.tsMillis }
}

internal fun estimateM10BarCount(from: LocalDate, till: LocalDate): Int {
    val days = java.time.temporal.ChronoUnit.DAYS.between(from, till).toInt() + 1
    return (days.coerceAtLeast(0) * PORTFOLIO_M10_BARS_PER_CALENDAR_DAY_ESTIMATE).coerceAtLeast(0)
}
