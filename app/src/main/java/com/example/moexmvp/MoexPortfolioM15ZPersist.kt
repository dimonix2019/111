package com.example.moexmvp

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/**
 * Снимок rolling-Z и spread на закрытом 15м баре (parity live-монитор ↔ «Тест страт.»).
 * MOEX позже может пересмотреть close → spread скачет → ложный Z-cross (напр. 10.06 18:00).
 */

/** Подозрительный скачок spread за один 15м бар (пересмотр MOEX / артефакт). */
internal const val M15_SPREAD_REVISION_JUMP_PP = 0.35

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

internal fun isM15SpreadRevisionSpike(
    index: Int,
    points: List<DataPoint>,
    entities: List<PortfolioM15SpreadEntity> = emptyList(),
): Boolean {
    if (index <= 0) return false
    val prevSpread = entities.getOrNull(index - 1)?.spreadAtZSnapshot
        ?: points[index - 1].spreadPercent
    return points[index].spreadPercent - prevSpread > M15_SPREAD_REVISION_JUMP_PP
}

internal fun spreadForRollingZAt(
    index: Int,
    points: List<DataPoint>,
    entities: List<PortfolioM15SpreadEntity>,
): Double {
    val entity = entities[index]
    if (entity.spreadAtZSnapshot != null && !isM15SpreadRevisionSpike(index, points, entities)) {
        return entity.spreadAtZSnapshot
    }
    val current = points[index].spreadPercent
    if (index <= 0) return current
    val prevSpread = entities[index - 1].spreadAtZSnapshot ?: points[index - 1].spreadPercent
    if (current - prevSpread > M15_SPREAD_REVISION_JUMP_PP) {
        return prevSpread
    }
    return current
}

/** Rolling-Z: снимки spread/Z не перезаписываются; без снимка — spread с guard от скачков. */
internal fun fillM15ZScoresInPlace(
    points: MutableList<DataPoint>,
    entities: List<PortfolioM15SpreadEntity>,
): Boolean {
    if (entities.size != points.size || points.isEmpty()) return false
    val spreads = DoubleArray(points.size) { i -> spreadForRollingZAt(i, points, entities) }
    val cache = RollingSpreadStatsCache.from(
        points.mapIndexed { i, p -> p.copy(spreadPercent = spreads[i]) },
    )
    var recalculated = false
    for (i in points.indices) {
        val snapZ = entities[i].persistedZScore
        if (snapZ != null && !isM15SpreadRevisionSpike(i, points, entities)) {
            if (points[i].zScore != snapZ) {
                points[i] = points[i].copy(zScore = snapZ)
            }
            continue
        }
        val stats = cache.at(i)
        val z = if (stats == null) 0.0 else zScoreAtSpread(spreads[i], stats)
        points[i] = points[i].copy(zScore = z)
        recalculated = true
    }
    return recalculated
}

internal fun applyJournalObservedZOverlay(
    points: MutableList<DataPoint>,
    events: List<StrategySignalEvent>,
): Int {
    if (events.isEmpty() || points.size < 2) return 0
    val snaps = snapStrategySignalEventsToExecutionPoints(points, events)
    var n = 0
    for (snap in snaps) {
        val z = snap.event.zScore
        if (points[snap.index].zScore != z) {
            points[snap.index] = points[snap.index].copy(zScore = z)
            n++
        }
    }
    return n
}

internal suspend fun backfillPersistedZFromJournal(
    context: Context,
    events: List<StrategySignalEvent>,
    points: List<DataPoint>,
) {
    if (events.isEmpty() || points.size < 2) return
    val dao = PortfolioM15Database.get(context.applicationContext).dao()
    val snaps = snapStrategySignalEventsToExecutionPoints(points, events)
    if (snaps.isEmpty()) return
    val tsList = snaps.map { points[it.index].timestampMillis }
    val existing = dao.getByTsMillis(tsList).associateBy { it.tsMillis }
    val updated = snaps.mapNotNull { snap ->
        val pt = points[snap.index]
        val prev = existing[pt.timestampMillis] ?: return@mapNotNull null
        if (prev.persistedZScore != null && prev.spreadAtZSnapshot != null) return@mapNotNull null
        prev.copy(
            persistedZScore = snap.event.zScore,
            spreadAtZSnapshot = prev.spreadAtZSnapshot ?: prev.spreadPercent,
        )
    }
    if (updated.isNotEmpty()) {
        mergePortfolioM15InsertPreservingSnapshots(dao, updated)
    }
}

