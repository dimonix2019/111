package com.example.moexmvp

import java.time.Instant
import java.time.ZoneId

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

/** Применить live Z из 1м котировок к in-memory 15м (сводка + Z-график). */
internal fun MoexScreenState.applyMarketsLiveZFromIntraday1mSnap(snap: MarketsIntraday1mSnapshot) {
    val tatnClose = snap.tatn.lastOrNull()?.close ?: return
    val tatnpClose = snap.tatnp.lastOrNull()?.close ?: return
    val src = marketsM15Source()
    val patched = buildM15PointsWithLiveFormingFrom1m(src, tatnClose, tatnpClose) ?: return
    val last = patched.last()
    val prev = marketsM15Source().lastOrNull()
    marketsLiveZScore = last.zScore
    marketsLiveZBarAt = last.tradeDate
    val structureChanged = prev == null ||
        prev.timestampMillis != last.timestampMillis ||
        kotlin.math.abs(prev.spreadPercent - last.spreadPercent) > 0.005
    if (structureChanged) {
        marketsM15SessionCache = patched
        marketsM15DataEpoch++
        bumpMarketsLoadedAtFromM15(patched)
    }
}
