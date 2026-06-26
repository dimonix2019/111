package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Гипотеза: вход на экстремум Z, выход на противоположном экстремуме.
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexZOppositeExtremeBacktestTest`
 */
class MoexZOppositeExtremeBacktestTest {

    private companion object {
        const val NOTIONAL = 100_000.0
        const val LEVERAGE = 7.0
        const val COMMISSION = 0.04
    }

    @Test
    fun moexBacktest_fixedVsOppositeExtreme_thresholdGrid() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val entries = listOf(1.0, 1.3, 1.5, 1.8, 2.0, 2.5)
        val fixedExits = listOf(0.7, 1.0, 1.2, 1.3)
        val oppositeExits = listOf(1.0, 1.3, 1.5, 1.8, 2.0, 2.5)

        println("=== MOEX 255д: фикс. выход vs противоп. экстремум Z ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        println("$NOTIONAL ₽ · x${LEVERAGE.toInt()} · комиссия ${COMMISSION}%/сторона · rolling Z 30д")
        println()
        printHeader()

        var bestFixed: Row? = null
        var bestOpposite: Row? = null

        for (entry in entries) {
            for (exit in fixedExits) {
                if (exit >= entry) continue
                val m = runSim(points, entry, exit, ZStrategyExitMode.FixedThreshold) ?: continue
                val row = Row("fixed", entry, exit, m)
                if (bestFixed == null || row.pnl > bestFixed!!.pnl) bestFixed = row
            }
            for (opp in oppositeExits) {
                val m = runSim(points, entry, opp, ZStrategyExitMode.OppositeExtreme) ?: continue
                val row = Row("opposite", entry, opp, m)
                if (bestOpposite == null || row.pnl > bestOpposite!!.pnl) bestOpposite = row
                if (entry == opp) {
                    printRow(row.copy(label = "symmetric ±${fmt(entry)}"))
                }
            }
        }

        println("---")
        bestFixed?.let { printRow(it.copy(label = "BEST fixed")) }
        bestOpposite?.let { printRow(it.copy(label = "BEST opposite")) }
        if (bestFixed != null && bestOpposite != null) {
            println(
                "Δ PnL (opposite − fixed): ${fmt(bestOpposite!!.pnl - bestFixed!!.pnl)} ₽ · " +
                    "Δ сделок: ${bestOpposite!!.trades - bestFixed!!.trades}"
            )
        }

        compareBaselinePairs(points, entry = 1.3, fixedExit = 1.2, oppositeExit = 1.3)
        compareBaselinePairs(points, entry = 1.8, fixedExit = 1.3, oppositeExit = 1.8)

        assertTrue(points.size >= 100)
        assertTrue(bestFixed != null || bestOpposite != null)
    }

    private fun compareBaselinePairs(
        points: List<DataPoint>,
        entry: Double,
        fixedExit: Double,
        oppositeExit: Double,
    ) {
        val fixed = runSim(points, entry, fixedExit, ZStrategyExitMode.FixedThreshold)!!
        val opposite = runSim(points, entry, oppositeExit, ZStrategyExitMode.OppositeExtreme)!!
        val fixedSwing = fixed.closedTrades.map { abs(it.exitSpreadPercent - it.entrySpreadPercent) }.average()
        val oppSwing = opposite.closedTrades.map { abs(it.exitSpreadPercent - it.entrySpreadPercent) }.average()
        println()
        println(
            "Сравнение ±$entry: fixed выход ±$fixedExit vs opposite ±$oppositeExit"
        )
        printRow(Row("fixed", entry, fixedExit, fixed))
        printRow(Row("opposite", entry, oppositeExit, opposite))
        println(
            "  ср. |Δspread| на сделку: fixed ${fmt(fixedSwing)} п.п. · opposite ${fmt(oppSwing)} п.п."
        )
        val oppReached = opposite.closedTrades.count { abs(it.exitSpreadPercent - it.entrySpreadPercent) > 0.3 }
        println(
            "  opposite закрылось с заметным движением спреда (>0.3 п.п.): " +
                "$oppReached / ${opposite.closedTrades.size}"
        )
    }

    private fun runSim(
        points: List<DataPoint>,
        entry: Double,
        exit: Double,
        exitMode: ZStrategyExitMode,
    ): PortfolioMetrics? =
        buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = DynamicThresholds(entry, exit, null),
            notionalRub = NOTIONAL,
            leverage = LEVERAGE,
            commissionPercentPerSide = COMMISSION,
            periodDescription = "opposite-extreme",
            compoundReturns = false,
            exitMode = exitMode,
        )

    private suspend fun loadTatn15mPoints(): Pair<LocalDate, List<DataPoint>> {
        val today = LocalDate.now(moexZoneId)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        val points = applyZScoresDefault(entities.map { it.toDataPoint() })
        return today to points
    }

    private data class Row(
        val kind: String,
        val entry: Double,
        val exit: Double,
        val metrics: PortfolioMetrics,
        val label: String? = null,
    ) {
        val pnl: Double get() = metrics.totalPnlRubApprox
        val trades: Int get() = metrics.closedTrades.size
        val maxDd: Double get() = metrics.maxDrawdownRubApprox
        val winRate: Double get() = metrics.winRate
    }

    private fun printHeader() {
        println(
            String.format(
                Locale.US,
                "%-28s | %5s | %12s | %12s | %6s",
                "вариант",
                "сделок",
                "PnL ₽",
                "max DD ₽",
                "WR %",
            )
        )
        println("-".repeat(78))
    }

    private fun printRow(row: Row) {
        val name = row.label ?: when (row.kind) {
            "fixed" -> "fixed ±${fmt(row.entry)}/±${fmt(row.exit)}"
            else -> "opposite вх±${fmt(row.entry)} вых+${fmt(row.exit)}"
        }
        println(
            String.format(
                Locale.US,
                "%-28s | %5d | %12.2f | %12.2f | %5.1f",
                name,
                row.trades,
                row.pnl,
                row.maxDd,
                row.winRate,
            )
        )
    }

    private fun fmt(v: Double): String =
        if (kotlin.math.abs(v - v.roundToInt()) < 1e-9) v.roundToInt().toString()
        else String.format(Locale.US, "%.1f", v)
}
