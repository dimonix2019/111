package com.example.moexmvp

import java.util.Locale
import kotlin.math.abs

internal data class OpenTradePnlForecastRow(
    val label: String,
    val zTarget: Double,
    val spreadTargetPercent: Double,
    val spreadDeltaPts: Double,
    val grossRub: Double,
    val netRubApprox: Double,
    val isExitLevel: Boolean = false,
    val isCurrentLevel: Boolean = false,
)

internal data class OpenTradePnlForecast(
    val entryZ: Double,
    val entrySpreadPercent: Double,
    val spreadMeanPercent: Double,
    val spreadSigmaPercent: Double,
    val notionalRub: Double,
    val rows: List<OpenTradePnlForecastRow>,
)

/** Сценарии PnL при целевых Z (при неизменных μ, σ последнего 15м бара). */
internal fun buildOpenTradePnlForecast(
    exec: SandboxSpreadExecUi,
    points: List<DataPoint>,
    entryThreshold: Double,
    exitThreshold: Double,
    leverage: Double,
    commissionPercentPerSide: Double,
    executionMode: TinkoffExecutionMode,
): OpenTradePnlForecast? {
    if (points.size < 2) return null
    if (exec.signalType != StrategySignalType.EnterLong &&
        exec.signalType != StrategySignalType.EnterShort
    ) {
        return null
    }
    val stats = rollingSpreadStatsAt(points, points.lastIndex) ?: return null
    if (stats.stdDev <= 0.0) return null

    val entrySpread = resolveEntrySpreadPercent(
        exec.entrySpreadPercent,
        exec.barTimestampMillis,
        points,
    )
    val entryZ = exec.zScore
    val currentZ = exec.exitZDisplay.takeUnless { it.isNaN() }
    val notionalRub = resolveTradeNotionalRubForPnl(exec, points)
    if (notionalRub <= 0.0) return null
    val pnlLeverage = portfolioPnlLeverageMultiplier(executionMode, leverage)
    val effNotional = notionalRub * pnlLeverage

    val entryDateLabel = points
        .minByOrNull { kotlin.math.abs(it.timestampMillis - exec.barTimestampMillis) }
        ?.tradeDate ?: exec.entryTimeMsk
    val exitDateLabel = points.last().tradeDate
    val (entryCommRub, overnightRub) = portfolioTradeCommissionAndOvernightRub(
        notionalRub = notionalRub,
        leverage = pnlLeverage,
        commissionPercentPerSide = commissionPercentPerSide,
        entryDateLabel = entryDateLabel,
        exitDateLabel = exitDateLabel,
        includeExitCommission = false,
    )
    val exitCommRub = effNotional * (commissionPercentPerSide / 100.0)

    val targets = forecastZTargets(
        signalType = exec.signalType,
        entryZ = entryZ,
        currentZ = currentZ,
        entryThreshold = entryThreshold,
        exitThreshold = exitThreshold,
    )

    val rows = targets.map { target ->
        val spreadTarget = stats.mean + target.z * stats.stdDev
        val spreadDelta = openSpreadMtmPoints(exec.signalType, entrySpread, spreadTarget)
        val grossRub = spreadPnlToRubApprox(spreadDelta, effNotional)
        val netRub = grossRub - entryCommRub - exitCommRub - overnightRub
        OpenTradePnlForecastRow(
            label = target.label,
            zTarget = target.z,
            spreadTargetPercent = spreadTarget,
            spreadDeltaPts = spreadDelta,
            grossRub = grossRub,
            netRubApprox = netRub,
            isExitLevel = target.isExit,
            isCurrentLevel = target.isCurrent,
        )
    }

    return OpenTradePnlForecast(
        entryZ = entryZ,
        entrySpreadPercent = entrySpread,
        spreadMeanPercent = stats.mean,
        spreadSigmaPercent = stats.stdDev,
        notionalRub = notionalRub,
        rows = rows,
    )
}

private data class ForecastZTarget(
    val label: String,
    val z: Double,
    val isExit: Boolean = false,
    val isCurrent: Boolean = false,
)

private fun forecastZTargets(
    signalType: StrategySignalType,
    entryZ: Double,
    currentZ: Double?,
    entryThreshold: Double,
    exitThreshold: Double,
): List<ForecastZTarget> {
    val entryT = entryThreshold.coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    val exitT = exitThreshold.coerceIn(PORTFOLIO_Z_THRESHOLD_MIN, PORTFOLIO_Z_THRESHOLD_MAX)
    val raw = when (signalType) {
        StrategySignalType.EnterLong -> listOf(
            ForecastZTarget("Z₀ вход", entryZ),
            ForecastZTarget("Выход LONG (−exit)", -exitT, isExit = true),
            ForecastZTarget("Z = 0 (средний спред)", 0.0),
            ForecastZTarget("Противоп. (+entry)", entryT),
        )
        StrategySignalType.EnterShort -> listOf(
            ForecastZTarget("Z₀ вход", entryZ),
            ForecastZTarget("Выход SHORT (+exit)", exitT, isExit = true),
            ForecastZTarget("Z = 0 (средний спред)", 0.0),
            ForecastZTarget("Противоп. (−entry)", -entryT),
        )
        else -> emptyList()
    }
    val withCurrent = buildList {
        addAll(raw)
        currentZ?.let { z ->
            if (abs(z - entryZ) > 0.04) {
                add(ForecastZTarget("Сейчас", z, isCurrent = true))
            }
        }
    }
    return withCurrent
        .distinctBy { String.format(Locale.US, "%.2f", it.z) }
        .sortedBy { it.z }
}

internal fun formatOpenTradePnlForecastSigma(sigma: Double): String =
    String.format(Locale.US, "%.2f", sigma)

internal fun formatOpenTradePnlForecastSpreadDelta(deltaPts: Double): String =
    String.format(Locale.US, "%+.2f п.п.", deltaPts)
