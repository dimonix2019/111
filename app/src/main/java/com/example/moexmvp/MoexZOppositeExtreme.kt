package com.example.moexmvp

/**
 * Выход на противоположном экстремуме Z:
 * Long закрывается при пересечении вверх +[oppositeThreshold],
 * Short — при пересечении вниз −[oppositeThreshold].
 *
 * [oppositeThreshold] — поле [DynamicThresholds.exit] (уровень «противоположной» стороны).
 */
internal fun oppositeExtremeExitLong(
    prevZ: Double,
    currentZ: Double,
    oppositeThreshold: Double,
): Boolean =
    prevZ < oppositeThreshold && currentZ >= oppositeThreshold

internal fun oppositeExtremeExitShort(
    prevZ: Double,
    currentZ: Double,
    oppositeThreshold: Double,
): Boolean =
    prevZ > -oppositeThreshold && currentZ <= -oppositeThreshold

internal fun oppositeExtremeExitLongBetweenBars(
    prev: DataPoint,
    current: DataPoint,
    oppositeThreshold: Double,
): Boolean {
    if (!isConsecutiveM15Bar(prev, current)) return false
    return oppositeExtremeExitLong(prev.zScore, current.zScore, oppositeThreshold)
}

internal fun oppositeExtremeExitShortBetweenBars(
    prev: DataPoint,
    current: DataPoint,
    oppositeThreshold: Double,
): Boolean {
    if (!isConsecutiveM15Bar(prev, current)) return false
    return oppositeExtremeExitShort(prev.zScore, current.zScore, oppositeThreshold)
}
