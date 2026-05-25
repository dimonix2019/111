package com.example.moexmvp

import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Доля 15м баров, когда |Z| попадает в полосу (сумма ≈ 100%). */
internal data class ZAbsZoneShare(
    val label: String,
    val barCount: Int,
    val percent: Double
)

internal data class ZThresholdCrossCounts(
    val crossUpEntryShort: Int,
    val crossDownEntryLong: Int,
    val crossUpExitShort: Int,
    val crossDownExitLong: Int,
    val crossUpPyramidShort: Int,
    val crossDownPyramidLong: Int,
    val crossZeroUp: Int,
    val crossZeroDown: Int
)

internal data class ZDailyPeak(
    val day: String,
    val maxAbsZ: Double,
    val minZ: Double,
    val maxZ: Double,
    val barCount: Int
)

internal data class ZMovementReport(
    val barCount: Int,
    val calendarDays: Int,
    val firstBarLabel: String,
    val lastBarLabel: String,
    val minZ: Double,
    val maxZ: Double,
    val meanZ: Double,
    val stdZ: Double,
    val meanAbsZ: Double,
    val medianAbsZ: Double,
    val percentileAbsZ: Map<Int, Double>,
    val meanAbsDeltaZPerBar: Double,
    val maxAbsDeltaZPerBar: Double,
    val zoneShares: List<ZAbsZoneShare>,
    val barsAbsZGe: Map<Double, Int>,
    val crossings: ZThresholdCrossCounts,
    val longestStreakAbsZGe1: Int,
    val topDaysByMaxAbsZ: List<ZDailyPeak>
)

internal fun buildZMovementReport(
    points: List<DataPoint>,
    zoneId: ZoneId = moexMskZone,
    entryThreshold: Double = 0.8,
    exitThreshold: Double = 0.7,
    pyramidDepth: Double = 1.0,
    topDays: Int = 10
): ZMovementReport? {
    if (points.isEmpty()) return null
    val zs = points.map { it.zScore }
    val n = zs.size
    val mean = zs.average()
    val variance = if (n > 1) zs.map { (it - mean) * (it - mean) }.average() else 0.0
    val std = sqrt(variance)
    val absZ = zs.map { abs(it) }.sorted()
    val medianAbs = absZ[absZ.size / 2]

    val deltas = (1 until n).map { abs(zs[it] - zs[it - 1]) }

    val zones = listOf(
        "|Z| < ${fmtTh(exitThreshold)}" to { z: Double -> abs(z) < exitThreshold },
        "${fmtTh(exitThreshold)}–${fmtTh(entryThreshold)}" to { z: Double ->
            val a = abs(z)
            a >= exitThreshold && a < entryThreshold
        },
        "${fmtTh(entryThreshold)}–${fmtTh(pyramidDepth)}" to { z: Double ->
            val a = abs(z)
            a >= entryThreshold && a < pyramidDepth
        },
        "${fmtTh(pyramidDepth)}–1.30" to { z: Double ->
            val a = abs(z)
            a >= pyramidDepth && a < 1.30
        },
        "|Z| ≥ 1.30" to { z: Double -> abs(z) >= 1.30 }
    )
    val zoneShares = zones.map { (label, pred) ->
        val cnt = zs.count(pred)
        ZAbsZoneShare(label, cnt, cnt * 100.0 / n)
    }

    val geLevels = listOf(0.8, 1.0, 1.2, 1.5, 2.0)
    val barsGe = geLevels.associateWith { level -> zs.count { abs(it) >= level } }

    val crossings = countZThresholdCrossings(points, entryThreshold, exitThreshold, pyramidDepth)
    val longestGe1 = longestStreak(zs) { abs(it) >= 1.0 }

    val byDay = linkedMapOf<String, MutableList<Double>>()
    for (p in points) {
        val day = barDayKey(p.tradeDate, zoneId)
        byDay.getOrPut(day) { mutableListOf() }.add(p.zScore)
    }
    val dailyPeaks = byDay.map { (day, dayZs) ->
        ZDailyPeak(
            day = day,
            maxAbsZ = dayZs.maxOf { abs(it) },
            minZ = dayZs.minOrNull() ?: 0.0,
            maxZ = dayZs.maxOrNull() ?: 0.0,
            barCount = dayZs.size
        )
    }.sortedByDescending { it.maxAbsZ }.take(topDays)

    return ZMovementReport(
        barCount = n,
        calendarDays = byDay.size,
        firstBarLabel = points.first().tradeDate,
        lastBarLabel = points.last().tradeDate,
        minZ = zs.minOrNull() ?: 0.0,
        maxZ = zs.maxOrNull() ?: 0.0,
        meanZ = mean,
        stdZ = std,
        meanAbsZ = absZ.average(),
        medianAbsZ = medianAbs,
        percentileAbsZ = percentileMap(absZ, listOf(5, 25, 50, 75, 95, 99)),
        meanAbsDeltaZPerBar = if (deltas.isEmpty()) 0.0 else deltas.average(),
        maxAbsDeltaZPerBar = deltas.maxOrNull() ?: 0.0,
        zoneShares = zoneShares,
        barsAbsZGe = barsGe,
        crossings = crossings,
        longestStreakAbsZGe1 = longestGe1,
        topDaysByMaxAbsZ = dailyPeaks
    )
}

