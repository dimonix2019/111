package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal fun spreadPercentFromPairCloses(tatnClose: Double, tatnpClose: Double): Double? {
    if (tatnpClose == 0.0) return null
    return (tatnClose / tatnpClose - 1.0) * 100.0
}

/** Подпись текущего 15м слота (МСК). */
internal fun currentM15BucketTradeDate(zone: ZoneId = moexZoneId): String =
    Instant.ofEpochMilli(currentM15BucketStartMillis())
        .atZone(zone)
        .format(portfolio15mLabelFormatter)

/**
 * Обновляет формирующийся 15м бар по последним 1м close TATN/TATNP и пересчитывает rolling-Z.
 * Без сетевого MOEX 15м — синхронно с минутными графиками.
 */
internal fun buildM15PointsWithLiveFormingFrom1m(
    m15Points: List<DataPoint>,
    tatnClose: Double,
    tatnpClose: Double,
    bucketMs: Long = currentM15BucketStartMillis(),
    zone: ZoneId = moexZoneId,
): List<DataPoint>? {
    if (m15Points.size < Z_SCORE_ROLLING_MIN_BARS) return null
    val spread = spreadPercentFromPairCloses(tatnClose, tatnpClose) ?: return null
    val mutable = m15Points.toMutableList()
    if (!patchFormingM15BarInPlace(mutable, bucketMs, tatnClose, tatnpClose, spread, zone)) return null
    val liveZ = rollingZAtBarIndex(mutable, mutable.lastIndex) ?: return null
    mutable[mutable.lastIndex] = mutable.last().copy(zScore = liveZ)
    return mutable
}

internal fun patchFormingM15BarInPlace(
    points: MutableList<DataPoint>,
    bucketMs: Long,
    tatnClose: Double,
    tatnpClose: Double,
    spreadPercent: Double,
    zone: ZoneId = moexZoneId,
): Boolean {
    if (points.isEmpty()) return false
    val label = Instant.ofEpochMilli(bucketMs).atZone(zone).format(portfolio15mLabelFormatter)
    val last = points.last()
    val patch = { idx: Int ->
        points[idx] = points[idx].copy(
            timestampMillis = bucketMs,
            tradeDate = label,
            tatnClose = tatnClose,
            tatnpClose = tatnpClose,
            spreadPercent = spreadPercent,
            diff = tatnClose - tatnpClose,
            zScore = 0.0,
        )
    }
    when {
        last.timestampMillis >= bucketMs -> {
            patch(points.lastIndex)
            return true
        }
        else -> {
            points.add(
                DataPoint(
                    timestampMillis = bucketMs,
                    tradeDate = label,
                    tatnClose = tatnClose,
                    tatnpClose = tatnpClose,
                    spreadPercent = spreadPercent,
                    diff = tatnClose - tatnpClose,
                    zScore = 0.0,
                ),
            )
            return true
        }
    }
}

internal fun rollingZAtBarIndex(points: List<DataPoint>, index: Int): Double? {
    if (index !in points.indices) return null
    val stats = RollingSpreadStatsCache.from(points).at(index) ?: return null
    return zScoreAtSpread(points[index].spreadPercent, stats)
}

