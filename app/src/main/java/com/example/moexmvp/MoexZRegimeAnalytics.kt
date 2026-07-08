package com.example.moexmvp

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * Режим концентрации Z на 15м барах:
 * - [NegativeBand] — Z преимущественно в полосе [−2, 0]
 * - [PositiveBand] — Z преимущественно в полосе [0, +2]
 * - [Neutral] — узко около нуля
 * - [Dispersed] — широкий размах, нет явной полосы
 */
internal enum class ZConcentrationRegime {
    NegativeBand,
    PositiveBand,
    Neutral,
    Dispersed,
}

internal data class ZSignedBandShares(
    val negativeBandShare: Double,
    val positiveBandShare: Double,
    val belowNegative2Share: Double,
    val abovePositive2Share: Double,
    val neutralTightShare: Double,
)

internal data class ZRegimeWindowStats(
    val windowId: String,
    val startLabel: String,
    val endLabel: String,
    val barCount: Int,
    val meanZ: Double,
    val medianZ: Double,
    val meanAbsZ: Double,
    val bandShares: ZSignedBandShares,
    val regime: ZConcentrationRegime,
)

internal data class ZRegimeTransitionCell(
    val from: ZConcentrationRegime,
    val to: ZConcentrationRegime,
    val count: Int,
    val probability: Double,
)

internal data class ZRegimeTransitionMatrix(
    val cells: List<ZRegimeTransitionCell>,
    val byFrom: Map<ZConcentrationRegime, Map<ZConcentrationRegime, Double>>,
)

internal data class ZRegimeForecastResult(
    val predicted: ZConcentrationRegime,
    val confidence: Double,
    val method: String,
)

internal data class ZRegimeWalkForwardFold(
    val trainEndWindowId: String,
    val actual: ZConcentrationRegime,
    val predicted: ZConcentrationRegime,
    val correct: Boolean,
    val confidence: Double,
)

internal data class ZRegimeWalkForwardReport(
    val folds: List<ZRegimeWalkForwardFold>,
    val accuracy: Double,
    val accuracyByRegime: Map<ZConcentrationRegime, Double>,
)

internal data class ZRegimeAnalysisReport(
    val windows: List<ZRegimeWindowStats>,
    val monthlyWindows: List<ZRegimeWindowStats>,
    val transitions: ZRegimeTransitionMatrix,
    val walkForward: ZRegimeWalkForwardReport,
    val currentRegime: ZRegimeWindowStats?,
    val nextRegimeForecast: ZRegimeForecastResult?,
)

internal const val Z_REGIME_NEGATIVE_BAND_MIN = -2.0
internal const val Z_REGIME_POSITIVE_BAND_MAX = 2.0
internal const val Z_REGIME_BAND_DOMINANCE_MIN_SHARE = 0.52
internal const val Z_REGIME_MEAN_Z_BIAS_MIN = 0.12
internal const val Z_REGIME_NEUTRAL_MAX_MEAN_ABS_Z = 0.55
internal const val Z_REGIME_NEUTRAL_MAX_ABS_MEAN_Z = 0.18

internal fun zSignedBandShares(zs: List<Double>): ZSignedBandShares {
    if (zs.isEmpty()) {
        return ZSignedBandShares(0.0, 0.0, 0.0, 0.0, 0.0)
    }
    val n = zs.size.toDouble()
    return ZSignedBandShares(
        negativeBandShare = zs.count { it >= Z_REGIME_NEGATIVE_BAND_MIN && it < 0.0 } / n,
        positiveBandShare = zs.count { it >= 0.0 && it <= Z_REGIME_POSITIVE_BAND_MAX } / n,
        belowNegative2Share = zs.count { it < Z_REGIME_NEGATIVE_BAND_MIN } / n,
        abovePositive2Share = zs.count { it > Z_REGIME_POSITIVE_BAND_MAX } / n,
        neutralTightShare = zs.count { abs(it) < 0.5 } / n,
    )
}

internal fun classifyZConcentrationRegime(
    zs: List<Double>,
    meanZ: Double = zs.average(),
    medianZ: Double = zs.sorted().let { it[it.size / 2] },
    meanAbsZ: Double = zs.map { abs(it) }.average(),
    bandShares: ZSignedBandShares = zSignedBandShares(zs),
): ZConcentrationRegime {
    if (zs.isEmpty()) return ZConcentrationRegime.Dispersed
    val negDom =
        bandShares.negativeBandShare >= Z_REGIME_BAND_DOMINANCE_MIN_SHARE &&
            meanZ <= -Z_REGIME_MEAN_Z_BIAS_MIN
    val posDom =
        bandShares.positiveBandShare >= Z_REGIME_BAND_DOMINANCE_MIN_SHARE &&
            meanZ >= Z_REGIME_MEAN_Z_BIAS_MIN
    return when {
        negDom && !posDom -> ZConcentrationRegime.NegativeBand
        posDom && !negDom -> ZConcentrationRegime.PositiveBand
        meanAbsZ <= Z_REGIME_NEUTRAL_MAX_MEAN_ABS_Z &&
            abs(meanZ) <= Z_REGIME_NEUTRAL_MAX_ABS_MEAN_Z -> ZConcentrationRegime.Neutral
        else -> ZConcentrationRegime.Dispersed
    }
}