private fun fmtTh(v: Double) = String.format(Locale.US, "%.2f", v)

private fun barDayKey(tradeDateLabel: String, zoneId: ZoneId): String {
    parseBarLocalDateMsk(tradeDateLabel)?.let { return it.toString() }
    return tradeDateLabel.take(10)
}

private fun percentileMap(sorted: List<Double>, pcts: List<Int>): Map<Int, Double> {
    if (sorted.isEmpty()) return pcts.associateWith { 0.0 }
    return pcts.associateWith { p ->
        val idx = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.lastIndex)
        sorted[idx]
    }
}

private fun longestStreak(values: List<Double>, pred: (Double) -> Boolean): Int {
    var best = 0
    var cur = 0
    for (v in values) {
        if (pred(v)) {
            cur++
            best = max(best, cur)
        } else {
            cur = 0
        }
    }
    return best
}

private fun countZThresholdCrossings(
    points: List<DataPoint>,
    entry: Double,
    exit: Double,
    pyramid: Double
): ZThresholdCrossCounts {
    if (points.size < 2) {
        return ZThresholdCrossCounts(0, 0, 0, 0, 0, 0, 0, 0)
    }
    var upEntryShort = 0
    var downEntryLong = 0
    var upExitShort = 0
    var downExitLong = 0
    var upPyrShort = 0
    var downPyrLong = 0
    var upZero = 0
    var downZero = 0
    for (i in 1 until points.size) {
        val prev = points[i - 1].zScore
        val cur = points[i].zScore
        if (prev < entry && cur >= entry) upEntryShort++
        if (prev > -entry && cur <= -entry) downEntryLong++
        if (prev < exit && cur >= exit) upExitShort++
        if (prev > -exit && cur <= -exit) downExitLong++
        if (prev < pyramid && cur >= pyramid) upPyrShort++
        if (prev > -pyramid && cur <= -pyramid) downPyrLong++
        if (prev < 0.0 && cur >= 0.0) upZero++
        if (prev > 0.0 && cur <= 0.0) downZero++
    }
    return ZThresholdCrossCounts(
        crossUpEntryShort = upEntryShort,
        crossDownEntryLong = downEntryLong,
        crossUpExitShort = upExitShort,
        crossDownExitLong = downExitLong,
        crossUpPyramidShort = upPyrShort,
        crossDownPyramidLong = downPyrLong,
        crossZeroUp = upZero,
        crossZeroDown = downZero
    )
}