/** Метка 1м (HH:mm, сегодня МСК) → начало 15м слота в epoch ms. */
internal fun intraday1mLabelToM15BucketMs(label: String, zone: ZoneId = moexZoneId): Long {
    val today = LocalDate.now(zone)
    val time = LocalTime.parse(label.trim(), intradayLabelFormatter)
    val bucketMinute = (time.minute / 15) * 15
    return today.atTime(time.hour, bucketMinute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}

internal data class Intraday1mZSeries(
    val labels: List<String>,
    val zScores: List<Double>,
)

/**
 * Z-score по минуткам за сегодня: rolling-Z на базе 15м истории + replay 1м spread.
 */
internal fun buildIntraday1mZScoreSeries(
    m15Points: List<DataPoint>,
    aligned: AlignedIntraday1mQuotes,
    zone: ZoneId = moexZoneId,
): Intraday1mZSeries? {
    if (aligned.labels.isEmpty()) return null
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    var base = m15Points.filter { it.timestampMillis < todayStart }
    if (base.size < Z_SCORE_ROLLING_MIN_BARS) {
        if (m15Points.size < Z_SCORE_ROLLING_MIN_BARS) return null
        base = m15Points.dropLastWhile { it.timestampMillis >= todayStart }
    }
    if (base.size < Z_SCORE_ROLLING_MIN_BARS) return null
    val mutable = base.toMutableList()
    val labels = mutableListOf<String>()
    val zScores = mutableListOf<Double>()
    aligned.labels.indices.forEach { i ->
        val label = aligned.labels[i]
        val spread = spreadPercentFromPairCloses(aligned.tatnCloses[i], aligned.tatnpCloses[i]) ?: return@forEach
        val bucketMs = intraday1mLabelToM15BucketMs(label, zone)
        if (!patchFormingM15BarInPlace(
                mutable,
                bucketMs,
                aligned.tatnCloses[i],
                aligned.tatnpCloses[i],
                spread,
                zone,
            )
        ) {
            return@forEach
        }
        val z = rollingZAtBarIndex(mutable, mutable.lastIndex) ?: return@forEach
        labels += label
        zScores += z
    }
    if (labels.isEmpty()) return null
    return Intraday1mZSeries(labels, zScores)
}

/** Единый снимок Z/спреда для сводки «Рынок» и шторки монитора. */
internal data class UnifiedLiveZSnapshot(
    val zScore: Double?,
    val barAt: String?,
    val spreadPercent: Double?,
    val monitorPoints: List<DataPoint>,
    val liveFrom1m: Boolean,
)

/**
 * Единый расчёт Z/спреда/15м для сводки и шторки.
 * До 07:00 МСК — последний закрытый 15м бар (без fake live 1м).
 */
internal fun resolveUnifiedLiveZSnapshot(
    m15Points: List<DataPoint>,
    intraday1m: MarketsIntraday1mSnapshot? = null,
    now: ZonedDateTime = ZonedDateTime.now(moexZoneId),
): UnifiedLiveZSnapshot {
    if (m15Points.isEmpty()) {
        return UnifiedLiveZSnapshot(null, null, null, m15Points, liveFrom1m = false)
    }
    if (intraday1m != null && shouldApplyMarketsLiveZFromIntraday1m(intraday1m, now)) {
        val tatnClose = intraday1m.tatn.lastOrNull()?.close
        val tatnpClose = intraday1m.tatnp.lastOrNull()?.close
        if (tatnClose != null && tatnpClose != null) {
            val bucketMs = maxOf(intraday1m.tatnLastBarMillis, intraday1m.tatnpLastBarMillis)
                .takeIf { it > 0L }
                ?: currentM15BucketStartMillis(now.toInstant(), moexZoneId)
            val patched = buildM15PointsWithLiveFormingFrom1m(
                m15Points,
                tatnClose,
                tatnpClose,
                bucketMs = bucketMs,
                zone = moexZoneId,
            )
            if (patched != null) {
                val last = patched.last()
                return UnifiedLiveZSnapshot(
                    zScore = last.zScore,
                    barAt = last.tradeDate,
                    spreadPercent = last.spreadPercent,
                    monitorPoints = patched,
                    liveFrom1m = true,
                )
            }
        }
    }
    val closed = lastClosedM15BarForDisplay(m15Points, now)
    return UnifiedLiveZSnapshot(
        zScore = rollingZForClosedM15Bar(m15Points, now),
        barAt = closed?.tradeDate,
        spreadPercent = closed?.spreadPercent ?: m15Points.last().spreadPercent,
        monitorPoints = m15Points,
        liveFrom1m = false,
    )
}

internal fun liveZScoreFromIntraday1m(
    m15Points: List<DataPoint>,
    snap: MarketsIntraday1mSnapshot,
): Double? = resolveUnifiedLiveZSnapshot(m15Points, snap).zScore

internal fun m15PointsWithLiveFormingFromIntraday1m(
    m15Points: List<DataPoint>,
    snap: MarketsIntraday1mSnapshot,
): List<DataPoint> = resolveUnifiedLiveZSnapshot(m15Points, snap).monitorPoints

internal fun MoexScreenState.applyMarketsLiveZFromIntraday1mSnap(snap: MarketsIntraday1mSnapshot) {
    val src = marketsM15Source()
    val unified = resolveUnifiedLiveZSnapshot(src, snap)
    marketsLiveZScore = unified.zScore
    marketsLiveZBarAt = unified.barAt
    marketsLiveSpreadPercent = unified.spreadPercent
    if (!unified.liveFrom1m) return
    val last = unified.monitorPoints.lastOrNull() ?: return
    val prev = src.lastOrNull()
    val structureChanged = prev == null ||
        prev.timestampMillis != last.timestampMillis ||
        kotlin.math.abs(prev.spreadPercent - last.spreadPercent) > 0.005
    if (structureChanged) {
        marketsM15SessionCache = unified.monitorPoints
        marketsM15DataEpoch++
        bumpMarketsLoadedAtFromM15(unified.monitorPoints)
    }
}
