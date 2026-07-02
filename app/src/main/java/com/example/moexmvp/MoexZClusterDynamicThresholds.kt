package com.example.moexmvp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Динамические 4 порога по «кластеру» Z: rolling-распределение Z за lookback без look-ahead.
 *
 * Идея: когда Z концентрируется в узкой полосе — пороги сжимаются к периферии кластера;
 * при широком размахе — расширяются к хвостам (p85/p15).
 */
internal data class ZClusterThresholdConfig(
    val lookbackBars: Int = 130,
    val entryPctShort: Int = 85,
    val exitPctShort: Int = 55,
    val entryPctLong: Int = 15,
    val exitPctLong: Int = 45,
    val minEntry: Double = 0.25,
    val maxEntry: Double = 2.5,
    val minExit: Double = 0.15,
    val minGap: Double = 0.1,
    val minWindowBars: Int = 48,
    val fallback: ZStrategyFourThresholds = ZStrategyFourThresholds(0.7, 0.5, 0.7, 0.5),
)

/** IQR-вариант: пороги = median(Z) ± mult × IQR/2. */
internal data class ZClusterIqrConfig(
    val lookbackBars: Int = 130,
    val entryIqrMult: Double = 1.2,
    val exitIqrMult: Double = 0.5,
    val minEntry: Double = 0.25,
    val maxEntry: Double = 2.5,
    val minExit: Double = 0.15,
    val minGap: Double = 0.1,
    val minWindowBars: Int = 48,
    val fallback: ZStrategyFourThresholds = ZStrategyFourThresholds(0.7, 0.5, 0.7, 0.5),
)

internal fun percentileOfSorted(sorted: List<Double>, pct: Int): Double {
    if (sorted.isEmpty()) return 0.0
    val p = pct.coerceIn(0, 100) / 100.0
    if (sorted.size == 1) return sorted[0]
    val idx = p * (sorted.size - 1)
    val lo = idx.toInt()
    val hi = min(lo + 1, sorted.lastIndex)
    val w = idx - lo
    return sorted[lo] * (1.0 - w) + sorted[hi] * w
}

internal fun buildClusterDynamicFourThresholdSeries(
    points: List<DataPoint>,
    config: ZClusterThresholdConfig,
): List<ZStrategyFourThresholds> {
    val n = points.size
    if (n == 0) return emptyList()
    return List(n) { i ->
        clusterFourFromWindow(
            windowZs = zWindowBeforeBar(points, i, config.lookbackBars, config.minWindowBars),
            config = config,
        )
    }
}

internal fun buildClusterIqrFourThresholdSeries(
    points: List<DataPoint>,
    config: ZClusterIqrConfig,
): List<ZStrategyFourThresholds> {
    val n = points.size
    if (n == 0) return emptyList()
    return List(n) { i ->
        clusterFourFromIqrWindow(
            windowZs = zWindowBeforeBar(points, i, config.lookbackBars, config.minWindowBars),
            config = config,
        )
    }
}

private fun zWindowBeforeBar(
    points: List<DataPoint>,
    barIndex: Int,
    lookbackBars: Int,
    minWindowBars: Int,
): List<Double>? {
    if (barIndex <= 0) return null
    val start = max(0, barIndex - lookbackBars)
    val window = (start until barIndex).map { points[it].zScore }
    return if (window.size >= minWindowBars) window else null
}

private fun clusterFourFromWindow(
    windowZs: List<Double>?,
    config: ZClusterThresholdConfig,
): ZStrategyFourThresholds {
    if (windowZs == null) return config.fallback
    val sorted = windowZs.sorted()
    val absSorted = windowZs.map { abs(it) }.sorted()

    var entryShort = percentileOfSorted(sorted, config.entryPctShort)
    if (entryShort < config.minEntry) {
        entryShort = percentileOfSorted(absSorted, config.entryPctShort)
    }
    entryShort = entryShort.coerceIn(config.minEntry, config.maxEntry)

    var exitShort = percentileOfSorted(sorted, config.exitPctShort).coerceIn(config.minExit, config.maxEntry)
    if (exitShort >= entryShort - config.minGap) {
        exitShort = (entryShort - config.minGap).coerceAtLeast(config.minExit)
    }

    val pLong = percentileOfSorted(sorted, config.entryPctLong)
    var entryLong = if (pLong < 0.0) {
        (-pLong).coerceIn(config.minEntry, config.maxEntry)
    } else {
        percentileOfSorted(absSorted, 100 - config.entryPctLong).coerceIn(config.minEntry, config.maxEntry)
    }

    val pExitLong = percentileOfSorted(sorted, config.exitPctLong)
    var exitLong = if (pExitLong < 0.0) {
        (-pExitLong).coerceIn(config.minExit, config.maxEntry)
    } else {
        (entryLong - config.minGap).coerceAtLeast(config.minExit)
    }
    if (exitLong >= entryLong - config.minGap) {
        exitLong = (entryLong - config.minGap).coerceAtLeast(config.minExit)
    }

    val four = ZStrategyFourThresholds(entryLong, exitLong, entryShort, exitShort)
    return if (four.isValid()) four else config.fallback
}

