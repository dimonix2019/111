package com.example.moexmvp

/** Ожидание отката Z после пробоя порога входа (Flat). */
internal enum class ZEntryPendingArm {
    None,
    Long,
    Short
}

/** Отмена long-setup: Z вернулся выше линии выхода без входа. */
internal fun zPullbackEntryCancelLong(currentZ: Double, exitThreshold: Double): Boolean =
    currentZ > -exitThreshold

/** Отмена short-setup: Z вернулся ниже линии выхода без входа. */
internal fun zPullbackEntryCancelShort(currentZ: Double, exitThreshold: Double): Boolean =
    currentZ < exitThreshold

/** Long: вход после отскока вверх от минимума Z на [pullbackZ]. */
internal fun zPullbackLongEntryTriggered(
    currentZ: Double,
    zExtreme: Double,
    pullbackZ: Double
): Boolean = currentZ >= zExtreme + pullbackZ

/** Short: вход после отката вниз от максимума Z на [pullbackZ]. */
internal fun zPullbackShortEntryTriggered(
    currentZ: Double,
    zExtreme: Double,
    pullbackZ: Double
): Boolean = currentZ <= zExtreme - pullbackZ

internal fun zPullbackArmLongCross(prevZ: Double, currentZ: Double, entryThreshold: Double): Boolean =
    prevZ > -entryThreshold && currentZ <= -entryThreshold

internal fun zPullbackArmShortCross(prevZ: Double, currentZ: Double, entryThreshold: Double): Boolean =
    prevZ < entryThreshold && currentZ >= entryThreshold