internal fun buildZRegimeWindows(
    points: List<DataPoint>,
    windowTradingDays: Int,
    zoneId: ZoneId = moexMskZone,
): List<ZRegimeWindowStats> {
    if (points.isEmpty() || windowTradingDays < 1) return emptyList()
    val byDay = linkedMapOf<LocalDate, MutableList<DataPoint>>()
    for (p in points) {
        val day = parseBarLocalDateMsk(p.tradeDate) ?: continue
        byDay.getOrPut(day) { mutableListOf() }.add(p)
    }
    val days = byDay.keys.sorted()
    if (days.isEmpty()) return emptyList()
    val out = mutableListOf<ZRegimeWindowStats>()
    var idx = 0
    var win = 0
    while (idx < days.size) {
        val chunk = days.subList(idx, minOf(idx + windowTradingDays, days.size))
        if (chunk.isEmpty()) break
        val chunkPoints = chunk.flatMap { byDay[it].orEmpty() }
        if (chunkPoints.isNotEmpty()) {
            val zs = chunkPoints.map { it.zScore }
            val shares = zSignedBandShares(zs)
            val meanZ = zs.average()
            val sorted = zs.sorted()
            val medianZ = sorted[sorted.size / 2]
            val meanAbsZ = zs.map { abs(it) }.average()
            out += ZRegimeWindowStats(
                windowId = "W${++win}",
                startLabel = chunk.first().toString(),
                endLabel = chunk.last().toString(),
                barCount = chunkPoints.size,
                meanZ = meanZ,
                medianZ = medianZ,
                meanAbsZ = meanAbsZ,
                bandShares = shares,
                regime = classifyZConcentrationRegime(zs, meanZ, medianZ, meanAbsZ, shares),
            )
        }
        idx += windowTradingDays
    }
    return out
}

internal fun buildZRegimeMonthlyWindows(
    points: List<DataPoint>,
    zoneId: ZoneId = moexMskZone,
): List<ZRegimeWindowStats> {
    if (points.isEmpty()) return emptyList()
    val byMonth = linkedMapOf<String, MutableList<DataPoint>>()
    for (p in points) {
        val day = parseBarLocalDateMsk(p.tradeDate) ?: continue
        val key = String.format(Locale.US, "%04d-%02d", day.year, day.monthValue)
        byMonth.getOrPut(key) { mutableListOf() }.add(p)
    }
    return byMonth.entries.sortedBy { it.key }.map { (month, chunkPoints) ->
        val zs = chunkPoints.map { it.zScore }
        val shares = zSignedBandShares(zs)
        val meanZ = zs.average()
        val sorted = zs.sorted()
        val medianZ = sorted[sorted.size / 2]
        val meanAbsZ = zs.map { abs(it) }.average()
        ZRegimeWindowStats(
            windowId = month,
            startLabel = chunkPoints.first().tradeDate,
            endLabel = chunkPoints.last().tradeDate,
            barCount = chunkPoints.size,
            meanZ = meanZ,
            medianZ = medianZ,
            meanAbsZ = meanAbsZ,
            bandShares = shares,
            regime = classifyZConcentrationRegime(zs, meanZ, medianZ, meanAbsZ, shares),
        )
    }
}

internal fun buildZRegimeTransitionMatrix(
    windows: List<ZRegimeWindowStats>,
): ZRegimeTransitionMatrix {
    if (windows.size < 2) {
        return ZRegimeTransitionMatrix(emptyList(), emptyMap())
    }
    val counts = mutableMapOf<Pair<ZConcentrationRegime, ZConcentrationRegime>, Int>()
    val fromTotals = mutableMapOf<ZConcentrationRegime, Int>()
    for (i in 1 until windows.size) {
        val from = windows[i - 1].regime
        val to = windows[i].regime
        val key = from to to
        counts[key] = (counts[key] ?: 0) + 1
        fromTotals[from] = (fromTotals[from] ?: 0) + 1
    }
    val cells = counts.map { (pair, cnt) ->
        val (from, to) = pair
        val total = fromTotals[from] ?: 1
        ZRegimeTransitionCell(from, to, cnt, cnt.toDouble() / total)
    }.sortedWith(compareBy({ it.from.name }, { -it.probability }))
    val byFrom = ZConcentrationRegime.entries.associateWith { from ->
        val total = fromTotals[from] ?: 0
        if (total == 0) {
            emptyMap()
        } else {
            ZConcentrationRegime.entries.associateWith { to ->
                (counts[from to to] ?: 0).toDouble() / total
            }
        }
    }
    return ZRegimeTransitionMatrix(cells, byFrom)
}