private fun clusterFourFromIqrWindow(
    windowZs: List<Double>?,
    config: ZClusterIqrConfig,
): ZStrategyFourThresholds {
    if (windowZs == null) return config.fallback
    val sorted = windowZs.sorted()
    val p25 = percentileOfSorted(sorted, 25)
    val p50 = percentileOfSorted(sorted, 50)
    val p75 = percentileOfSorted(sorted, 75)
    val iqr = (p75 - p25).coerceAtLeast(0.05)
    val halfBand = iqr / 2.0

    val shortEntryZ = p50 + config.entryIqrMult * halfBand
    var entryShort = shortEntryZ.coerceIn(config.minEntry, config.maxEntry)
    val shortExitZ = p50 + config.exitIqrMult * halfBand
    var exitShort = shortExitZ.coerceIn(config.minExit, entryShort - config.minGap)
    if (exitShort >= entryShort - config.minGap) {
        exitShort = (entryShort - config.minGap).coerceAtLeast(config.minExit)
    }

    val longEntryZ = p50 - config.entryIqrMult * halfBand
    var entryLong = (-longEntryZ).coerceIn(config.minEntry, config.maxEntry)
    if (longEntryZ >= 0.0) {
        entryLong = (config.entryIqrMult * halfBand).coerceIn(config.minEntry, config.maxEntry)
    }
    val longExitZ = p50 - config.exitIqrMult * halfBand
    var exitLong = (-longExitZ).coerceIn(config.minExit, entryLong - config.minGap)
    if (exitLong >= entryLong - config.minGap) {
        exitLong = (entryLong - config.minGap).coerceAtLeast(config.minExit)
    }

    val four = ZStrategyFourThresholds(entryLong, exitLong, entryShort, exitShort)
    return if (four.isValid()) four else config.fallback
}

/** Статический вход (wide) + динамический exit из percentile-кластера. */
internal data class ZHybridStaticEntryConfig(
    val staticEntryLong: Double = 1.6,
    val staticEntryShort: Double = 1.6,
    val exitCluster: ZClusterThresholdConfig,
)

/** Regime switch: IQR(Z) < порога → не входим; иначе статические wide пороги. */
internal data class ZRegimeSwitchConfig(
    val lookbackBars: Int = 260,
    val minIqrToTrade: Double = 0.5,
    val minWindowBars: Int = 48,
    val activeFour: ZStrategyFourThresholds = ZStrategyFourThresholds(1.6, 1.1, 1.6, 1.4),
    val quietEntryBlock: Double = 9.0,
)

/** Expansion: торгуем только когда max|Z| за recent >> baseline (фаза расширения). */
internal data class ZExpansionRegimeConfig(
    val recentBars: Int = 130,
    val baselineBars: Int = 520,
    val minWindowBars: Int = 48,
    val expansionRatio: Double = 1.5,
    val activeFour: ZStrategyFourThresholds = ZStrategyFourThresholds(1.6, 1.1, 1.6, 1.4),
    val quietEntryBlock: Double = 9.0,
)

internal fun buildHybridStaticEntryDynamicExitSeries(
    points: List<DataPoint>,
    config: ZHybridStaticEntryConfig,
): List<ZStrategyFourThresholds> {
    val exitSeries = buildClusterDynamicFourThresholdSeries(points, config.exitCluster)
    return exitSeries.map { exit ->
        val four = ZStrategyFourThresholds(
            entryLong = config.staticEntryLong,
            exitLong = exit.exitLong,
            entryShort = config.staticEntryShort,
            exitShort = exit.exitShort,
        )
        if (four.isValid()) four else config.exitCluster.fallback
    }
}

internal fun buildRegimeSwitchFourThresholdSeries(
    points: List<DataPoint>,
    config: ZRegimeSwitchConfig,
): List<ZStrategyFourThresholds> {
    val n = points.size
    if (n == 0) return emptyList()
    val block = config.quietEntryBlock
    return List(n) { i ->
        val window = zWindowBeforeBar(points, i, config.lookbackBars, config.minWindowBars)
        val iqr = window?.let { zIqr(it) }
        val active = iqr != null && iqr >= config.minIqrToTrade
        if (active) {
            config.activeFour
        } else {
            ZStrategyFourThresholds(
                entryLong = block,
                exitLong = config.activeFour.exitLong,
                entryShort = block,
                exitShort = config.activeFour.exitShort,
            )
        }
    }
}

internal fun buildExpansionRegimeFourThresholdSeries(
    points: List<DataPoint>,
    config: ZExpansionRegimeConfig,
): List<ZStrategyFourThresholds> {
    val n = points.size
    if (n == 0) return emptyList()
    val block = config.quietEntryBlock
    return List(n) { i ->
        if (i <= 0) return@List config.activeFour
        val recentStart = max(0, i - config.recentBars)
        val recent = (recentStart until i).map { abs(points[it].zScore) }
        val baselineStart = max(0, i - config.baselineBars)
        val baseline = (baselineStart until recentStart).map { abs(points[it].zScore) }
        val expanding = expansionPhaseActive(recent, baseline, config.minWindowBars, config.expansionRatio)
        if (expanding) {
            config.activeFour
        } else {
            ZStrategyFourThresholds(
                entryLong = block,
                exitLong = config.activeFour.exitLong,
                entryShort = block,
                exitShort = config.activeFour.exitShort,
            )
        }
    }
}

private fun zIqr(windowZs: List<Double>): Double {
    val sorted = windowZs.sorted()
    return percentileOfSorted(sorted, 75) - percentileOfSorted(sorted, 25)
}

private fun expansionPhaseActive(
    recentAbsZ: List<Double>,
    baselineAbsZ: List<Double>,
    minWindowBars: Int,
    expansionRatio: Double,
): Boolean {
    if (recentAbsZ.size < min(minWindowBars / 4, 12)) return false
    if (baselineAbsZ.size < minWindowBars) return false
    val recentMax = recentAbsZ.maxOrNull() ?: return false
    val baselineSorted = baselineAbsZ.sorted()
    val baselineP75 = percentileOfSorted(baselineSorted, 75)
    val baselineMax = baselineSorted.last()
    val ref = max(max(baselineP75, baselineMax * 0.85), 0.4)
    return recentMax >= expansionRatio * ref
}
