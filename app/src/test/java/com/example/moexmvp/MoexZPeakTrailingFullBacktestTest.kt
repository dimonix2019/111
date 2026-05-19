package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Полная выборка MOEX 15м TATN/TATNP: пороговый выход vs трейлинг от пика Z.
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexZPeakTrailingFullBacktestTest`
 */
class MoexZPeakTrailingFullBacktestTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val entryZ = 0.8

    @Test
    fun fullSample_thresholdVsZPeakTrailing() = runBlocking {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue("Нет 15м данных MOEX", entities.isNotEmpty())
        val points = applyZScores(entities.map { it.toDataPoint() })
        assertTrue("Мало точек", points.size >= 2)

        val baselineExit = 0.7
        val baseline = runMetrics(
            points,
            today,
            exitMode = ZStrategyExitMode.FixedThreshold,
            exitOrTrailLabel = "порог $baselineExit",
            zPeakTrailZ = baselineExit
        )
        checkNotNull(baseline)

        val trailSteps = listOf(0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40)
        val trailingRuns = trailSteps.map { trail ->
            trail to checkNotNull(
                runMetrics(
                    points,
                    today,
                    exitMode = ZStrategyExitMode.ZPeakTrailing,
                    exitOrTrailLabel = "трейл $trail",
                    zPeakTrailZ = trail
                )
            )
        }

        println("=== MOEX полная выборка: порог vs трейлинг от пика Z ===")
        println("Период: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        println("Вход |Z| = $entryZ, плечо 7×, комиссия 0.05%/сторона, notional ${DEFAULT_PORTFOLIO_NOTIONAL_RUB.toLong()} ₽")
        println()
        println("режим          | сделок | win%  | PnL ₽      | max DD ₽  | PF    | комис. ₽  | оверн. ₽")
        println("---------------+--------+-------+------------+-----------+-------+-----------+----------")
        println(formatRow("порог $baselineExit", baseline))
        trailingRuns.forEach { (trail, m) -> println(formatRow("трейл $trail", m)) }

        val bestTrail = trailingRuns.maxByOrNull { it.second.totalPnlRubApprox }!!
        println()
        println("Лучший трейл по PnL: ${bestTrail.first} → ${fmt(bestTrail.second.totalPnlRubApprox)} ₽ " +
            "(база порог: ${fmt(baseline.totalPnlRubApprox)} ₽, Δ ${fmt(bestTrail.second.totalPnlRubApprox - baseline.totalPnlRubApprox)} ₽)")
        println("Сделок база ${baseline.closedTrades.size} vs трейл ${bestTrail.second.closedTrades.size}")
    }

    private fun runMetrics(
        points: List<DataPoint>,
        today: LocalDate,
        exitMode: ZStrategyExitMode,
        exitOrTrailLabel: String,
        zPeakTrailZ: Double
    ): PortfolioMetrics? {
        val exitForThresholds = if (exitMode == ZStrategyExitMode.FixedThreshold) zPeakTrailZ else 0.7
        return buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = DynamicThresholds(entry = entryZ, exit = exitForThresholds, calculatedDate = today.toString()),
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.05,
            periodDescription = "MOEX full $exitOrTrailLabel",
            compoundReturns = false,
            exitMode = exitMode,
            zPeakTrailZ = zPeakTrailZ
        )
    }

    private fun formatRow(label: String, m: PortfolioMetrics): String =
        String.format(
            Locale.US,
            "%-14s | %6d | %5.1f | %10s | %9s | %5.2f | %9s | %8s",
            label,
            m.closedTrades.size,
            m.winRate,
            fmt(m.totalPnlRubApprox),
            fmt(m.maxDrawdownRubApprox),
            m.profitFactor ?: 0.0,
            fmt(m.totalCommissionRub),
            fmt(m.totalOvernightRub)
        )

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
}
