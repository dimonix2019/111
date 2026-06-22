package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Гипотеза: 4 независимых порога (L-in, L-out, S-in, S-out) дают больше PnL, чем симметричная пара.
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexFourThresholdSweepTest`
 */
class MoexFourThresholdSweepTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private data class FourSweepCell(
        val four: ZStrategyFourThresholds,
        val pnl: Double,
        val trades: Int,
        val maxDd: Double,
        val totalReturnPercent: Double,
        val winRate: Double,
    )

    @Test
    fun moexBacktest_255d_fourThresholdSweep_prodLike() = runBlocking {
        val raw = loadMoex255()
        val points = prepareMoexPointsForProdLikeSim(raw)

        val symBest = buildProdLikeStrategySimMetrics(
            points = points,
            thresholds = DynamicThresholds(1.8, 1.3, null),
        )!!
        val symBaseline = buildProdLikeStrategySimMetrics(
            points = points,
            thresholds = DynamicThresholds(0.7, 0.5, null),
        )!!

        val coarseSteps = thresholdSteps(min = 0.2, max = 2.4, step = 0.2)
        val coarseCells = runFourSweep(points, coarseSteps)
        assertTrue(coarseCells.isNotEmpty())

        val topCoarse = coarseCells.sortedByDescending { it.pnl }.take(8)
        val refineSteps = buildRefineSteps(topCoarse.map { it.four })
        val refineCells = runFourSweep(points, refineSteps)
        val allCells = (coarseCells + refineCells).distinctBy { cellKey(it.four) }
        val best = allCells.maxByOrNull { it.pnl }!!

        val report = buildString {
            appendLine("=== 255д prod-like: 4 независимых порога vs симметричная пара ===")
            appendLine("Счёт ${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()}k, 80%, ×7, slip 0.05п, Z+guard")
            appendLine("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров)")
            appendLine("Грубая сетка: шаг 0.2 → ${coarseCells.size} комб.; уточнение ±0.1 вокруг TOP-8")
            appendLine()
            appendLine("Симметричный BEST (1.8/1.3): PnL ${fmt(symBest.totalPnlRubApprox)} ₽, " +
                "${symBest.closedTrades.size} сд., ret ${fmt1(symBest.totalReturnPercent)}%")
            appendLine("Симметричный 0.7/0.5: PnL ${fmt(symBaseline.totalPnlRubApprox)} ₽")
            appendLine()
            appendLine("FOUR BEST: L-in ${fmt(best.four.entryLong)} L-out ${fmt(best.four.exitLong)} · " +
                "S-in ${fmt(best.four.entryShort)} S-out ${fmt(best.four.exitShort)}")
            appendLine(
                "  PnL ${fmt(best.pnl)} ₽ (Δ vs sym 1.8/1.3: ${fmt(best.pnl - symBest.totalPnlRubApprox)}), " +
                    "ret ${fmt1(best.totalReturnPercent)}%, ${best.trades} сд., DD ${fmt(best.maxDd)}, " +
                    "WR ${fmt1(best.winRate)}%"
            )
            appendLine()
            val hypothesis = best.pnl > symBest.totalPnlRubApprox + 50.0
            appendLine(
                "Гипотеза (4 порога > симметричный best): " +
                    if (hypothesis) "ПОДТВЕРЖДЕНА (+${fmt(best.pnl - symBest.totalPnlRubApprox)} ₽)" else "НЕ ПОДТВЕРЖДЕНА"
            )
            appendLine()
            appendLine("TOP 15 четвёрок:")
            appendLine(
                String.format(
                    Locale.US,
                    "%5s %5s %5s %5s %12s %6s %10s %6s %6s",
                    "L-in", "L-out", "S-in", "S-out", "PnL ₽", "сделок", "DD ₽", "ret%", "WR%",
                )
            )
            allCells.sortedByDescending { it.pnl }.take(15).forEach { c ->
                val f = c.four
                appendLine(
                    String.format(
                        Locale.US,
                        "%5.1f %5.1f %5.1f %5.1f %12.0f %6d %10.0f %6.1f %6.1f",
                        f.entryLong, f.exitLong, f.entryShort, f.exitShort,
                        c.pnl, c.trades, c.maxDd, c.totalReturnPercent, c.winRate,
                    )
                )
            }
            appendLine()
            appendLine("TOP 5 vs симметричная 1.8/1.3 (оба направления одинаково):")
            allCells.filter {
                near(it.four.entryLong, 1.8) && near(it.four.exitLong, 1.3) &&
                    near(it.four.entryShort, 1.8) && near(it.four.exitShort, 1.3)
            }.maxByOrNull { it.pnl }?.let {
                appendLine("  exact 1.8/1.3 ×2: ${fmt(it.pnl)} ₽")
            }
        }
        println(report)
        java.io.File("/tmp/moex_four_threshold_sweep.txt").writeText(report)
        assertTrue(best.pnl > 0.0)
    }

    private suspend fun loadMoex255(): List<DataPoint> {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue(entities.isNotEmpty())
        return entities.map { it.toDataPoint() }
    }

    private fun thresholdSteps(min: Double, max: Double, step: Double): List<Double> {
        val steps = mutableListOf<Double>()
        var v = min
        while (v <= max + 1e-9) {
            steps += (v * 10).roundToInt() / 10.0
            v += step
        }
        return steps
    }

    private fun buildRefineSteps(seeds: List<ZStrategyFourThresholds>): List<Double> {
        val set = linkedSetOf<Double>()
        for (s in seeds) {
            for (v in listOf(s.entryLong, s.exitLong, s.entryShort, s.exitShort)) {
                for (d in listOf(-0.1, 0.0, 0.1)) {
                    val x = ((v + d) * 10).roundToInt() / 10.0
                    if (x >= 0.2 && x <= 2.5) set += x
                }
            }
        }
        return set.sorted()
    }

    private fun runFourSweep(points: List<DataPoint>, steps: List<Double>): List<FourSweepCell> {
        val cells = mutableListOf<FourSweepCell>()
        for (entryLong in steps) {
            for (exitLong in steps) {
                if (exitLong >= entryLong) continue
                for (entryShort in steps) {
                    for (exitShort in steps) {
                        if (exitShort >= entryShort) continue
                        val four = ZStrategyFourThresholds(entryLong, exitLong, entryShort, exitShort)
                        val m = buildProdLikeStrategySimMetricsFour(points, four) ?: continue
                        cells += FourSweepCell(
                            four = four,
                            pnl = m.totalPnlRubApprox,
                            trades = m.closedTrades.size,
                            maxDd = m.maxDrawdownRubApprox,
                            totalReturnPercent = m.totalReturnPercent,
                            winRate = m.winRate,
                        )
                    }
                }
            }
        }
        return cells
    }

    private fun cellKey(f: ZStrategyFourThresholds): String =
        "${f.entryLong}|${f.exitLong}|${f.entryShort}|${f.exitShort}"

    private fun near(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.011

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
}
