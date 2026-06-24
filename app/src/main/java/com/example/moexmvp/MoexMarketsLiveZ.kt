package com.example.moexmvp

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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

/**
 * Replay сегодняшних 15м баров из 1м TATN/TATNP (rolling Z на истории до сегодня).
 * Закрывает дыры MOEX 15м-кэша на Z-графике «Рынок».
 */
internal fun replayTodayM15FromIntraday1m(
    m15Points: List<DataPoint>,
    aligned: AlignedIntraday1mQuotes,
    zone: ZoneId = moexZoneId,
): List<DataPoint>? {
    if (aligned.labels.isEmpty() || m15Points.isEmpty()) return null
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    var base = m15Points.filter { it.timestampMillis < todayStart }
    if (base.size < Z_SCORE_ROLLING_MIN_BARS) {
        if (m15Points.size < Z_SCORE_ROLLING_MIN_BARS) return null
        base = m15Points.dropLastWhile { it.timestampMillis >= todayStart }
    }
    if (base.size < Z_SCORE_ROLLING_MIN_BARS) return null
    val mutable = base.toMutableList()
    aligned.labels.indices.forEach { i ->
        val spread = spreadPercentFromPairCloses(aligned.tatnCloses[i], aligned.tatnpCloses[i])
            ?: return@forEach
        val bucketMs = intraday1mLabelToM15BucketMs(aligned.labels[i], zone)
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
        mutable[mutable.lastIndex] = mutable.last().copy(zScore = z)
    }
    val todayBars = mutable.count { it.timestampMillis >= todayStart }
    if (todayBars == 0) return null
    return mutable
}

/** Live Z из 1м TATN/TATNP поверх кэшированного 15м ряда (сводка, шторка, монитор). */
internal fun liveZScoreFromIntraday1m(
    m15Points: List<DataPoint>,
    snap: MarketsIntraday1mSnapshot,
): Double? {
    val tatnClose = snap.tatn.lastOrNull()?.close ?: return null
    val tatnpClose = snap.tatnp.lastOrNull()?.close ?: return null
    return buildM15PointsWithLiveFormingFrom1m(m15Points, tatnClose, tatnpClose)?.last()?.zScore
}

internal data class MarketsLiveZPublishResult(
    val zScore: Double,
    val barAt: String,
    /** Ненулевой, если Z пересчитан из 1м (parity с шторкой). */
    val patchedPoints: List<DataPoint>?,
)

/**
 * Единый расчёт live Z: при наличии 1м — patch формирующего 15м бара (как в шторке/мониторе).
 */
internal fun resolveMarketsLiveZFromPoints(
    points: List<DataPoint>,
    snap: MarketsIntraday1mSnapshot?,
): MarketsLiveZPublishResult? {
    if (points.isEmpty()) return null
    val patched = snap?.let { s ->
        val tatnClose = s.tatn.lastOrNull()?.close ?: return@let null
        val tatnpClose = s.tatnp.lastOrNull()?.close ?: return@let null
        buildM15PointsWithLiveFormingFrom1m(points, tatnClose, tatnpClose)
    }
    val series = patched ?: points
    val last = series.lastOrNull() ?: return null
    return MarketsLiveZPublishResult(
        zScore = last.zScore,
        barAt = last.tradeDate,
        patchedPoints = patched,
    )
}

internal fun MoexScreenState.cachedMarketsIntraday1mSnapshot(): MarketsIntraday1mSnapshot? {
    if (marketsIntraday1mTatn.isEmpty() || marketsIntraday1mTatnp.isEmpty()) return null
    return MarketsIntraday1mSnapshot(
        tatn = marketsIntraday1mTatn,
        tatnp = marketsIntraday1mTatnp,
        tatnLastBarMillis = marketsIntraday1mLastBarMillis,
        tatnpLastBarMillis = marketsIntraday1mLastBarMillis,
        fetchedAtMillis = marketsIntraday1mFetchedAtMillis,
    )
}

/** Обновить сводный live Z на «Рынок»; 1м overlay имеет приоритет над MOEX 15m Z. */
internal suspend fun MoexScreenState.publishMarketsLiveZFromPoints(
    points: List<DataPoint>,
    snap: MarketsIntraday1mSnapshot? = cachedMarketsIntraday1mSnapshot(),
) {
    val zBase = resolveMarketsM15PointsForLiveZ(points)
    val result = resolveMarketsLiveZFromPoints(zBase, snap) ?: return
    marketsLiveZScore = result.zScore
    marketsLiveZBarAt = result.barAt
    // Live Z / формирующий бар — только overlay на графике (applyLiveZToM15ChartSeries).
    // Не пишем patchedPoints в session cache: иначе live Z «замораживается» на закрытых барах.
}

/** 15м ряд с актуальным формирующимся баром из 1м (для фонового монитора). */
internal fun m15PointsWithLiveFormingFromIntraday1m(
    m15Points: List<DataPoint>,
    snap: MarketsIntraday1mSnapshot,
): List<DataPoint> {
    val tatnClose = snap.tatn.lastOrNull()?.close ?: return m15Points
    val tatnpClose = snap.tatnp.lastOrNull()?.close ?: return m15Points
    return buildM15PointsWithLiveFormingFrom1m(m15Points, tatnClose, tatnpClose) ?: m15Points
}

/** Применить live Z из 1м котировок к in-memory 15м (сводка + Z-график). */
internal suspend fun MoexScreenState.applyMarketsLiveZFromIntraday1mSnap(snap: MarketsIntraday1mSnapshot) {
    publishMarketsLiveZFromPoints(marketsM15Source(), snap = snap)
}
