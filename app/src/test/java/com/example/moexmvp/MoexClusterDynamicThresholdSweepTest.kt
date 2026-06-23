package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Гипотеза: 4 порога, динамически подстраиваемые под кластер Z (rolling percentiles / IQR),
 * дают больше PnL, чем статические best.
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexClusterDynamicThresholdSweepTest`
 */
class MoexClusterDynamicThresholdSweepTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private data class SweepCell(
        val label: String,
        val pnl: Double,
        val trades: Int,
        val maxDd: Double,
        val totalReturnPercent: Double,
        val winRate: Double,
    )

    @Test
    fun moexBacktest_255d_clusterDynamicThresholdSweep_prodLike() = runBlocking {
        val raw = loadMoex255()
        val points = prepareMoexPointsForProdLikeSim(raw)

        val baselines = listOf(
            "static 0.7/0.5" to buildProdLikeStrategySimMetrics(
                points, DynamicThresholds(0.7, 0.5, null),
            )!!,
            "static 1.8/1.3" to buildProdLikeStrategySimMetrics(
                points, DynamicThresholds(1.8, 1.3, null),
            )!!,
            "static four 1.6/1.1/1.6/1.4" to buildProdLikeStrategySimMetricsFour(
                points, ZStrategyFourThresholds(1.6, 1.1, 1.6, 1.4),
            )!!,
        )

        val lookbacks = listOf(65, 130, 260, 520)
        val entryPcts = listOf(80, 85, 90)
        val exitPcts = listOf(50, 55, 60)

        val pctCells = mutableListOf<SweepCell>()
        for (lb in lookbacks) {
            for (ePct in entryPcts) {
                for (xPct in exitPcts) {
                    val config = ZClusterThresholdConfig(
                        lookbackBars = lb,
                        entryPctShort = ePct,
                        exitPctShort = xPct,
                        entryPctLong = 100 - ePct,
                        exitPctLong = 100 - xPct,
                    )
                    val series = buildClusterDynamicFourThresholdSeries(points, config)
                    val m = buildProdLikeStrategySimMetricsCluster(
                        points = points,
                        fourSeries = series,
                        periodDescription = "pct lb=$lb e=$ePct x=$xPct",
                    ) ?: continue
                    pctCells += SweepCell(
                        label = "pct lb=$lb e$ePct x$xPct",
                        pnl = m.totalPnlRubApprox,
                        trades = m.closedTrades.size,
                        maxDd = m.maxDrawdownRubApprox,
                        totalReturnPercent = m.totalReturnPercent,
                        winRate = m.winRate,
                    )
                }
            }
        }

        val iqrCells = mutableListOf<SweepCell>()
        val entryMults = listOf(0.8, 1.0, 1.2, 1.5)
        val exitMults = listOf(0.3, 0.5, 0.7)
        for (lb in lookbacks) {
            for (eMult in entryMults) {
                for (xMult in exitMults) {
                    val config = ZClusterIqrConfig(
                        lookbackBars = lb,
                        entryIqrMult = eMult,
                        exitIqrMult = xMult,
                    )
                    val series = buildClusterIqrFourThresholdSeries(points, config)
                    val m = buildProdLikeStrategySimMetricsCluster(
                        points = points,
                        fourSeries = series,
                        periodDescription = "iqr lb=$lb em=$eMult xm=$xMult",
                    ) ?: continue
                    iqrCells += SweepCell(
                        label = "iqr lb=$lb em=$eMult xm=$xMult",
                        pnl = m.totalPnlRubApprox,
                        trades = m.closedTrades.size,
                        maxDd = m.maxDrawdownRubApprox,
                        totalReturnPercent = m.totalReturnPercent,
                        winRate = m.winRate,
                    )
                }
            }
        }

        val allCells = pctCells + iqrCells
        assertTrue(allCells.isNotEmpty())
        val best = allCells.maxByOrNull { it.pnl }!!
        val bestStaticFour = baselines[2].second.totalPnlRubApprox
        val bestStaticSym = baselines[1].second.totalPnlRubApprox

        val report = buildString {
            appendLine("=== 255д prod-like: динамические порога по кластеру Z ===")
            appendLine("Счёт ${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()}k, 80%, ×7, slip 0.05п, rolling Z+guard")
            appendLine("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров)")
            appendLine()
            appendLine("BASELINE static:")
            baselines.forEach { (name, m) ->
                appendLine(
                    "  $name: PnL ${fmt(m.totalPnlRubApprox)} ₽, ${m.closedTrades.size} сд., " +
                        "ret ${fmt1(m.totalReturnPercent)}%, DD ${fmt(m.maxDrawdownRubApprox)}"
                )
            }
            appendLine()
            appendLine("CLUSTER BEST: ${best.label}")
            appendLine(
                "  PnL ${fmt(best.pnl)} ₽ (Δ vs static four: ${fmt(best.pnl - bestStaticFour)}, " +
                    "Δ vs 1.8/1.3: ${fmt(best.pnl - bestStaticSym)}), " +
                    "ret ${fmt1(best.totalReturnPercent)}%, ${best.trades} сд., DD ${fmt(best.maxDd)}, WR ${fmt1(best.winRate)}%"
            )
            val hypothesis = best.pnl > bestStaticFour + 50.0
            appendLine(
                "Гипотеза (cluster > static four best): " +
                    if (hypothesis) "ПОДТВЕРЖДЕНА (+${fmt(best.pnl - bestStaticFour)} ₽)" else "НЕ ПОДТВЕРЖДЕНА"
            )
            appendLine()
            appendLine("TOP 15 percentile-cluster:")
            appendLine(topTableHeader())
            pctCells.sortedByDescending { it.pnl }.take(15).forEach { appendLine(topTableRow(it)) }
            appendLine()
            appendLine("TOP 10 IQR-cluster:")
            appendLine(topTableHeader())
            iqrCells.sortedByDescending { it.pnl }.take(10).forEach { appendLine(topTableRow(it)) }
        }
        println(report)
        java.io.File("/tmp/moex_cluster_dynamic_sweep.txt").writeText(report)
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

    private fun topTableHeader(): String =
        String.format(Locale.US, "%-28s %12s %6s %10s %6s %6s", "config", "PnL ₽", "сделок", "DD ₽", "ret%", "WR%")

    private fun topTableRow(c: SweepCell): String =
        String.format(
            Locale.US,
            "%-28s %12.0f %6d %10.0f %6.1f %6.1f",
            c.label, c.pnl, c.trades, c.maxDd, c.totalReturnPercent, c.winRate,
        )

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
}
