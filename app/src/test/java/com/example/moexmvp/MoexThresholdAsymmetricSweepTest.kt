package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Сетка асимметричных порогов 0.2…2.5 (prod-like, как «Тест страт.»).
 *
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexThresholdAsymmetricSweepTest.moexBacktest_255d_prodLike_asymmetricThreshold_0_2_5`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexThresholdAsymmetricSweepTest.moexBacktest_3y_prodLike_asymmetricThreshold_0_2_5`
 */
class MoexThresholdAsymmetricSweepTest {

    private val zone = ZoneId.of("Europe/Moscow")

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
    fun moexThresholdTopPairs_fullMetrics_report() = runBlocking {
        val steps = thresholdSweepSteps()
        val reports = buildString {
            appendLine("=== TOP-15 пар вход/выход · prod-like ===")
            appendLine("Счёт ${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()} ₽, ${DEFAULT_STRATEGY_TEST_CAPITAL_USAGE_PERCENT.toInt()}%, ×7 лоты, slip 0.05п")
            appendLine()

            val raw255 = loadMoex15mRaw(PORTFOLIO_M15_LOOKBACK_DAYS)
            append(topPairsFullReport("255д MOEX", prepareMoexPointsForProdLikeSim(raw255), steps))
            appendLine()

            val raw3y = loadMoex15mRaw(BACKTEST_3Y_LOOKBACK_DAYS)
            append(topPairsFullReport("3 года MOEX", prepareMoexPointsForProdLikeSim(raw3y), steps))
        }
        println(reports)
        java.io.File("/tmp/moex_threshold_top_full.txt").writeText(reports)
        assertTrue(reports.contains("TOP-15"))
    }

    @Test
    fun moexBacktest_255d_prodLike_asymmetricThreshold_0_2_5() = runBlocking {
        val raw = loadMoex15mRaw(PORTFOLIO_M15_LOOKBACK_DAYS)
        val report = runHypothesisReport(
            title = "255д MOEX · prod-like ${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()} ₽",
            points = prepareMoexPointsForProdLikeSim(raw),
        )
        println(report)
        java.io.File("/tmp/moex_threshold_255d_prodlike.txt").writeText(report)
        assertTrue(report.contains("BEST:"))
    }

    @Test
    fun moexBacktest_3y_prodLike_asymmetricThreshold_0_2_5() = runBlocking {
        val raw = loadMoex15mRaw(BACKTEST_3Y_LOOKBACK_DAYS)
        val report = runHypothesisReport(
            title = "3 года MOEX · prod-like ${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()} ₽",
            points = prepareMoexPointsForProdLikeSim(raw),
        )
        println(report)
        java.io.File("/tmp/moex_threshold_3y_prodlike.txt").writeText(report)
        assertTrue(report.contains("BEST:"))
    }

    private suspend fun loadMoex15mRaw(lookbackDays: Long): List<DataPoint> {
        val today = LocalDate.now(zone)
        val from = today.minusDays(lookbackDays)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue("Нет MOEX 15м ($from…$till)", entities.isNotEmpty())
        return entities.map { it.toDataPoint() }
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
    ): List<ThresholdSweepCell> {
        val cells = mutableListOf<ThresholdSweepCell>()
        for (entry in steps) {
            for (exit in steps) {
                if (exit >= entry) continue
                val m = buildProdLikeStrategySimMetrics(
                    points = points,
                    thresholds = DynamicThresholds(entry, exit, null),
                ) ?: continue
                cells += ThresholdSweepCell(entry, exit, m)
            }
        }
        return cells
    }

    private fun runHypothesisReport(title: String, points: List<DataPoint>): String {
        val steps = thresholdSweepSteps()
        val cells = runThresholdGapSweep(points, steps)
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
            appendLine(
                "Prod-like: ${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()} ₽, " +
                    "${DEFAULT_STRATEGY_TEST_CAPITAL_USAGE_PERCENT.toInt()}%, ×7 лоты, " +
                    "комис. 0.04%, slip ${DEFAULT_STRATEGY_TEST_SLIPPAGE_SPREAD_PTS}п, rolling Z+guard"
            )
            appendLine("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров)")
            appendLine("Сетка 0.2…2.5 шаг 0.1 → ${cells.size} комб., прибыльных $profitable (${fmt1(100.0 * profitable / cells.size)}%)")
            appendLine()
            appendLine("BEST: вход ±${fmt(best.entry)} / выход ±${fmt(best.exit)} Δ=${fmt(best.gap)}")
            appendLine(
                "  PnL ${fmt(best.pnl)} ₽, ret ${fmt1(best.metrics.totalReturnPercent)}%, " +
                    "сделок ${best.trades}, DD ${fmt(best.maxDd)} ₽, WR ${fmt1(best.metrics.winRate)}%"
            )
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
            appendLine("TOP 10:")
            cells.sortedByDescending { it.pnl }.take(10).forEach { c ->
                val m = c.metrics
                appendLine(
                    "  ±${fmt(c.entry)}/±${fmt(c.exit)} Δ=${fmt(c.gap)} → ${fmt(c.pnl)} ₽ " +
                        "(${c.trades} сд., DD ${fmt(c.maxDd)}, WR ${fmt1(m.winRate)}%, ret ${fmt1(m.totalReturnPercent)}%)"
                )
            }
        }
    }

    private fun topPairsFullReport(title: String, points: List<DataPoint>, steps: List<Double>): String {
        val cells = runThresholdGapSweep(points, steps).sortedByDescending { it.pnl }.take(15)
        return buildString {
            appendLine("--- $title ---")
            appendLine("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров)")
            appendLine(
                String.format(
                    Locale.US,
                    "%5s %5s %4s %12s %6s %10s %8s %10s %6s %8s",
                    "вход", "выход", "Δ", "PnL ₽", "сделок", "max DD ₽", "WR %", "комис. ₽", "ret %", "PF",
                )
            )
            cells.forEach { c ->
                val m = c.metrics
                appendLine(
                    String.format(
                        Locale.US,
                        "%5.1f %5.1f %4.1f %12.0f %6d %10.0f %8.1f %10.0f %6.1f %8.2f",
                        c.entry,
                        c.exit,
                        c.gap,
                        m.totalPnlRubApprox,
                        m.closedTrades.size,
                        m.maxDrawdownRubApprox,
                        m.winRate,
                        m.totalCommissionRub,
                        m.totalReturnPercent,
                        m.profitFactor ?: 0.0,
                    )
                )
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
    }
}
