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
    val isBrokerFact: Boolean = false,
)

internal data class OpenTradePnlForecast(
    val entryZ: Double,
    val entrySpreadPercent: Double,
    val spreadMeanPercent: Double,
    val spreadSigmaPercent: Double,
    val notionalRub: Double,
    val rows: List<OpenTradePnlForecastRow>,
    /** Прогноз калиброван по факту Tinkoff (вход → сейчас), не только μ/σ MOEX. */
    val calibratedFromBroker: Boolean = false,
)

/**
 * Сценарии PnL при целевых Z.
 * «Сейчас» = факт с брокера; остальное — линейная экстраполяция по ΔZ от входа
 * (если есть broker gross), иначе Δспред от Z₀ с σ rolling 15м.
 */
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

    val brokerGrossRub = brokerOpenTradeGrossRub(exec)
    val brokerNetRub = exec.netPnlRubApprox.takeUnless { it.isNaN() }
    val zDeltaCurrent = if (currentZ != null) currentZ - entryZ else null
    val calibratedFromBroker = brokerGrossRub != null &&
        brokerNetRub != null &&
        zDeltaCurrent != null &&
        abs(zDeltaCurrent) > 0.05

    val sigmaForSpreadModel = if (calibratedFromBroker && zDeltaCurrent != null) {
        val currentSpread = points.last().spreadPercent
        abs((currentSpread - entrySpread) / zDeltaCurrent)
            .takeIf { it > 1e-6 }
            ?: stats.stdDev
    } else {
        stats.stdDev
    }

    val targets = forecastZTargets(
        signalType = exec.signalType,
        entryZ = entryZ,
        currentZ = currentZ,
        entryThreshold = entryThreshold,
        exitThreshold = exitThreshold,
    )

    val rows = targets.map { target ->
        if (target.isCurrent && brokerNetRub != null && brokerGrossRub != null) {
            val spreadDelta = openSpreadMtmPoints(
                exec.signalType,
                entrySpread,
                points.last().spreadPercent,
            )
            OpenTradePnlForecastRow(
                label = "Сейчас · факт Tinkoff",
                zTarget = target.z,
                spreadTargetPercent = points.last().spreadPercent,
                spreadDeltaPts = spreadDelta,
                grossRub = brokerGrossRub,
                netRubApprox = brokerNetRub,
                isCurrentLevel = true,
                isBrokerFact = true,
            )
        } else if (calibratedFromBroker && zDeltaCurrent != null && brokerGrossRub != null) {
            val zDelta = target.z - entryZ
            val fraction = zDelta / zDeltaCurrent
            val grossRub = brokerGrossRub * fraction
            val exitCommScaled = exitCommRub * abs(fraction).coerceIn(0.0, 1.0)
            val targetSpread = entrySpread + zDelta * sigmaForSpreadModel
            val spreadDelta = openSpreadMtmPoints(exec.signalType, entrySpread, targetSpread)
            val netRub = grossRub - entryCommRub - exitCommScaled - overnightRub * abs(fraction).coerceIn(0.0, 1.0)
            OpenTradePnlForecastRow(
                label = target.label,
                zTarget = target.z,
                spreadTargetPercent = targetSpread,
                spreadDeltaPts = spreadDelta,
                grossRub = grossRub,
                netRubApprox = netRub,
                isExitLevel = target.isExit,
                isCurrentLevel = target.isCurrent,
            )
        } else {
            val targetSpread = entrySpread + (target.z - entryZ) * stats.stdDev
            val spreadDelta = openSpreadMtmPoints(exec.signalType, entrySpread, targetSpread)
            val grossRub = spreadPnlToRubApprox(spreadDelta, effNotional)
            val netRub = grossRub - entryCommRub - exitCommRub - overnightRub
            OpenTradePnlForecastRow(
                label = target.label,
                zTarget = target.z,
                spreadTargetPercent = targetSpread,
                spreadDeltaPts = spreadDelta,
                grossRub = grossRub,
                netRubApprox = netRub,
                isExitLevel = target.isExit,
                isCurrentLevel = target.isCurrent,
            )
        }
    }

    return OpenTradePnlForecast(
        entryZ = entryZ,
        entrySpreadPercent = entrySpread,
        spreadMeanPercent = stats.mean,
        spreadSigmaPercent = sigmaForSpreadModel,
        notionalRub = notionalRub,
        rows = rows,
        calibratedFromBroker = calibratedFromBroker,
    )
}

/** Gross PnL открытой сделки с GetPortfolio (сумма expectedYield ног). */
internal fun brokerOpenTradeGrossRub(exec: SandboxSpreadExecUi): Double? {
    if (exec.signalType != StrategySignalType.EnterLong &&
        exec.signalType != StrategySignalType.EnterShort
    ) {
        return null
    }
    if (exec.legLongPnlSplitRubApprox.isNaN() || exec.legShortPnlSplitRubApprox.isNaN()) {
        return null
    }
    return exec.legLongPnlSplitRubApprox + exec.legShortPnlSplitRubApprox
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
