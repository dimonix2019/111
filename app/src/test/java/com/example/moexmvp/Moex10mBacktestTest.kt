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
        const val LEVERAGE = 1.0
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
