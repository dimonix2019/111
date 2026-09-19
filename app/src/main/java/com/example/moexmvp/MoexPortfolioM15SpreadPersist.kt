package com.example.moexmvp

import java.time.LocalDate
import kotlin.math.abs

/** Спрэд % из сохранённых close TATN/TATNP (как при загрузке MOEX). */
internal fun spreadPercentFromEntityLegs(entity: PortfolioM15SpreadEntity): Double? =
    spreadPercentFromPairCloses(entity.tatnClose, entity.tatnpClose)

/**
 * Нужен пересчёт spread% из цен: legacy-нули, NaN или нет цен на ногах.
 * Не трогаем валидный spread при расхождении с пересмотром MOEX.
 */
internal fun entitySpreadNeedsBackfillFromLegs(entity: PortfolioM15SpreadEntity): Boolean {
    if (entity.tatnpClose == 0.0 || entity.tatnClose == 0.0) return false
    if (spreadPercentFromEntityLegs(entity) == null) return false
    return entity.spreadPercent == 0.0 || entity.spreadPercent.isNaN()
}

/** Дозаполнить spread%/diff из TATN/TATNP, если в SQLite пусто (parity Z: rolling при null). */
internal fun fillM15SpreadFromLegClosesInPlace(
    points: MutableList<DataPoint>,
    entities: List<PortfolioM15SpreadEntity>,
): Boolean {
    if (entities.size != points.size || points.isEmpty()) return false
    var changed = false
    for (i in points.indices) {
        if (!entitySpreadNeedsBackfillFromLegs(entities[i])) continue
        val spread = spreadPercentFromEntityLegs(entities[i]) ?: continue
        val diff = entities[i].tatnClose - entities[i].tatnpClose
        if (points[i].spreadPercent != spread || points[i].diff != diff) {
            points[i] = points[i].copy(spreadPercent = spread, diff = diff)
            changed = true
        }
    }
    return changed
}

internal suspend fun persistM15SpreadFromLegSnapshots(
    dao: PortfolioM15Dao,
    entities: List<PortfolioM15SpreadEntity>,
    points: List<DataPoint>,
) {
    if (entities.isEmpty() || entities.size != points.size) return
    val updated = entities.indices.mapNotNull { i ->
        if (!entitySpreadNeedsBackfillFromLegs(entities[i])) return@mapNotNull null
        val spread = spreadPercentFromEntityLegs(entities[i]) ?: return@mapNotNull null
        entities[i].copy(
            spreadPercent = spread,
            diff = entities[i].tatnClose - entities[i].tatnpClose,
        )
    }
    if (updated.isNotEmpty()) {
        mergePortfolioM15InsertPreservingSnapshots(dao, updated)
    }
}

internal data class SpreadDeltaBarSnapshot(
    val deltaPp: Double,
    val dayOpenPercent: Double,
)

/** Δ спреда от открытия дня (07:30) для каждого бара. */
internal fun spreadDeltaSnapshotsForSeries(points: List<DataPoint>): List<SpreadDeltaBarSnapshot>? {
    if (points.isEmpty()) return null
    val openByDay = linkedMapOf<LocalDate, Double>()
    points.forEach { pt ->
        val day = m15LabelCalendarDate(pt.tradeDate) ?: return@forEach
        if (!openByDay.containsKey(day)) {
            openByDay[day] = spreadPercentAtTradingDayOpen(points, day) ?: pt.spreadPercent
        }
    }
    if (openByDay.isEmpty()) return null
    return points.map { pt ->
        val day = m15LabelCalendarDate(pt.tradeDate)
        val base = day?.let { openByDay[it] } ?: pt.spreadPercent
        SpreadDeltaBarSnapshot(
            deltaPp = pt.spreadPercent - base,
            dayOpenPercent = base,
        )
    }
}

/**
 * Δ спреда: снимки из SQLite или пересчёт из spread% (после backfill из TATN/TATNP).
 * Возвращает true, если хотя бы один бар пересчитан (не из persisted).
 */