internal fun forecastNextZRegimeFromTransitions(
    current: ZConcentrationRegime,
    matrix: ZRegimeTransitionMatrix,
): ZRegimeForecastResult? {
    val row = matrix.byFrom[current] ?: return null
    if (row.values.all { it == 0.0 }) return null
    val best = row.maxByOrNull { it.value } ?: return null
    return ZRegimeForecastResult(
        predicted = best.key,
        confidence = best.value,
        method = "markov_1step_from_$current",
    )
}

/**
 * Walk-forward: на каждом шаге матрица переходов строится только по прошлым окнам,
 * прогноз — режим следующего окна по текущему.
 */
internal fun walkForwardZRegimeForecast(
    windows: List<ZRegimeWindowStats>,
    minTrainWindows: Int = 6,
): ZRegimeWalkForwardReport {
    if (windows.size <= minTrainWindows) {
        return ZRegimeWalkForwardReport(emptyList(), 0.0, emptyMap())
    }
    val folds = mutableListOf<ZRegimeWalkForwardFold>()
    for (i in minTrainWindows until windows.size) {
        val train = windows.subList(0, i)
        val actual = windows[i].regime
        val prev = windows[i - 1].regime
        val matrix = buildZRegimeTransitionMatrix(train)
        val forecast = forecastNextZRegimeFromTransitions(prev, matrix)
        if (forecast != null) {
            folds += ZRegimeWalkForwardFold(
                trainEndWindowId = train.last().windowId,
                actual = actual,
                predicted = forecast.predicted,
                correct = forecast.predicted == actual,
                confidence = forecast.confidence,
            )
        }
    }
    val accuracy = if (folds.isEmpty()) 0.0 else folds.count { it.correct }.toDouble() / folds.size
    val byRegime = folds.groupBy { it.actual }.mapValues { (_, group) ->
        group.count { it.correct }.toDouble() / group.size
    }
    return ZRegimeWalkForwardReport(folds, accuracy, byRegime)
}

internal fun buildZRegimeAnalysisReport(
    points: List<DataPoint>,
    windowTradingDays: Int = 10,
    walkForwardMinTrain: Int = 6,
): ZRegimeAnalysisReport? {
    if (points.size < 40) return null
    val windows = buildZRegimeWindows(points, windowTradingDays)
    val monthly = buildZRegimeMonthlyWindows(points)
    val transitions = buildZRegimeTransitionMatrix(windows)
    val walkForward = walkForwardZRegimeForecast(windows, walkForwardMinTrain)
    val current = windows.lastOrNull()
    val forecast = current?.let { c ->
        val train = windows.dropLast(1)
        if (train.size < 2) null
        else forecastNextZRegimeFromTransitions(c.regime, buildZRegimeTransitionMatrix(train))
    }
    return ZRegimeAnalysisReport(
        windows = windows,
        monthlyWindows = monthly,
        transitions = transitions,
        walkForward = walkForward,
        currentRegime = current,
        nextRegimeForecast = forecast,
    )
}

internal fun zRegimeLabelRu(regime: ZConcentrationRegime): String = when (regime) {
    ZConcentrationRegime.NegativeBand -> "минус [−2,0]"
    ZConcentrationRegime.PositiveBand -> "плюс [0,+2]"
    ZConcentrationRegime.Neutral -> "нейтраль"
    ZConcentrationRegime.Dispersed -> "разброс"
}