internal fun formatZMovementReport(
    report: ZMovementReport,
    entryThreshold: Double = 0.8,
    exitThreshold: Double = 0.7
): String = buildString {
    appendLine("=== Аналитика движения Z (15м бары) ===")
    appendLine("Период: ${report.firstBarLabel} … ${report.lastBarLabel}")
    appendLine("Баров: ${report.barCount} · календарных дней: ${report.calendarDays}")
    appendLine()
    appendLine("— Распределение Z —")
    appendLine(
        String.format(
            Locale.US,
            "min %+.2f · max %+.2f · mean %+.3f · σ %.3f",
            report.minZ,
            report.maxZ,
            report.meanZ,
            report.stdZ
        )
    )
    appendLine(
        String.format(
            Locale.US,
            "|Z| mean %.3f · median %.3f",
            report.meanAbsZ,
            report.medianAbsZ
        )
    )
    append("|Z| перцентили: ")
    report.percentileAbsZ.entries.sortedBy { it.key }.forEach { (p, v) ->
        append(String.format(Locale.US, "p%d=%.2f ", p, v))
    }
    appendLine()
    appendLine(
        String.format(
            Locale.US,
            "Δ|Z| за бар: mean %.3f · max %.3f",
            report.meanAbsDeltaZPerBar,
            report.maxAbsDeltaZPerBar
        )
    )
    appendLine()
    appendLine("— Доля времени в полосах |Z| (пороги страт. ${fmtTh(entryThreshold)}/${fmtTh(exitThreshold)}) —")
    for (z in report.zoneShares) {
        appendLine(
            String.format(
                Locale.US,
                "  %-22s %6d баров (%5.1f%%)",
                z.label,
                z.barCount,
                z.percent
            )
        )
    }
    appendLine()
    appendLine("— Бары с экстремальным |Z| —")
    for ((level, cnt) in report.barsAbsZGe.entries.sortedBy { it.key }) {
        appendLine(
            String.format(
                Locale.US,
                "  |Z| ≥ %.1f: %5d (%5.1f%%)",
                level,
                cnt,
                cnt * 100.0 / report.barCount
            )
        )
    }
    appendLine(
        String.format(
            Locale.US,
            "  max подряд |Z|≥1.0: %d баров (~%.1f ч)",
            report.longestStreakAbsZGe1,
            report.longestStreakAbsZGe1 * 15.0 / 60.0
        )
    )
    appendLine()
    appendLine("— Пересечения порогов (между соседними барами) —")
    val c = report.crossings
    appendLine("  Вход SHORT (Z↑ через +${fmtTh(entryThreshold)}): ${c.crossUpEntryShort}")
    appendLine("  Вход LONG  (Z↓ через −${fmtTh(entryThreshold)}): ${c.crossDownEntryLong}")
    appendLine("  Выход SHORT (Z↑ через +${fmtTh(exitThreshold)}): ${c.crossUpExitShort}")
    appendLine("  Выход LONG  (Z↓ через −${fmtTh(exitThreshold)}): ${c.crossDownExitLong}")
    appendLine("  Пирамида SHORT (Z↑ +${fmtTh(1.0)}): ${c.crossUpPyramidShort}")
    appendLine("  Пирамида LONG  (Z↓ −${fmtTh(1.0)}): ${c.crossDownPyramidLong}")
    appendLine("  Пересечение нуля ↑/↓: ${c.crossZeroUp} / ${c.crossZeroDown}")
    appendLine()
    appendLine("— Топ-${report.topDaysByMaxAbsZ.size} дней по max |Z| —")
    appendLine(String.format(Locale.US, "  %-12s %7s %7s %7s %5s", "день", "max|Z|", "min Z", "max Z", "баров"))
    for (d in report.topDaysByMaxAbsZ) {
        appendLine(
            String.format(
                Locale.US,
                "  %-12s %7.2f %7.2f %7.2f %5d",
                d.day,
                d.maxAbsZ,
                d.minZ,
                d.maxZ,
                d.barCount
            )
        )
    }
}
