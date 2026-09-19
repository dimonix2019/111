package com.example.moexmvp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Как закрывать позицию по Z-стратегии. */
internal enum class ZStrategyExitMode {
    /** Фиксированный порог |Z| (как сейчас в портфеле). */
    FixedThreshold,
    /** Выход при откате Z от экстремума внутри сделки (трейлинг от пика). */
    ZPeakTrailing,
    /** Выход при пересечении противоположного экстремума Z (+/− exit как «другая сторона»). */
    OppositeExtreme,
    /** Вход на локальном дне/вершине Z, выход на локальном противоположном экстремуме. */
    LocalExtrema,
}

internal fun parseZStrategyExitMode(raw: String?): ZStrategyExitMode =
    runCatching { ZStrategyExitMode.valueOf(raw.orEmpty()) }
        .getOrDefault(ZStrategyExitMode.FixedThreshold)

/**
 * Long: запоминаем минимальный Z (самый «дешёвый» спред), выходим при отскоке вверх на [trailZ].
 * Выход только если |zBest| достиг [entryThreshold] — иначе шум сразу после входа.
 */
internal fun zPeakTrailingExitLong(
    currentZ: Double,
    zBestSinceEntry: Double,
    entryThreshold: Double,
    trailZ: Double
): Boolean {
    if (abs(zBestSinceEntry) < entryThreshold) return false
    return currentZ >= zBestSinceEntry + trailZ
}

/** Short: максимальный Z в сделке, выход при откате вниз на [trailZ]. */
internal fun zPeakTrailingExitShort(
    currentZ: Double,
    zBestSinceEntry: Double,
    entryThreshold: Double,
    trailZ: Double
): Boolean {
    if (abs(zBestSinceEntry) < entryThreshold) return false
    return currentZ <= zBestSinceEntry - trailZ
}

internal fun updateZBestForLong(zBestSinceEntry: Double, currentZ: Double): Double =
    min(zBestSinceEntry, currentZ)

internal fun updateZBestForShort(zBestSinceEntry: Double, currentZ: Double): Double =
    max(zBestSinceEntry, currentZ)

internal fun fixedThresholdExitLong(prevZ: Double, currentZ: Double, exitThreshold: Double): Boolean =
    prevZ < -exitThreshold && currentZ >= -exitThreshold

internal fun fixedThresholdExitShort(prevZ: Double, currentZ: Double, exitThreshold: Double): Boolean =
    prevZ > exitThreshold && currentZ <= exitThreshold
