package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Бэктест на **нативных 10м** барах MOEX ISS (без 10→15м агрегации).
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.Moex10mBacktestTest.moexBacktest_255d_native10m_threshold07_05`
 */
class Moex10mBacktestTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private companion object {
        val THRESH_08_07 = DynamicThresholds(0.8, 0.7, null)
        val THRESH_07_05 = DynamicThresholds(0.7, 0.5, null)
        const val NOTIONAL = 50_000.0
        const val NOTIONAL_100K = 100_000.0
        const val LEVERAGE = 1.0
        const val LEVERAGE_X7 = 7.0
        const val COMMISSION = 0.04
    }

    @Test
    fun moexBacktest_255d_compare_15m_vs_native10m() = runBlocking {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()

        val entities15 = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        val entities10 = fetchPortfolio10mSpreadEntitiesChunked(from, till)
        assertTrue("Нет 15м данных MOEX", entities15.isNotEmpty())
        assertTrue("Нет 10м данных MOEX", entities10.isNotEmpty())

        val points15 = applyZScoresDefault(entities15.map { it.toDataPoint() })
        val points10 = applyZScoresFor10mBars(entities10.map { it.toDataPoint() })

        val metrics15 = buildZStrategyPortfolioMetrics(
            points = points15,
            thresholds = THRESH_08_07,
            notionalRub = NOTIONAL,
            leverage = LEVERAGE,
            commissionPercentPerSide = COMMISSION,
            periodDescription = "15m MOEX ISS",
            compoundReturns = false,
            exitMode = ZStrategyExitMode.FixedThreshold,
        )
        val metrics10 = buildZStrategyPortfolioMetrics(
            points = points10,
            thresholds = THRESH_08_07,
            notionalRub = NOTIONAL,
            leverage = LEVERAGE,
            commissionPercentPerSide = COMMISSION,
            periodDescription = "10m native MOEX ISS",
            compoundReturns = false,
            exitMode = ZStrategyExitMode.FixedThreshold,
        )
        checkNotNull(metrics15)
        checkNotNull(metrics10)

        println("=== MOEX 255д: 15м (10→15 agg) vs нативные 10м · пороги 0.8/0.7 ===")
        println("15м: ${points15.first().tradeDate} … ${points15.last().tradeDate} · ${points15.size} баров")
        println("10м: ${points10.first().tradeDate} … ${points10.last().tradeDate} · ${points10.size} баров")
        println(
            String.format(
                Locale.US,
                "%-8s | %5s | %12s | %10s | %10s",
                "ряд",
                "сделок",
                "PnL чист. ₽",
                "max DD ₽",
                "комис. ₽",
            )
        )
        println("-".repeat(58))
        printRow("15м", metrics15)
        printRow("10м", metrics10)
        println()
        println(
            "Z-свечи на графике: только тело (open/close между барами), без 10м фитилей внутри 15м слота."
        )
        println(
            "Тени на 15м графике возникали из spread-OHLC 10м внутри 15м; на 10м ряду фитили = только шаг Z между соседними 10м барами."
        )

        assertTrue(points10.size > points15.size)
    }

    @Test
    fun moexBacktest_255d_thresholdGrid_15m_vs_10m() = runBlocking {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities15 = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        val entities10 = fetchPortfolio10mSpreadEntitiesChunked(from, till)
        val points15 = applyZScoresDefault(entities15.map { it.toDataPoint() })
        val points10 = applyZScoresFor10mBars(entities10.map { it.toDataPoint() })

        val thresholds = listOf(
            "0.8/0.7" to DynamicThresholds(0.8, 0.7, null),
            "0.8/0.5" to DynamicThresholds(0.8, 0.5, null),
            "0.7/0.5" to DynamicThresholds(0.7, 0.5, null),
        )
        println("=== MOEX 255д · сетка порогов · 50k x1 · комис 0.04%/стор ===")
        println("15м: ${points15.size} баров · 10м: ${points10.size} баров")
        println(String.format(Locale.US, "%-8s | %-8s | %5s | %12s | %10s | %10s", "ряд", "пороги", "сделок", "PnL ₽", "maxDD ₽", "комис ₽"))
        println("-".repeat(70))
        for ((label, th) in thresholds) {
            for ((rowLabel, pts) in listOf("15м" to points15, "10м" to points10)) {
                val m = checkNotNull(
                    buildZStrategyPortfolioMetrics(
                        points = pts,
                        thresholds = th,
                        notionalRub = NOTIONAL,
                        leverage = LEVERAGE,
                        commissionPercentPerSide = COMMISSION,
                        periodDescription = "$rowLabel $label",
                        compoundReturns = false,
                        exitMode = ZStrategyExitMode.FixedThreshold,
                    )
                )
                println(
                    String.format(
                        Locale.US,
                        "%-8s | %-8s | %5d | %12.0f | %10.0f | %10.0f",
                        rowLabel,
                        label,
                        m.closedTrades.size,
                        m.totalPnlRubApprox,
                        m.maxDrawdownRubApprox,
                        m.totalCommissionRub,
                    )
                )
            }
        }
    }

    /**
     * Параметры как «Тест страт.»: 100k, x7, комиссия 0.04%, без капитализации.
     * Сравнение 0.8/0.7 vs 0.7/0.5 на 15м и нативных 10м.
     */
    @Test
    fun moexBacktest_255d_strategyTestParity_100k_x7() = runBlocking {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities15 = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        val entities10 = fetchPortfolio10mSpreadEntitiesChunked(from, till)
        val points15 = applyZScoresDefault(entities15.map { it.toDataPoint() })
        val points10 = applyZScoresFor10mBars(entities10.map { it.toDataPoint() })

        data class Row(
            val series: String,
            val thresholds: String,
            val notional: Double,
            val leverage: Double,
            val metrics: PortfolioMetrics,
        )

        fun run(
            series: String,
            points: List<DataPoint>,
            th: DynamicThresholds,
            label: String,
            notional: Double,
            leverage: Double,
        ): Row {
            val m = checkNotNull(
                buildZStrategyPortfolioMetrics(
                    points = points,
                    thresholds = th,
                    notionalRub = notional,
                    leverage = leverage,
                    commissionPercentPerSide = COMMISSION,
                    periodDescription = "$series $label ${notional.toInt()}k x$leverage",
                    compoundReturns = false,
                    exitMode = ZStrategyExitMode.FixedThreshold,
                )
            )
            return Row(series, label, notional, leverage, m)
        }

        val rows = listOf(
            run("15м", points15, THRESH_08_07, "0.8/0.7", NOTIONAL, LEVERAGE),
            run("15м", points15, THRESH_07_05, "0.7/0.5", NOTIONAL, LEVERAGE),
            run("10м", points10, THRESH_08_07, "0.8/0.7", NOTIONAL, LEVERAGE),
            run("10м", points10, THRESH_07_05, "0.7/0.5", NOTIONAL, LEVERAGE),
            run("15м", points15, THRESH_08_07, "0.8/0.7", NOTIONAL_100K, LEVERAGE_X7),
            run("15м", points15, THRESH_07_05, "0.7/0.5", NOTIONAL_100K, LEVERAGE_X7),
            run("10м", points10, THRESH_08_07, "0.8/0.7", NOTIONAL_100K, LEVERAGE_X7),
            run("10м", points10, THRESH_07_05, "0.7/0.5", NOTIONAL_100K, LEVERAGE_X7),
            run("15м", points15, DynamicThresholds(1.3, 1.2, null), "1.3/1.2", NOTIONAL_100K, LEVERAGE_X7),
            run("15м", points15, DynamicThresholds(0.8, 0.5, null), "0.8/0.5", NOTIONAL_100K, LEVERAGE_X7),
        )

        println("=== MOEX 255д · parity «Тест страт.» · комис ${COMMISSION}%/стор ===")
        println("15м: ${points15.size} баров · 10м: ${points10.size} баров")
        println(
            String.format(
                Locale.US,
                "%-4s | %-8s | %8s | %5s | %12s | %10s | %10s | %10s",
                "ряд",
                "пороги",
                "номинал",
                "сдел",
                "PnL ₽",
                "maxDD ₽",
                "комис ₽",
                "оверн. ₽",
            )
        )
        println("-".repeat(88))
        for (r in rows) {
            val m = r.metrics
            println(
                String.format(
                    Locale.US,
                    "%-4s | %-8s | %5dk x%-1.0f | %5d | %12.0f | %10.0f | %10.0f | %10.0f",
                    r.series,
                    r.thresholds,
                    (r.notional / 1000).toInt(),
                    r.leverage,
                    m.closedTrades.size,
                    m.totalPnlRubApprox,
                    m.maxDrawdownRubApprox,
                    m.totalCommissionRub,
                    m.totalOvernightRub,
                )
            )
        }
        val st15_07 = rows[4].metrics
        val st15_05 = rows[5].metrics
        val st10_07 = rows[6].metrics
        val st10_05 = rows[7].metrics
        val st15_dyn = rows[8].metrics
        val st15_085 = rows[9].metrics
        println("---")
        println(
            "15м 100k x7: 0.7/0.5 vs 0.8/0.7 → PnL ×${
                String.format(Locale.US, "%.2f", st15_05.totalPnlRubApprox / st15_07.totalPnlRubApprox)
            } (Δ ${(st15_05.totalPnlRubApprox - st15_07.totalPnlRubApprox).roundToInt()} ₽)"
        )
        println(
            "10м 100k x7: 0.7/0.5 vs 0.8/0.7 → PnL ×${
                String.format(Locale.US, "%.2f", st10_05.totalPnlRubApprox / st10_07.totalPnlRubApprox)
            } (Δ ${(st10_05.totalPnlRubApprox - st10_07.totalPnlRubApprox).roundToInt()} ₽)"
        )
        println(
            "50k x1 → 100k x7 (15м 0.7/0.5): ×${
                String.format(Locale.US, "%.1f", st15_05.totalPnlRubApprox / rows[1].metrics.totalPnlRubApprox)
            } (не ровно 14× из‑за overnight и комиссии)"
        )
        if (st15_dyn.totalPnlRubApprox > 0) {
            println(
                "15м 100k x7: 0.7/0.5 vs дефолт 1.3/1.2 → PnL ×${
                    String.format(Locale.US, "%.2f", st15_05.totalPnlRubApprox / st15_dyn.totalPnlRubApprox)
                }"
            )
        }
        println(
            "15м 100k x7: 0.7/0.5 vs 0.8/0.5 → PnL ×${
                String.format(Locale.US, "%.2f", st15_05.totalPnlRubApprox / st15_085.totalPnlRubApprox)
            }"
        )
    }

    @Test
    fun moexBacktest_255d_native10m_threshold07_05() = runBlocking {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()

        val entities10 = fetchPortfolio10mSpreadEntitiesChunked(from, till)
        assertTrue("Нет 10м данных MOEX", entities10.isNotEmpty())

        val points10 = applyZScoresFor10mBars(entities10.map { it.toDataPoint() })
        val metrics = buildZStrategyPortfolioMetrics(
            points = points10,
            thresholds = THRESH_07_05,
            notionalRub = NOTIONAL,
            leverage = LEVERAGE,
            commissionPercentPerSide = COMMISSION,
            periodDescription = "10m native · 0.7/0.5",
            compoundReturns = false,
            exitMode = ZStrategyExitMode.FixedThreshold,
        )
        checkNotNull(metrics)

        println("=== MOEX 255д · нативные 10м · вход 0.7 / выход 0.5 ===")
        println("Ряд: ${points10.first().tradeDate} … ${points10.last().tradeDate} · ${points10.size} баров 10м")
        println("Номинал: ${NOTIONAL.toInt()} ₽ · плечо x${LEVERAGE.toInt()} · комиссия ${COMMISSION}%/сторона")
        printDetailedMetrics(metrics)
    }

    private fun printDetailedMetrics(metrics: PortfolioMetrics) {
        println(
            String.format(
                Locale.US,
                "Сделок: %d · PnL чистый: %.0f ₽ · max DD: %.0f ₽ · комиссия: %.0f ₽ · overnight: %.0f ₽",
                metrics.closedTrades.size,
                metrics.totalPnlRubApprox,
                metrics.maxDrawdownRubApprox,
                metrics.totalCommissionRub,
                metrics.totalOvernightRub,
            )
        )
        println(
            String.format(
                Locale.US,
                "Win rate: %.1f%% · avg PnL/сделку: %.0f ₽",
                metrics.winRate,
                if (metrics.closedTrades.isEmpty()) 0.0
                else metrics.totalPnlRubApprox / metrics.closedTrades.size,
            )
        )
        metrics.closedTrades.takeLast(5).forEach { t ->
            println("  ${t.entryDate} → ${t.exitDate} · PnL ${t.pnlRubApprox.roundToInt()} ₽")
        }
        if (metrics.closedTrades.size > 5) {
            println("  … ещё ${metrics.closedTrades.size - 5} сделок")
        }
    }

    private fun printRow(label: String, metrics: PortfolioMetrics) {
        println(
            String.format(
                Locale.US,
                "%-8s | %5d | %12.0f | %10.0f | %10.0f",
                label,
                metrics.closedTrades.size,
                metrics.totalPnlRubApprox.roundToInt().toDouble(),
                metrics.maxDrawdownRubApprox.roundToInt().toDouble(),
                metrics.totalCommissionRub.roundToInt().toDouble(),
            )
        )
    }
}
