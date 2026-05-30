package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Полная выборка MOEX 15м TATN/TATNP: пороговый выход vs трейлинг от пика Z.
 *
 * - `...MoexZPeakTrailingFullBacktestTest.fullSample_thresholdVsZPeakTrailing`
 * - `...MoexZPeakTrailingFullBacktestTest.fullSample_entrySweep_zPeakTrailing`
 * - `...MoexZPeakTrailingFullBacktestTest.fullSample_compromise_entryThreshold_trailExit`
 */
class MoexZPeakTrailingFullBacktestTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private companion object {
        const val SWEEP_STEP = 0.1
        const val SWEEP_MAX = 2.1
        val TRAIL_STEPS = listOf(0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40)
        val COMPROMISE_ENTRY_STEPS = listOf(0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80)
        val COMPROMISE_TRAIL_STEPS = listOf(0.25, 0.30, 0.35, 0.40)
    }

    @Test
    fun fullSample_thresholdVsZPeakTrailing() = runBlocking {
        val (today, points) = loadFullSamplePoints()
        val entryZ = 0.8
        val baseline = runMetrics(
            points, today, entryZ,
            exitMode = ZStrategyExitMode.FixedThreshold,
            zPeakTrailZ = fixedExitForEntry(entryZ)
        )!!
        val trailingRuns = TRAIL_STEPS.map { trail ->
            trail to runMetrics(points, today, entryZ, ZStrategyExitMode.ZPeakTrailing, trail)!!
        }

        println("=== MOEX полная выборка: порог vs трейлинг от пика Z ===")
        printSampleHeader(points, "Вход |Z| = $entryZ")
        println("режим          | сделок | win%  | PnL ₽      | max DD ₽  | PF    | комис. ₽  | оверн. ₽")
        println("---------------+--------+-------+------------+-----------+-------+-----------+----------")
        println(formatRow("порог ${fixedExitForEntry(entryZ)}", baseline))
        trailingRuns.forEach { (trail, m) -> println(formatRow("трейл $trail", m)) }

        val bestTrail = trailingRuns.maxByOrNull { it.second.totalPnlRubApprox }!!
        println()
        println(
            "Лучший трейл по PnL: ${bestTrail.first} → ${fmt(bestTrail.second.totalPnlRubApprox)} ₽ " +
                "(база порог: ${fmt(baseline.totalPnlRubApprox)} ₽, " +
                "Δ ${fmt(bestTrail.second.totalPnlRubApprox - baseline.totalPnlRubApprox)} ₽)"
        )
    }

    @Test
    fun fullSample_entrySweep_zPeakTrailing() = runBlocking {
        val (today, points) = loadFullSamplePoints()
        val entrySteps = thresholdSweepSteps()

        println("=== MOEX полная выборка: сетка входа + лучший трейл vs порог ===")
        printSampleHeader(points, "Вход 0.1…2.1 шаг 0.1 · выход порог = вход−0.1 · трейл ∈ ${TRAIL_STEPS}")
        println(
            "вход | выход | лучш.trail | сделок T | PnL трейл ₽ | maxDD T | сделок P | PnL порог ₽ | maxDD P | Δ PnL T−P"
        )
        println("-----+-------+------------+----------+-------------+---------+----------+-------------+---------+----------")

        val rows = mutableListOf<EntrySweepRow>()
        for (entry in entrySteps) {
            val fixedExit = fixedExitForEntry(entry)
            val fixed = runMetrics(points, today, entry, ZStrategyExitMode.FixedThreshold, fixedExit)!!
            val trailRuns = TRAIL_STEPS.map { trail ->
                trail to runMetrics(points, today, entry, ZStrategyExitMode.ZPeakTrailing, trail)!!
            }
            val best = trailRuns.maxByOrNull { it.second.totalPnlRubApprox }!!
            val row = EntrySweepRow(
                entry = entry,
                fixedExit = fixedExit,
                bestTrail = best.first,
                trailMetrics = best.second,
                fixedMetrics = fixed
            )
            rows += row
            println(row.formatLine())
        }

        val trailWins = rows.count { it.trailMetrics.totalPnlRubApprox > it.fixedMetrics.totalPnlRubApprox }
        val bestTrailOverall = rows.maxByOrNull { it.trailMetrics.totalPnlRubApprox }!!
        val bestFixedOverall = rows.maxByOrNull { it.fixedMetrics.totalPnlRubApprox }!!
        val bestDelta = rows.maxByOrNull { it.pnlDelta }!!

        println("---")
        println("Комбинаций входа: ${rows.size} × ${TRAIL_STEPS.size} трейлов + порог")
        println("Трейл лучше порога по PnL: $trailWins / ${rows.size}")
        println(
            "Лучший трейл: вход ${fmt1(bestTrailOverall.entry)} trail ${fmt2(bestTrailOverall.bestTrail)} → " +
                "${fmt(bestTrailOverall.trailMetrics.totalPnlRubApprox)} ₽, DD ${fmt(bestTrailOverall.trailMetrics.maxDrawdownRubApprox)}"
        )
        println(
            "Лучший порог: вход ${fmt1(bestFixedOverall.entry)} выход ${fmt1(bestFixedOverall.fixedExit)} → " +
                "${fmt(bestFixedOverall.fixedMetrics.totalPnlRubApprox)} ₽, DD ${fmt(bestFixedOverall.fixedMetrics.maxDrawdownRubApprox)}"
        )
        println(
            "Макс. выигрыш трейла vs порог: вход ${fmt1(bestDelta.entry)} trail ${fmt2(bestDelta.bestTrail)} " +
                "Δ ${fmt(bestDelta.pnlDelta)} ₽"
        )

        println("\nТОП-10 трейл (вход, trail, PnL):")
        rows.sortedByDescending { it.trailMetrics.totalPnlRubApprox }.take(10).forEach { r ->
            println(
                "  вход ${fmt1(r.entry)} trail ${fmt2(r.bestTrail)} → ${fmt(r.trailMetrics.totalPnlRubApprox)} ₽ " +
                    "(порог ${fmt(r.fixedMetrics.totalPnlRubApprox)} ₽, Δ ${fmt(r.pnlDelta)})"
            )
        }
    }

    @Test
    fun fullSample_compromise_entryThreshold_trailExit() = runBlocking {
        val (today, points) = loadFullSamplePoints()

        val refMaxPnl = runMetrics(points, today, 0.6, ZStrategyExitMode.FixedThreshold, 0.5)!!
        val refMinDdTrail = runMetrics(points, today, 0.8, ZStrategyExitMode.ZPeakTrailing, 0.40)!!
        val refClassic = runMetrics(points, today, 0.8, ZStrategyExitMode.FixedThreshold, 0.7)!!

        val grid = mutableListOf<CompromiseRow>()
        for (entry in COMPROMISE_ENTRY_STEPS) {
            for (trail in COMPROMISE_TRAIL_STEPS) {
                val m = runMetrics(points, today, entry, ZStrategyExitMode.ZPeakTrailing, trail)!!
                grid += CompromiseRow(entry, trail, m)
            }
        }

        println("=== Вариант 3: компромисс — пороговый вход + трейлинг-выход (полная выборка) ===")
        printSampleHeader(
            points,
            "Сетка вход ${COMPROMISE_ENTRY_STEPS} × trail ${COMPROMISE_TRAIL_STEPS} · выход только трейл от пика (|zBest|≥вход)"
        )
        println("Эталоны:")
        println(
            "  A макс.PnL (порог): вход 0.6 выход 0.5 → ${fmt(refMaxPnl.totalPnlRubApprox)} ₽, DD ${fmt(refMaxPnl.maxDrawdownRubApprox)} ₽"
        )
        println(
            "  B мин.DD (трейл):  вход 0.8 trail 0.40 → ${fmt(refMinDdTrail.totalPnlRubApprox)} ₽, DD ${fmt(refMinDdTrail.maxDrawdownRubApprox)} ₽"
        )
        println(
            "  C портфель:        вход 0.8 выход 0.7 → ${fmt(refClassic.totalPnlRubApprox)} ₽, DD ${fmt(refClassic.maxDrawdownRubApprox)} ₽"
        )
        println()
        println("вход | trail | сделок | win% | PnL ₽     | maxDD ₽  | Calmar | vs A PnL | vs B DD")
        println("-----+-------+--------+------+-----------+----------+--------+----------+--------")
        grid.sortedWith(compareByDescending<CompromiseRow> { it.calmar }.thenByDescending { it.metrics.totalPnlRubApprox })
            .forEach { println(it.formatLine(refMaxPnl, refMinDdTrail)) }

        val bestCalmar = grid.maxByOrNull { it.calmar }!!
        val bestPnlUnder20kDd = grid.filter { it.metrics.maxDrawdownRubApprox <= 20_000.0 }
            .maxByOrNull { it.metrics.totalPnlRubApprox }
        val bestPnlUnder15kDd = grid.filter { it.metrics.maxDrawdownRubApprox <= 15_000.0 }
            .maxByOrNull { it.metrics.totalPnlRubApprox }

        println("---")
        println(
            "Лучший Calmar (PnL/DD): вход ${fmt1(bestCalmar.entry)} trail ${fmt2(bestCalmar.trail)} → " +
                "${fmt(bestCalmar.metrics.totalPnlRubApprox)} ₽, DD ${fmt(bestCalmar.metrics.maxDrawdownRubApprox)} ₽, " +
                "Calmar ${fmt2(bestCalmar.calmar)}"
        )
        if (bestPnlUnder20kDd != null) {
            println(
                "Лучший PnL при DD≤20k: вход ${fmt1(bestPnlUnder20kDd.entry)} trail ${fmt2(bestPnlUnder20kDd.trail)} → " +
                    "${fmt(bestPnlUnder20kDd.metrics.totalPnlRubApprox)} ₽, DD ${fmt(bestPnlUnder20kDd.metrics.maxDrawdownRubApprox)} ₽"
            )
        }
        if (bestPnlUnder15kDd != null) {
            println(
                "Лучший PnL при DD≤15k: вход ${fmt1(bestPnlUnder15kDd.entry)} trail ${fmt2(bestPnlUnder15kDd.trail)} → " +
                    "${fmt(bestPnlUnder15kDd.metrics.totalPnlRubApprox)} ₽, DD ${fmt(bestPnlUnder15kDd.metrics.maxDrawdownRubApprox)} ₽"
            )
        }
        println()
        println("ТОП-5 по Calmar:")
        grid.sortedByDescending { it.calmar }.take(5).forEach { r ->
            println(
                "  ${fmt1(r.entry)}/${fmt2(r.trail)} → ${fmt(r.metrics.totalPnlRubApprox)} ₽, DD ${fmt(r.metrics.maxDrawdownRubApprox)} ₽, " +
                    "Calmar ${fmt2(r.calmar)}"
            )
        }
    }

    private suspend fun loadFullSamplePoints(): Pair<LocalDate, List<DataPoint>> {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue("Нет 15м данных MOEX", entities.isNotEmpty())
        val points = applyZScoresDefault(entities.map { it.toDataPoint() })
        assertTrue("Мало точек", points.size >= 2)
        return today to points
    }

    /** Гистерезис как 0.8/0.7: выход на 0.1 ниже входа, но не ниже 0.1. */
    private fun fixedExitForEntry(entry: Double): Double =
        (entry - 0.1).coerceAtLeast(0.1)

    private fun thresholdSweepSteps(): List<Double> {
        val steps = mutableListOf<Double>()
        var v = SWEEP_STEP
        while (v <= SWEEP_MAX + 1e-9) {
            steps += (v * 10).roundToInt() / 10.0
            v += SWEEP_STEP
        }
        return steps
    }

    private fun runMetrics(
        points: List<DataPoint>,
        today: LocalDate,
        entryZ: Double,
        exitMode: ZStrategyExitMode,
        zPeakTrailZ: Double
    ): PortfolioMetrics? {
        val exitThreshold = if (exitMode == ZStrategyExitMode.FixedThreshold) {
            zPeakTrailZ
        } else {
            fixedExitForEntry(entryZ)
        }
        return buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = DynamicThresholds(entry = entryZ, exit = exitThreshold, calculatedDate = today.toString()),
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.05,
            periodDescription = "MOEX full e=$entryZ",
            compoundReturns = false,
            exitMode = exitMode,
            zPeakTrailZ = zPeakTrailZ
        )
    }

    private fun printSampleHeader(points: List<DataPoint>, extra: String) {
        println("Период: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        println("$extra · плечо 7× · комиссия 0.05%/сторона · notional ${DEFAULT_PORTFOLIO_NOTIONAL_RUB.toLong()} ₽")
        println()
    }

    private data class CompromiseRow(
        val entry: Double,
        val trail: Double,
        val metrics: PortfolioMetrics
    ) {
        val calmar: Double = metrics.totalPnlRubApprox / metrics.maxDrawdownRubApprox.coerceAtLeast(1.0)

        fun formatLine(refMaxPnl: PortfolioMetrics, refMinDd: PortfolioMetrics): String {
            fun f(v: Double) = String.format(Locale.US, "%.2f", v)
            val vsPnl = metrics.totalPnlRubApprox - refMaxPnl.totalPnlRubApprox
            val vsDd = metrics.maxDrawdownRubApprox - refMinDd.maxDrawdownRubApprox
            return String.format(
                Locale.US,
                " %4.2f | %5.2f | %6d | %4.1f | %9s | %8s | %6.2f | %+9.0f | %+6.0f",
                entry,
                trail,
                metrics.closedTrades.size,
                metrics.winRate,
                f(metrics.totalPnlRubApprox),
                f(metrics.maxDrawdownRubApprox),
                calmar,
                vsPnl,
                vsDd
            )
        }
    }

    private data class EntrySweepRow(
        val entry: Double,
        val fixedExit: Double,
        val bestTrail: Double,
        val trailMetrics: PortfolioMetrics,
        val fixedMetrics: PortfolioMetrics
    ) {
        val pnlDelta: Double = trailMetrics.totalPnlRubApprox - fixedMetrics.totalPnlRubApprox

        fun formatLine(): String {
            fun f(v: Double) = String.format(Locale.US, "%.2f", v)
            return String.format(
                Locale.US,
                " %4.1f | %5.1f | %10.2f | %8d | %11s | %7s | %8d | %11s | %7s | %+10.2f",
                entry,
                fixedExit,
                bestTrail,
                trailMetrics.closedTrades.size,
                f(trailMetrics.totalPnlRubApprox),
                f(trailMetrics.maxDrawdownRubApprox),
                fixedMetrics.closedTrades.size,
                f(fixedMetrics.totalPnlRubApprox),
                f(fixedMetrics.maxDrawdownRubApprox),
                pnlDelta
            )
        }
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
    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)
}
