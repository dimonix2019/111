package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Сетка асимметричных порогов вход/выход 0.2…2.5 шаг 0.1.
 *
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexThresholdAsymmetricSweepTest.moexBacktest_255d_10k_prodLike_asymmetricThreshold_0_2_5`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexThresholdAsymmetricSweepTest.moexBacktest_3y_asymmetricThreshold_0_2_5`
 */
class MoexThresholdAsymmetricSweepTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private enum class SweepProfile(val label: String) {
        Backtest100kX7("100k ₽ ×7, комис. 0.04%, rolling Z 30д"),
        ProdLike10k("10k ₽ prod-like: 80%, ×7 лоты, комис. 0.04%, slip 0.05п, rolling Z+guard"),
    }

    private data class ThresholdSweepCell(
        val entry: Double,
        val exit: Double,
        val metrics: PortfolioMetrics,
    ) {
        val gap: Double = ((entry - exit) * 10).roundToInt() / 10.0
        val pnl: Double get() = metrics.totalPnlRubApprox
        val trades: Int get() = metrics.closedTrades.size
        val maxDd: Double get() = metrics.maxDrawdownRubApprox
    }

    @Test
    fun moexBacktest_255d_10k_prodLike_asymmetricThreshold_0_2_5() = runBlocking {
        val points = loadMoex15mRaw(PORTFOLIO_M15_LOOKBACK_DAYS)
        val simPoints = prepareM15PointsForZStrategySignalDetection(points)
        val report = runHypothesisReport(
            title = "255д MOEX · 10k prod-like",
            points = simPoints,
            profile = SweepProfile.ProdLike10k,
        )
        println(report)
        java.io.File("/tmp/moex_threshold_255d_10k_prodlike.txt").writeText(report)
        assertTrue(report.contains("BEST:"))
    }

    @Test
    fun moexBacktest_3y_asymmetricThreshold_0_2_5() = runBlocking {
        val raw = loadMoex15mRaw(BACKTEST_3Y_LOOKBACK_DAYS)
        val backtestPoints = applyZScoresDefault(raw.map { it.copy() })
        val prodPoints = prepareM15PointsForZStrategySignalDetection(raw)

        val report100k = runHypothesisReport(
            title = "3 года MOEX · 100k ×7",
            points = backtestPoints,
            profile = SweepProfile.Backtest100kX7,
        )
        val report10k = runHypothesisReport(
            title = "3 года MOEX · 10k prod-like",
            points = prodPoints,
            profile = SweepProfile.ProdLike10k,
        )
        val combined = buildString {
            appendLine("=== Асимметричные пороги 0.2…2.5 (3 года MOEX ISS) ===")
            appendLine("Ряд: ${backtestPoints.first().tradeDate} … ${backtestPoints.last().tradeDate} (${backtestPoints.size} баров)")
            appendLine()
            append(report100k)
            appendLine()
            appendLine("---")
            appendLine()
            append(report10k)
        }
        println(combined)
        java.io.File("/tmp/moex_threshold_3y_combined.txt").writeText(combined)
        assertTrue(combined.contains("BEST:"))
    }

    private suspend fun loadMoex15mRaw(lookbackDays: Long): List<DataPoint> {
        val today = LocalDate.now(zone)
        val from = today.minusDays(lookbackDays)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue("Нет MOEX 15м ($from…$till)", entities.isNotEmpty())
        val points = entities.map { it.toDataPoint() }
        assertTrue("Мало баров: ${points.size}", points.size >= 100)
        return points
    }

    private fun thresholdSweepSteps(min: Double = SWEEP_MIN, max: Double = SWEEP_MAX): List<Double> {
        val steps = mutableListOf<Double>()
        var v = min
        while (v <= max + 1e-9) {
            steps += (v * 10).roundToInt() / 10.0
            v += SWEEP_STEP
        }
        return steps
    }

    private fun runThresholdGapSweep(
        points: List<DataPoint>,
        steps: List<Double>,
        profile: SweepProfile,
    ): List<ThresholdSweepCell> {
        val cells = mutableListOf<ThresholdSweepCell>()
        for (entry in steps) {
            for (exit in steps) {
                if (exit >= entry) continue
                val m = runSim(points, DynamicThresholds(entry, exit, null), profile) ?: continue
                cells += ThresholdSweepCell(entry, exit, m)
            }
        }
        return cells
    }

    private fun runSim(
        points: List<DataPoint>,
        thresholds: DynamicThresholds,
        profile: SweepProfile,
    ): PortfolioMetrics? = when (profile) {
        SweepProfile.Backtest100kX7 -> buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = BACKTEST_NOTIONAL_100K_RUB,
            leverage = BACKTEST_LEVERAGE_X7,
            commissionPercentPerSide = BACKTEST_COMMISSION_PCT,
            periodDescription = "sweep 100k×7",
            compoundReturns = false,
        )
        SweepProfile.ProdLike10k -> buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = DEFAULT_STRATEGY_TEST_ACCOUNT_RUB,
            leverage = 1.0,
            commissionPercentPerSide = BACKTEST_COMMISSION_PCT,
            periodDescription = "sweep 10k prod-like",
            compoundReturns = false,
            simOptions = ZStrategySimOptions(slippageSpreadPts = DEFAULT_STRATEGY_TEST_SLIPPAGE_SPREAD_PTS),
            prodLikeSizing = ZStrategyProdLikeSizing(
                accountSizeRub = DEFAULT_STRATEGY_TEST_ACCOUNT_RUB,
                capitalUsagePercent = DEFAULT_STRATEGY_TEST_CAPITAL_USAGE_PERCENT,
                leverageForLots = 7.0,
            ),
        )
    }

    private fun runHypothesisReport(
        title: String,
        points: List<DataPoint>,
        profile: SweepProfile,
    ): String {
        val steps = thresholdSweepSteps()
        val cells = runThresholdGapSweep(points, steps, profile)
        assertTrue("Нет результатов сетки ($title)", cells.isNotEmpty())

        val best = cells.maxByOrNull { it.pnl }!!
        val baseline075 = cells.find { near(it.entry, 0.7) && near(it.exit, 0.5) }
        val baseline087 = cells.find { near(it.entry, 0.8) && near(it.exit, 0.7) }

        val bestExitPerEntry = cells.groupBy { it.entry }.mapValues { (_, g) -> g.maxByOrNull { it.pnl }!! }
        val minGapPerEntry = steps.associateWith { entry ->
            val tightExit = (entry - SWEEP_STEP).coerceAtLeast(steps.first())
            cells.find { near(it.entry, entry) && near(it.exit, tightExit) }
        }.filterValues { it != null }.mapValues { it.value!! }

        val tunedBeatsTight = bestExitPerEntry.count { (entry, bestCell) ->
            val tight = minGapPerEntry[entry] ?: return@count false
            bestCell.pnl > tight.pnl + 1.0
        }
        val profitable = cells.count { it.pnl > 0.0 }
        val top10AvgGap = cells.sortedByDescending { it.pnl }.take(10).map { it.gap }.average()

        return buildString {
            appendLine("=== $title ===")
            appendLine("Профиль: ${profile.label}")
            appendLine("Сетка: 0.2…2.5 шаг 0.1, exit < entry → ${cells.size} комбинаций")
            appendLine("Прибыльных: $profitable (${fmt1(100.0 * profitable / cells.size)}%)")
            appendLine()
            appendLine("BEST: вход ±${fmt(best.entry)} / выход ±${fmt(best.exit)} Δ=${fmt(best.gap)}")
            appendLine("  PnL ${fmt(best.pnl)} ₽, сделок ${best.trades}, max DD ${fmt(best.maxDd)} ₽")
            baseline075?.let {
                appendLine("  baseline 0.7/0.5: PnL ${fmt(it.pnl)} ₽ (Δ vs best ${fmt(it.pnl - best.pnl)})")
            }
            baseline087?.let {
                appendLine("  baseline 0.8/0.7: PnL ${fmt(it.pnl)} ₽")
            }
            appendLine()
            appendLine("Подбор exit отдельно: ${tunedBeatsTight}/${bestExitPerEntry.size} entry лучше tight Δ=0.1")
            appendLine("Средний Δ в TOP-10: ${fmt1(top10AvgGap)}")
            appendLine()
            appendLine("BEST PER GAP:")
            appendLine(String.format(Locale.US, "%4s %5s %5s %12s %7s %10s", "Δ", "вход", "выход", "PnL ₽", "сделок", "DD ₽"))
            cells.groupBy { it.gap }.toSortedMap().forEach { (gap, group) ->
                val row = group.maxByOrNull { it.pnl }!!
                appendLine(
                    String.format(
                        Locale.US,
                        "%4s %5s %5s %12s %7d %10s",
                        fmt(gap), fmt(row.entry), fmt(row.exit), fmt(row.pnl), row.trades, fmt(row.maxDd),
                    )
                )
            }
            appendLine()
            appendLine("TOP 10:")
            cells.sortedByDescending { it.pnl }.take(10).forEach { c ->
                appendLine(
                    "  ±${fmt(c.entry)}/±${fmt(c.exit)} Δ=${fmt(c.gap)} → ${fmt(c.pnl)} ₽ " +
                        "(${c.trades} сд., DD ${fmt(c.maxDd)})"
                )
            }
            appendLine()
            appendLine("Entry 0.7 — лучшие exit:")
            cells.filter { near(it.entry, 0.7) }.sortedByDescending { it.pnl }.take(6).forEach { c ->
                appendLine("  exit ±${fmt(c.exit)} Δ=${fmt(c.gap)} → ${fmt(c.pnl)} ₽ (${c.trades} сд.)")
            }
        }
    }

    private fun near(a: Double, b: Double, eps: Double = 0.011): Boolean = kotlin.math.abs(a - b) < eps

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

    private companion object {
        const val SWEEP_MIN = 0.2
        const val SWEEP_MAX = 2.5
        const val SWEEP_STEP = 0.1
        const val BACKTEST_3Y_LOOKBACK_DAYS = 365L * 3L
        const val BACKTEST_NOTIONAL_100K_RUB = 100_000.0
        const val BACKTEST_LEVERAGE_X7 = 7.0
        const val BACKTEST_COMMISSION_PCT = 0.04
    }
}
