package com.example.moexmvp

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Снимок rolling-Z и spread на закрытом 15м баре (parity live-монитор ↔ «Тест страт.»).
 * MOEX позже может пересмотреть close → spread скачет → ложный Z-cross (напр. 10.06 18:00).
 */

/** Подозрительный скачок spread за один 15м бар (пересмотр MOEX / артефакт). */
internal const val M15_SPREAD_REVISION_JUMP_PP = 0.35

/** Календарных дней от spike до последнего бара: guard только у хвоста (не вся история). */
internal const val M15_SPREAD_GUARD_RECENT_DAYS = 3L

internal fun isSameM15TradeDateDay(a: DataPoint, b: DataPoint): Boolean =
    a.tradeDate.take(10) == b.tradeDate.take(10)

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

internal fun isM15SpreadRevisionSpikeAt(index: Int, points: List<DataPoint>): Boolean {
    if (index <= 0) return false
    return points[index].spreadPercent - points[index - 1].spreadPercent > M15_SPREAD_REVISION_JUMP_PP
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

internal fun isRecentM15SpreadRevisionSpike(index: Int, points: List<DataPoint>): Boolean {
    if (!isM15SpreadRevisionSpikeAt(index, points)) return false
    val zone = ZoneId.of("Europe/Moscow")
    val spikeDay = Instant.ofEpochMilli(barMillisAt(points[index])).atZone(zone).toLocalDate()
    val lastDay = Instant.ofEpochMilli(barMillisAt(points.last())).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(spikeDay, lastDay) <= M15_SPREAD_GUARD_RECENT_DAYS
}

/** Пересчёт Z с демпфированным spread на spike и вперёд (μ/σ по ряду с guard только на spike). */
internal fun recomputeZForwardFromRecentSpikes(
    points: MutableList<DataPoint>,
    spikeIndices: List<Int>,
) {
    if (spikeIndices.isEmpty()) return
    val spikeSet = spikeIndices.toSet()
    val firstSpike = spikeIndices.min()
    val guardedSpreads = points.mapIndexed { j, p ->
        if (j in spikeSet) points[j - 1].spreadPercent else p.spreadPercent
    }
    val cache = RollingSpreadStatsCache.from(
        points.mapIndexed { j, p -> p.copy(spreadPercent = guardedSpreads[j]) },
    )
    for (i in firstSpike until points.size) {
        val refSpike = spikeSet.filter { s ->
            i > s && isSameM15TradeDateDay(points[s], points[i])
        }.maxOrNull()
        val spreadForZ = when {
            i in spikeSet -> points[i - 1].spreadPercent
            refSpike != null && refSpike >= 1 -> points[refSpike - 1].spreadPercent
            else -> points[i].spreadPercent
        }
        val stats = cache.at(i) ?: continue
        points[i] = points[i].copy(zScore = zScoreAtSpread(spreadForZ, stats))
    }
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

/** Rolling-Z: снимки spread/Z не перезаписываются; без снимка — spread guard на хвосте. */
internal fun fillM15ZScoresInPlace(
    points: MutableList<DataPoint>,
    entities: List<PortfolioM15SpreadEntity>,
): Boolean {
    if (entities.size != points.size || points.isEmpty()) return false
    val beforeZ = points.map { it.zScore }
    applyZScoresDefaultInPlace(points)
    val keepPersisted = BooleanArray(points.size) { i ->
        entities[i].persistedZScore != null && !isM15SpreadRevisionSpike(i, points, entities)
    }
    val keptZ = DoubleArray(points.size) { points[it].zScore }
    for (i in points.indices) {
        val snapZ = entities[i].persistedZScore
        if (snapZ != null && !isM15SpreadRevisionSpike(i, points, entities)) {
            points[i] = points[i].copy(zScore = snapZ)
            keptZ[i] = snapZ
        }
    }
    val latestRecentSpike = (1 until points.size)
        .filter { isRecentM15SpreadRevisionSpike(it, points) }
        .maxOrNull()
    if (latestRecentSpike != null) {
        recomputeZForwardFromRecentSpikes(points, listOf(latestRecentSpike))
        for (i in points.indices) {
            if (keepPersisted[i]) points[i] = points[i].copy(zScore = keptZ[i])
        }
    } else {
        val cache = RollingSpreadStatsCache.from(points)
        for (i in points.indices) {
            if (keepPersisted[i]) continue
            val spreadForZ = spreadForRollingZAt(i, points, entities)
            val stats = cache.at(i) ?: continue
            points[i] = points[i].copy(zScore = zScoreAtSpread(spreadForZ, stats))
        }
    }
    return points.map { it.zScore } != beforeZ
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

/**
 * Rolling-Z без снимков SQLite: guard скачков spread (>0.35 pp) на хвосте ряда.
 * Бары до первого недавнего spike — сырой rolling-Z; с spike — демпфированный spread в окне.
 */
internal fun applySpreadGuardZScoresInPlace(points: MutableList<DataPoint>) {
    if (points.isEmpty()) return
    applyZScoresDefaultInPlace(points)
    if (points.size < 2) return
    val latestRecentSpike = (1 until points.size)
        .filter { isRecentM15SpreadRevisionSpike(it, points) }
        .maxOrNull() ?: return
    recomputeZForwardFromRecentSpikes(points, listOf(latestRecentSpike))
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