internal fun formatZRegimeAnalysisReport(
    report: ZRegimeAnalysisReport,
    windowTradingDays: Int,
): String = buildString {
    appendLine("=== Режимы концентрации Z (TATN/TATNP 15м) ===")
    appendLine("Окно: $windowTradingDays торг. дней · полосы [−2,0] и [0,+2]")
    appendLine()
    appendLine("— Помесячно —")
    appendLine(String.format(Locale.US, "%-8s %6s %7s %6s %5s %5s %5s %s", "месяц", "meanZ", "medZ", "|Z|", "[-2,0]", "[0,+2]", "баров", "режим"))
    for (w in report.monthlyWindows) {
        appendLine(formatZRegimeWindowRow(w))
    }
    appendLine()
    appendLine("— Скользящие окна ($windowTradingDays дн.) —")
    appendLine(String.format(Locale.US, "%-8s %12s %12s %6s %5s %5s %s", "окно", "с", "по", "meanZ", "[-2,0]", "[0,+2]", "режим"))
    for (w in report.windows) {
        appendLine(
            String.format(
                Locale.US,
                "%-8s %12s %12s %6.2f %5.0f%% %5.0f%% %s",
                w.windowId,
                w.startLabel,
                w.endLabel,
                w.meanZ,
                w.bandShares.negativeBandShare * 100.0,
                w.bandShares.positiveBandShare * 100.0,
                zRegimeLabelRu(w.regime),
            )
        )
    }
    appendLine()
    appendLine("— Матрица переходов (10д окна) —")
    for (cell in report.transitions.cells.filter { it.count > 0 }.take(20)) {
        appendLine(
            String.format(
                Locale.US,
                "  %s → %s: %d (P=%.0f%%)",
                zRegimeLabelRu(cell.from),
                zRegimeLabelRu(cell.to),
                cell.count,
                cell.probability * 100.0,
            )
        )
    }
    appendLine()
    appendLine("— Walk-forward прогноз (Markov 1 шаг) —")
    appendLine(
        String.format(
            Locale.US,
            "Точность: %.1f%% (%d/%d окон)",
            report.walkForward.accuracy * 100.0,
            report.walkForward.folds.count { it.correct },
            report.walkForward.folds.size,
        )
    )
    for ((regime, acc) in report.walkForward.accuracyByRegime.entries.sortedByDescending { it.value }) {
        appendLine(String.format(Locale.US, "  если факт %s: %.0f%%", zRegimeLabelRu(regime), acc * 100.0))
    }
    report.walkForward.folds.takeLast(8).forEach { f ->
        appendLine(
            String.format(
                Locale.US,
                "  после %s → прогноз %s, факт %s %s",
                f.trainEndWindowId,
                zRegimeLabelRu(f.predicted),
                zRegimeLabelRu(f.actual),
                if (f.correct) "✓" else "✗",
            )
        )
    }
    report.currentRegime?.let { cur ->
        appendLine()
        appendLine("— Текущее окно —")
        appendLine(
            String.format(
                Locale.US,
                "%s … %s: meanZ=%+.2f, [-2,0]=%.0f%%, [0,+2]=%.0f%% → %s",
                cur.startLabel,
                cur.endLabel,
                cur.meanZ,
                cur.bandShares.negativeBandShare * 100.0,
                cur.bandShares.positiveBandShare * 100.0,
                zRegimeLabelRu(cur.regime),
            )
        )
    }
    report.nextRegimeForecast?.let { fc ->
        appendLine(
            String.format(
                Locale.US,
                "Прогноз следующего окна: %s (уверенность %.0f%%)",
                zRegimeLabelRu(fc.predicted),
                fc.confidence * 100.0,
            )
        )
    }
}

private fun formatZRegimeWindowRow(w: ZRegimeWindowStats): String =
    String.format(
        Locale.US,
        "%-8s %6.2f %7.2f %6.2f %5.0f%% %5.0f%% %5d %s",
        w.windowId,
        w.meanZ,
        w.medianZ,
        w.meanAbsZ,
        w.bandShares.negativeBandShare * 100.0,
        w.bandShares.positiveBandShare * 100.0,
        w.barCount,
        zRegimeLabelRu(w.regime),
    )

/** Длительность режима в календарных днях (для отчёта закономерностей). */
internal fun zRegimeRunLengths(windows: List<ZRegimeWindowStats>): List<Pair<ZConcentrationRegime, Int>> {
    if (windows.isEmpty()) return emptyList()
    val runs = mutableListOf<Pair<ZConcentrationRegime, Int>>()
    var cur = windows.first().regime
    var len = 1
    for (i in 1 until windows.size) {
        if (windows[i].regime == cur) {
            len++
        } else {
            runs += cur to len
            cur = windows[i].regime
            len = 1
        }
    }
    runs += cur to len
    return runs
}

internal fun meanCalendarDaysBetweenWindowStarts(
    windows: List<ZRegimeWindowStats>,
): Double? {
    if (windows.size < 2) return null
    val days = (1 until windows.size).mapNotNull { i ->
        val a = LocalDate.parse(windows[i - 1].startLabel)
        val b = LocalDate.parse(windows[i].startLabel)
        ChronoUnit.DAYS.between(a, b)
    }
    return if (days.isEmpty()) null else days.average()
}
