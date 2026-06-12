package com.example.moexmvp

import java.time.Instant
import java.time.ZoneId

/**
 * Снимок rolling-Z на закрытом 15м баре (parity live-монитор ↔ «Тест страт.»).
 * Пишется после расчёта в [loadPortfolio15mDataPoints]; читается из SQLite без полного пересчёта.
 */

internal fun PortfolioM15SpreadEntity.toDataPointWithPersistedZ(): DataPoint {
    val zone = ZoneId.of("Europe/Moscow")
    val ldt = Instant.ofEpochMilli(tsMillis).atZone(zone).toLocalDateTime()
    return DataPoint(
        timestampMillis = tsMillis,
        tradeDate = ldt.format(portfolio15mLabelFormatter),
        tatnClose = tatnClose,
        tatnpClose = tatnpClose,
        spreadPercent = spreadPercent,
        diff = diff,
        zScore = persistedZScore ?: 0.0,
    )
}

/** true, если пришлось пересчитать rolling-Z (хотя бы один бар без снимка). */
internal fun ensureM15PointsZScoresInPlace(
    points: MutableList<DataPoint>,
    entities: List<PortfolioM15SpreadEntity>,
): Boolean {
    if (entities.size != points.size) return false
    if (entities.none { it.persistedZScore == null }) return false
    applyZScoresDefaultInPlace(points)
    return true
}

/** С первого бара без снимка до конца ряда (overlap MOEX / новый хвост). */
internal suspend fun persistM15ZScoreSnapshots(
    dao: PortfolioM15Dao,
    entities: List<PortfolioM15SpreadEntity>,
    points: List<DataPoint>,
) {
    if (entities.isEmpty() || entities.size != points.size) return
    val firstStale = entities.indexOfFirst { it.persistedZScore == null }
    if (firstStale < 0) return
    val updated = (firstStale until entities.size).map { i ->
        entities[i].copy(persistedZScore = points[i].zScore)
    }
    updated.chunked(PORTFOLIO_M15_ROOM_INSERT_BATCH).forEach { batch ->
        dao.insertAll(batch)
    }
}