/** Снимок Z+spread с бара, обработанного live-монитором (в т.ч. без сигнала). */
internal suspend fun persistM15LiveBarSnapshots(
    context: Context,
    bars: List<DataPoint>,
) {
    if (bars.isEmpty()) return
    val dao = PortfolioM15Database.get(context.applicationContext).dao()
    val tsList = bars.map { it.timestampMillis }
    val existing = dao.getByTsMillis(tsList).associateBy { it.tsMillis }
    val updated = bars.mapNotNull { bar ->
        val prev = existing[bar.timestampMillis] ?: return@mapNotNull null
        if (prev.persistedZScore != null && prev.spreadAtZSnapshot != null) return@mapNotNull null
        prev.copy(
            persistedZScore = bar.zScore,
            spreadAtZSnapshot = bar.spreadPercent,
        )
    }
    if (updated.isNotEmpty()) {
        mergePortfolioM15InsertPreservingSnapshots(dao, updated)
    }
}

internal suspend fun persistM15ZScoreSnapshots(
    dao: PortfolioM15Dao,
    entities: List<PortfolioM15SpreadEntity>,
    points: List<DataPoint>,
) {
    if (entities.isEmpty() || entities.size != points.size) return
    val firstStale = entities.indexOfFirst { it.persistedZScore == null }
    if (firstStale < 0) return
    val updated = (firstStale until entities.size).map { i ->
        entities[i].copy(
            persistedZScore = points[i].zScore,
            spreadAtZSnapshot = entities[i].spreadAtZSnapshot ?: points[i].spreadPercent,
        )
    }
    mergePortfolioM15InsertPreservingSnapshots(dao, updated)
}

internal suspend fun mergePortfolioM15InsertPreservingSnapshots(
    dao: PortfolioM15Dao,
    incoming: List<PortfolioM15SpreadEntity>,
) {
    if (incoming.isEmpty()) return
    incoming.chunked(PORTFOLIO_M15_ROOM_INSERT_BATCH).forEach { batch ->
        val existing = dao.getByTsMillis(batch.map { it.tsMillis }).associateBy { it.tsMillis }
        val merged = batch.map { row ->
            val prev = existing[row.tsMillis]
            if (prev == null) row
            else row.copy(
                persistedZScore = row.persistedZScore ?: prev.persistedZScore,
                spreadAtZSnapshot = row.spreadAtZSnapshot ?: prev.spreadAtZSnapshot,
            )
        }
        dao.insertAll(merged)
    }
}

/** Rolling-Z без снимков SQLite: spread guard + пересчёт. */
internal fun applySpreadGuardZScoresInPlace(points: MutableList<DataPoint>) {
    if (points.isEmpty()) return
    val spreads = DoubleArray(points.size) { i ->
        if (i <= 0) points[i].spreadPercent
        else {
            val jump = points[i].spreadPercent - points[i - 1].spreadPercent
            if (jump > M15_SPREAD_REVISION_JUMP_PP) points[i - 1].spreadPercent
            else points[i].spreadPercent
        }
    }
    val forCache = points.mapIndexed { i, p -> p.copy(spreadPercent = spreads[i]) }
    val cache = RollingSpreadStatsCache.from(forCache)
    for (i in points.indices) {
        val stats = cache.at(i)
        points[i] = points[i].copy(
            zScore = if (stats == null) 0.0 else zScoreAtSpread(spreads[i], stats),
        )
    }
}

/** Точки для симуляции «Тест страт.»: Z из SQLite + журнал + guard spread. */
internal fun prepareM15PointsForZStrategySim(
    points: List<DataPoint>,
    entities: List<PortfolioM15SpreadEntity> = emptyList(),
    journalEvents: List<StrategySignalEvent> = emptyList(),
): List<DataPoint> {
    if (points.size < 2) return points
    val mutable = points.map { it.copy(zScore = 0.0) }.toMutableList()
    if (entities.size == points.size) {
        fillM15ZScoresInPlace(mutable, entities)
    } else {
        applySpreadGuardZScoresInPlace(mutable)
    }
    applyJournalObservedZOverlay(mutable, journalEvents)
    return mutable
}