internal fun fillM15SpreadDeltaSnapshotsInPlace(
    points: List<DataPoint>,
    entities: List<PortfolioM15SpreadEntity>,
): Boolean {
    if (entities.size != points.size || points.isEmpty()) return false
    if (spreadDeltaSnapshotsForSeries(points) == null) return false
    for (i in points.indices) {
        if (isM15LiveZTailIndex(points, i)) return true
        val entity = entities[i]
        if (entity.persistedSpreadDeltaPp == null || entity.spreadDeltaDayOpenPercent == null) {
            return true
        }
    }
    return false
}

internal suspend fun persistM15SpreadDeltaSnapshots(
    dao: PortfolioM15Dao,
    entities: List<PortfolioM15SpreadEntity>,
    points: List<DataPoint>,
) {
    if (entities.isEmpty() || entities.size != points.size) return
    val computed = spreadDeltaSnapshotsForSeries(points) ?: return
    val firstStale = entities.indexOfFirst {
        it.persistedSpreadDeltaPp == null || it.spreadDeltaDayOpenPercent == null
    }
    if (firstStale < 0) return
    val updated = (firstStale until entities.size).mapNotNull { i ->
        if (isM15LiveZTailIndex(points, i)) return@mapNotNull null
        val entity = entities[i]
        if (entity.persistedSpreadDeltaPp != null && entity.spreadDeltaDayOpenPercent != null) {
            return@mapNotNull null
        }
        val snap = computed[i]
        entity.copy(
            persistedSpreadDeltaPp = snap.deltaPp,
            spreadDeltaDayOpenPercent = snap.dayOpenPercent,
        )
    }
    if (updated.isNotEmpty()) {
        mergePortfolioM15InsertPreservingSnapshots(dao, updated)
    }
}

/** Единая точка: spread из ног + Δ спреда + запись в SQLite (как Z после load). */
internal suspend fun fillAndPersistM15SpreadAndDelta(
    dao: PortfolioM15Dao,
    entities: List<PortfolioM15SpreadEntity>,
    points: MutableList<DataPoint>,
) {
    fillM15SpreadFromLegClosesInPlace(points, entities)
    persistM15SpreadFromLegSnapshots(dao, entities, points)
    fillM15SpreadDeltaSnapshotsInPlace(points, entities)
    persistM15SpreadDeltaSnapshots(dao, entities, points)
}

/**
 * Δ спреда для окна графика: day-open берётся из полного rolling-base (255д),
 * как [recalcM15ZForChartDisplayWindow] для Z-score.
 */
internal fun spreadDeltaFromDayOpenSeriesForDisplayWindow(
    window: List<DataPoint>,
    rollingBase: List<DataPoint>,
): SpreadDeltaSeries? {
    if (window.isEmpty()) return null
    val base = rollingBase.ifEmpty { window }
    val full = spreadDeltaFromDayOpenSeries(base) ?: return null
    val deltaByLabel = full.labels.zip(full.deltasPp).toMap()
    val windowLabels = window.map { it.tradeDate }
    val lastDay = window.lastOrNull()?.let { m15LabelCalendarDate(it.tradeDate) }
    val openByDay = linkedMapOf<LocalDate, Double>()
    base.forEach { pt ->
        val day = m15LabelCalendarDate(pt.tradeDate) ?: return@forEach
        if (!openByDay.containsKey(day)) {
            openByDay[day] = spreadPercentAtTradingDayOpen(base, day) ?: pt.spreadPercent
        }
    }
    return SpreadDeltaSeries(
        labels = windowLabels,
        deltasPp = windowLabels.map { label -> deltaByLabel[label] ?: 0.0 },
        dayOpenSpreadPercent = lastDay?.let { openByDay[it] },
    )
}

/** Проверка: persisted Δ совпадает с пересчётом (для тестов). */
internal fun spreadDeltaPersistedMatchesComputed(
    entity: PortfolioM15SpreadEntity,
    computed: SpreadDeltaBarSnapshot,
    tolerancePp: Double = 1e-6,
): Boolean {
    val delta = entity.persistedSpreadDeltaPp ?: return false
    val dayOpen = entity.spreadDeltaDayOpenPercent ?: return false
    return abs(delta - computed.deltaPp) <= tolerancePp &&
        abs(dayOpen - computed.dayOpenPercent) <= tolerancePp
}
