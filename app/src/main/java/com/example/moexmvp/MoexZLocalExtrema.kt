package com.example.moexmvp

import kotlin.math.abs

/**
 * Локальные экстремумы Z на тройке баров (prevPrev → prev → current):
 * пик/дно считается на среднем баре [prev].
 */

internal fun isLocalZTrough(prevPrevZ: Double, prevZ: Double, currentZ: Double): Boolean =
    prevPrevZ > prevZ && prevZ < currentZ

internal fun isLocalZPeak(prevPrevZ: Double, prevZ: Double, currentZ: Double): Boolean =
    prevPrevZ < prevZ && prevZ > currentZ

/** Long: локальный минимум на prev и глубина |Z| ≥ порога входа. */
internal fun localExtremaLongEntry(
    prevPrevZ: Double,
    prevZ: Double,
    currentZ: Double,
    entryThreshold: Double,
): Boolean =
    isLocalZTrough(prevPrevZ, prevZ, currentZ) && prevZ <= -entryThreshold

/** Short: локальный максимум на prev и |Z| ≥ порога входа. */
internal fun localExtremaShortEntry(
    prevPrevZ: Double,
    prevZ: Double,
    currentZ: Double,
    entryThreshold: Double,
): Boolean =
    isLocalZPeak(prevPrevZ, prevZ, currentZ) && prevZ >= entryThreshold

/**
 * Long: локальный максимум на prev после отскока от дна сделки минимум на [minRecoveryZ].
 * [minRecoveryZ] — поле exit порогов (минимальный подъём Z от zBest).
 */
internal fun localExtremaExitLong(
    prevPrevZ: Double,
    prevZ: Double,
    currentZ: Double,
    zBestSinceEntry: Double,
    entryThreshold: Double,
    minRecoveryZ: Double,
): Boolean {
    if (abs(zBestSinceEntry) < entryThreshold) return false
    if (prevZ < zBestSinceEntry + minRecoveryZ) return false
    return isLocalZPeak(prevPrevZ, prevZ, currentZ)
}

/** Short: локальный минимум на prev после отката от пика сделки. */
internal fun localExtremaExitShort(
    prevPrevZ: Double,
    prevZ: Double,
    currentZ: Double,
    zBestSinceEntry: Double,
    entryThreshold: Double,
    minRecoveryZ: Double,
): Boolean {
    if (abs(zBestSinceEntry) < entryThreshold) return false
    if (prevZ > zBestSinceEntry - minRecoveryZ) return false
    return isLocalZTrough(prevPrevZ, prevZ, currentZ)
}

internal fun localExtremaLongEntryBetweenBars(
    prevPrev: DataPoint,
    prev: DataPoint,
    current: DataPoint,
    entryThreshold: Double,
): Boolean {
    if (!isConsecutiveM15Bar(prevPrev, prev) || !isConsecutiveM15Bar(prev, current)) return false
    return localExtremaLongEntry(prevPrev.zScore, prev.zScore, current.zScore, entryThreshold)
}

internal fun localExtremaShortEntryBetweenBars(
    prevPrev: DataPoint,
    prev: DataPoint,
    current: DataPoint,
    entryThreshold: Double,
): Boolean {
    if (!isConsecutiveM15Bar(prevPrev, prev) || !isConsecutiveM15Bar(prev, current)) return false
    return localExtremaShortEntry(prevPrev.zScore, prev.zScore, current.zScore, entryThreshold)
}

internal fun localExtremaExitLongBetweenBars(
    prevPrev: DataPoint,
    prev: DataPoint,
    current: DataPoint,
    zBestSinceEntry: Double,
    entryThreshold: Double,
    minRecoveryZ: Double,
): Boolean {
    if (!isConsecutiveM15Bar(prevPrev, prev) || !isConsecutiveM15Bar(prev, current)) return false
    return localExtremaExitLong(
        prevPrev.zScore,
        prev.zScore,
        current.zScore,
        zBestSinceEntry,
        entryThreshold,
        minRecoveryZ,
    )
}

internal fun localExtremaExitShortBetweenBars(
    prevPrev: DataPoint,
    prev: DataPoint,
    current: DataPoint,
    zBestSinceEntry: Double,
    entryThreshold: Double,
    minRecoveryZ: Double,
): Boolean {
    if (!isConsecutiveM15Bar(prevPrev, prev) || !isConsecutiveM15Bar(prev, current)) return false
    return localExtremaExitShort(
        prevPrev.zScore,
        prev.zScore,
        current.zScore,
        zBestSinceEntry,
        entryThreshold,
        minRecoveryZ,
    )
}
