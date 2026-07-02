package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Три гибрида после неудачи full-dynamic cluster:
 * 1) static entry + dynamic exit
 * 2) regime switch (IQR low → no trade)
 * 3) expansion phase (max|Z| recent >> baseline)
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexClusterHybridSweepTest`
 */
class MoexClusterHybridSweepTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val staticFourBest = ZStrategyFourThresholds(1.6, 1.1, 1.6, 1.4)
    private val staticSymBest = DynamicThresholds(1.8, 1.3, null)

    private data class SweepCell(
        val label: String,
        val pnl: Double,
        val trades: Int,
        val maxDd: Double,
        val totalReturnPercent: Double,
        val winRate: Double,
    )

    @Test
    fun moexBacktest_255d_clusterHybridSweep_prodLike() = runBlocking {
        val raw = loadMoex255()
        val points = prepareMoexPointsForProdLikeSim(raw)

        val baselineFour = buildProdLikeStrategySimMetricsFour(points, staticFourBest)!!
        val baselineSym = buildProdLikeStrategySimMetrics(points, staticSymBest)!!
        val baselineRef = baselineFour.totalPnlRubApprox

        val hybrid1 = sweepHybridStaticEntryDynamicExit(points)
        val hybrid2 = sweepRegimeSwitch(points)
        val hybrid3 = sweepExpansionRegime(points)

        val allCells = hybrid1 + hybrid2 + hybrid3
        assertTrue(allCells.isNotEmpty())
        val bestOverall = allCells.maxByOrNull { it.pnl }!!
        val bestH1 = hybrid1.maxByOrNull { it.pnl }!!
        val bestH2 = hybrid2.maxByOrNull { it.pnl }!!
        val bestH3 = hybrid3.maxByOrNull { it.pnl }!!

        val report = buildString {
            appendLine("=== 255д prod-like: 3 гибрида cluster × static ===")
            appendLine("Счёт ${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()}k, 80%, ×7, slip 0.05п, Z+guard")
            appendLine("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров)")
            appendLine()
            appendLine("BASELINE:")
            appendLine("  static four 1.6/1.1/1.6/1.4: ${fmt(baselineFour.totalPnlRubApprox)} ₽, ${baselineFour.closedTrades.size} сд.")
            appendLine("  static 1.8/1.3: ${fmt(baselineSym.totalPnlRubApprox)} ₽, ${baselineSym.closedTrades.size} сд.")
            appendLine()
            appendLine("BEST OVERALL: ${bestOverall.label}")
            appendLine(
                "  PnL ${fmt(bestOverall.pnl)} ₽ (Δ vs static four: ${fmt(bestOverall.pnl - baselineRef)}), " +
                    "${bestOverall.trades} сд., DD ${fmt(bestOverall.maxDd)}, WR ${fmt1(bestOverall.winRate)}%"
            )
            appendLine()
            listOf(
                "1 static entry + dyn exit" to bestH1,
                "2 regime IQR switch" to bestH2,
                "3 expansion max|Z|" to bestH3,
            ).forEach { (title, best) ->
                val ok = best.pnl > baselineRef + 50.0
                appendLine(
                    "$title BEST: ${best.label} → ${fmt(best.pnl)} ₽ (Δ ${fmt(best.pnl - baselineRef)}) " +
                        if (ok) "ПОДТВЕРЖДЁН" else "нет"
                )
            }
            appendLine()
            appendLine("TOP 10 hybrid-1 (static entry + dyn exit):")
            append(topTable(hybrid1.sortedByDescending { it.pnl }.take(10)))
            appendLine()
            appendLine("TOP 10 hybrid-2 (regime IQR switch):")
            append(topTable(hybrid2.sortedByDescending { it.pnl }.take(10)))
            appendLine()
            appendLine("TOP 10 hybrid-3 (expansion max|Z|):")
            append(topTable(hybrid3.sortedByDescending { it.pnl }.take(10)))
        }
        println(report)
        java.io.File("/tmp/moex_cluster_hybrid_sweep.txt").writeText(report)
        assertTrue(bestOverall.pnl > 0.0)
    }

    private fun sweepHybridStaticEntryDynamicExit(points: List<DataPoint>): List<SweepCell> {
        val cells = mutableListOf<SweepCell>()
        val staticEntries = listOf(
            1.6 to 1.6,
            1.8 to 1.8,
            1.6 to 1.8,
        )
        val lookbacks = listOf(130, 260, 520)
        val exitPcts = listOf(50, 55, 60)
        for ((eLong, eShort) in staticEntries) {
            for (lb in lookbacks) {
                for (xPct in exitPcts) {
                    val config = ZHybridStaticEntryConfig(
                        staticEntryLong = eLong,
                        staticEntryShort = eShort,
                        exitCluster = ZClusterThresholdConfig(
                            lookbackBars = lb,
                            entryPctShort = 85,
                            exitPctShort = xPct,
                            entryPctLong = 15,
                            exitPctLong = 100 - xPct,
                            fallback = staticFourBest,
                        ),
                    )
                    val series = buildHybridStaticEntryDynamicExitSeries(points, config)
                    val m = buildProdLikeStrategySimMetricsCluster(
                        points, series, periodDescription = "h1 e=$eLong/$eShort lb=$lb x$xPct",
                    ) ?: continue
                    cells += SweepCell(
                        "h1 e${eLong}/${eShort} lb=$lb x$xPct",
                        m.totalPnlRubApprox, m.closedTrades.size, m.maxDrawdownRubApprox,
                        m.totalReturnPercent, m.winRate,
                    )
                }
            }
        }
        return cells
    }

    private fun sweepRegimeSwitch(points: List<DataPoint>): List<SweepCell> {
        val cells = mutableListOf<SweepCell>()
        val lookbacks = listOf(130, 260, 520)
        val minIqrs = listOf(0.3, 0.5, 0.7, 1.0, 1.3)
        val actives = listOf(
            staticFourBest,
            ZStrategyFourThresholds(1.8, 1.3, 1.8, 1.3),
            ZStrategyFourThresholds(1.6, 1.3, 1.6, 1.3),
        )
        for (lb in lookbacks) {
            for (minIqr in minIqrs) {
                for ((ai, active) in actives.withIndex()) {
                    val config = ZRegimeSwitchConfig(
                        lookbackBars = lb,
                        minIqrToTrade = minIqr,
                        activeFour = active,
                    )
                    val series = buildRegimeSwitchFourThresholdSeries(points, config)
                    val m = buildProdLikeStrategySimMetricsCluster(
                        points, series, periodDescription = "h2 lb=$lb iqr=$minIqr a=$ai",
                    ) ?: continue
                    cells += SweepCell(
                        "h2 lb=$lb iqr≥$minIqr act=$ai",
                        m.totalPnlRubApprox, m.closedTrades.size, m.maxDrawdownRubApprox,
                        m.totalReturnPercent, m.winRate,
                    )
                }
            }
        }
        return cells
    }

    private fun sweepExpansionRegime(points: List<DataPoint>): List<SweepCell> {
        val cells = mutableListOf<SweepCell>()
        val recentBars = listOf(65, 130, 260)
        val baselineBars = listOf(260, 520)
        val ratios = listOf(1.2, 1.5, 1.8, 2.0)
        val actives = listOf(staticFourBest, ZStrategyFourThresholds(1.8, 1.3, 1.8, 1.3))
        for (recent in recentBars) {
            for (baseline in baselineBars) {
                if (recent >= baseline) continue
                for (ratio in ratios) {
                    for ((ai, active) in actives.withIndex()) {
                        val config = ZExpansionRegimeConfig(
                            recentBars = recent,
                            baselineBars = baseline,
                            expansionRatio = ratio,
                            activeFour = active,
                        )
                        val series = buildExpansionRegimeFourThresholdSeries(points, config)
                        val m = buildProdLikeStrategySimMetricsCluster(
                            points, series, periodDescription = "h3 r=$recent b=$baseline x$ratio",
                        ) ?: continue
                        cells += SweepCell(
                            "h3 r=$recent b=$baseline ×$ratio act=$ai",
                            m.totalPnlRubApprox, m.closedTrades.size, m.maxDrawdownRubApprox,
                            m.totalReturnPercent, m.winRate,
                        )
                    }
                }
            }
        }
        return cells
    }

    private suspend fun loadMoex255(): List<DataPoint> {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue(entities.isNotEmpty())
        return entities.map { it.toDataPoint() }
    }

    private fun topTable(rows: List<SweepCell>): String = buildString {
        appendLine(String.format(Locale.US, "%-32s %12s %6s %10s %6s", "config", "PnL ₽", "сделок", "DD ₽", "WR%"))
        rows.forEach { c ->
            appendLine(
                String.format(
                    Locale.US, "%-32s %12.0f %6d %10.0f %6.1f",
                    c.label, c.pnl, c.trades, c.maxDd, c.winRate,
                )
            )
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
}
