package com.example.moexmvp

/**
 * После выхода по порогу — новый вход в ту же сторону только после «перезарядки»:
 * SHORT: Z строго ниже exit; LONG: Z строго выше −exit (пока FLAT).
 * Блокирует ложный re-entry на следующем баре (напр. 18:00 после exit 17:45).
 */
internal class ZStrategyRearmState {
    var shortRearmRequired: Boolean = false
        private set
    var longRearmRequired: Boolean = false
        private set

    fun onExitShort() {
        shortRearmRequired = true
    }

    fun onExitLong() {
        longRearmRequired = true
    }

    fun updateWhileFlat(currentZ: Double, exitThreshold: Double) {
        if (currentZ < exitThreshold) {
            shortRearmRequired = false
        }
        if (currentZ > -exitThreshold) {
            longRearmRequired = false
        }
    }

    fun canEnterShort(): Boolean = !shortRearmRequired

    fun canEnterLong(): Boolean = !longRearmRequired
}

internal fun resolveZStrategySignalWithRearm(
    previousZ: Double?,
    currentZ: Double,
    position: ZStrategyPosition,
    thresholds: DynamicThresholds,
    rearm: ZStrategyRearmState,
): ZStrategySignal {
    if (position == ZStrategyPosition.Flat) {
        rearm.updateWhileFlat(currentZ, thresholds.exit)
    }
    val raw = determineZStrategySignal(previousZ, currentZ, position, thresholds)
    return when (raw) {
        ZStrategySignal.EnterShort ->
            if (rearm.canEnterShort()) ZStrategySignal.EnterShort else ZStrategySignal.None
        ZStrategySignal.EnterLong ->
            if (rearm.canEnterLong()) ZStrategySignal.EnterLong else ZStrategySignal.None
        else -> raw
    }
}
