package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Сравнение бэктеста: regime-adaptive (прогноз 10д) vs static 0.7/0.5 vs static 1.7/1.3.
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexZRegimeBacktestCompareTest`
 */
class MoexZRegimeBacktestCompareTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private data class ModeResult(
        val label: String,
        val metrics: PortfolioMetrics,
    )

    @Test
    fun moexBacktest_255d_regimeAdaptive_vs_staticThresholds() = runBlocking {
        val raw = loadMoex255()
        val points = prepareMoexPointsForProdLikeSim(raw)
        assertTrue(points.size >= 100)

        val cfg = ZRegimeAdaptiveThresholdConfig()
        val snapshot = resolveZRegimeAdaptiveSnapshot(points, cfg)!!
        val adaptive = buildProdLikeStrategySimMetricsRegimeAdaptive(points, cfg)!!
        val staticTight = buildProdLikeStrategySimMetrics(
            points = points,
            thresholds = DynamicThresholds(0.7, 0.5, null),
            periodDescription = "static 0.7/0.5",
        )!!
        val staticWide = buildProdLikeStrategySimMetrics(
            points = points,
            thresholds = DynamicThresholds(1.7, 1.3, null),
            periodDescription = "static 1.7/1.3",
        )!!

        val results = listOf(
            ModeResult("regime-adaptive (прогноз 10д)", adaptive),
            ModeResult("static 0.7/0.5", staticTight),
            ModeResult("static 1.7/1.3", staticWide),
        )

        val report = buildCompareReport(
            points = points,
            snapshot = snapshot,
            results = results,
        )
        println(report)
        java.io.File("/tmp/moex_z_regime_backtest_compare.txt").writeText(report)

        assertTrue(adaptive.closedTrades.isNotEmpty() || staticTight.closedTrades.isNotEmpty())
    }

    private fun buildCompareReport(
        points: List<DataPoint>,
        snapshot: ZRegimeAdaptiveSnapshot,
        results: List<ModeResult>,
    ): String = buildString {
        appendLine("=== 255д prod-like: regime-adaptive vs static пороги ===")
        appendLine("Счёт ${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()}k, 80%, ×7, slip 0.05п, Z+guard")
        appendLine("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров)")
        appendLine()
        appendLine(formatZRegimeAdaptiveSnapshot(snapshot))
        appendLine()
        appendLine("— Сравнение PnL —")
        appendLine(String.format(Locale.US, "%-28s %12s %6s %10s %8s %6s", "режим", "PnL ₽", "сделок", "DD ₽", "ret%", "WR%"))
        val bestPnl = results.maxOf { it.metrics.totalPnlRubApprox }
        for (r in results.sortedByDescending { it.metrics.totalPnlRubApprox }) {
            val m = r.metrics
            val marker = if (m.totalPnlRubApprox >= bestPnl - 0.01) " ◀ best" else ""
            appendLine(
                String.format(
                    Locale.US,
                    "%-28s %12.0f %6d %10.0f %8.2f %6.1f%s",
                    r.label,
                    m.totalPnlRubApprox,
                    m.closedTrades.size,
                    m.maxDrawdownRubApprox,
                    m.totalReturnPercent,
                    m.winRate,
                    marker,
                )
            )
        }
        appendLine()
        val adaptive = results.first { it.label.startsWith("regime") }.metrics
        val tight = results.first { it.label.contains("0.7") }.metrics
        val wide = results.first { it.label.contains("1.7") }.metrics
        appendLine("Δ vs static 0.7/0.5: ${fmt(adaptive.totalPnlRubApprox - tight.totalPnlRubApprox)} ₽")
        appendLine("Δ vs static 1.7/1.3: ${fmt(adaptive.totalPnlRubApprox - wide.totalPnlRubApprox)} ₽")
        appendLine()
        appendLine("— Распределение порогов regime-adaptive —")
        val series = buildZRegimeAdaptiveFourThresholdSeries(points, snapshot.config)!!
        val tightBars = series.count { it.entryLong < 1.0 }
        val wideBars = series.count { it.entryLong >= 1.0 }
        appendLine("Баров с узкими 0.7/0.5: $tightBars (${pct(tightBars, series.size)})")
        appendLine("Баров с широкими 1.7/1.3: $wideBars (${pct(wideBars, series.size)})")
        appendLine()
        appendLine("— Окна: прогноз vs факт (последние 8) —")
        for (plan in snapshot.windowPlans.takeLast(8)) {
            val pred = plan.predictedRegime?.let { zRegimeLabelRu(it) } ?: "warmup"
            val ok = plan.predictedRegime?.let { it == plan.actualRegime }
            appendLine(
                "${plan.windowId}: прогноз=$pred факт=${zRegimeLabelRu(plan.actualRegime)} " +
                    "пороги ${plan.appliedFour.entryLong}/${plan.appliedFour.exitLong} " +
                    if (ok == true) "✓" else if (ok == false) "✗" else "—",
            )
        }
    }

    private suspend fun loadMoex255(): List<DataPoint> {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue(entities.isNotEmpty())
        return entities.map { it.toDataPoint() }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%+.2f", v)

    private fun pct(part: Int, total: Int): String =
        if (total == 0) "0%" else String.format(Locale.US, "%.0f%%", part * 100.0 / total)
}
