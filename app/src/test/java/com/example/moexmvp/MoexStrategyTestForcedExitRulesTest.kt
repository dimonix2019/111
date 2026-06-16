package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Принудительные выходы на live MOEX 255д (тот же ряд, что «Тест страт.»).
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexStrategyTestForcedExitRulesTest`
 */
class MoexStrategyTestForcedExitRulesTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private companion object {
        const val NOTIONAL = 100_000.0
        const val LEVERAGE = 7.0
        const val COMMISSION = 0.04
        val TH_08_07 = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null)
        val TH_07_05 = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)
    }

    @Test
    fun moexForcedExitRules_live255d_report() = runBlocking {
        val (_, raw) = loadPoints()
        val points = prepareM15PointsForZStrategySim(raw)
        println("=== MOEX 255д · forced exit rules · 100k x7 · без капитализации ===")
        println(
            "Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)"
        )

        for (thresholds in listOf(TH_08_07, TH_07_05)) {
            val baseline = runSim(points, thresholds, ZStrategySimOptions())
            checkNotNull(baseline)
            val avgWin = baseline.closedTrades
                .map { it.pnlRubApprox }
                .filter { it > 0 }
                .let { wins -> if (wins.isEmpty()) 0.0 else wins.average() }
            val moneyStopDynamic = (avgWin * 3.5).coerceAtLeast(4_000.0)

            println()
            println("--- Пороги ${thresholds.entry}/${thresholds.exit} · baseline PnL ${fmt(baseline.totalPnlRubApprox)} ₽ · сделок ${baseline.closedTrades.size} · avg win ${fmt(avgWin)} ₽ ---")
            println(
                String.format(
                    Locale.US,
                    "%-28s | %5s | %12s | %9s | %10s | %6s",
                    "правило",
                    "сдел",
                    "PnL ₽",
                    "Δ к базе",
                    "maxDD ₽",
                    "убыт",
                )
            )
            println("-".repeat(82))

            val rules = listOf(
                "baseline (Z)" to ZStrategySimOptions(),
                "1. Time stop 24ч" to ZStrategySimOptions(forcedTimeStopHours = 24.0),
                "2. Z-stop ±0.5" to ZStrategySimOptions(forcedZStopDeviation = 0.5),
                "3. Money stop 4000₽" to ZStrategySimOptions(maxLossRub = 4_000.0),
                "3b. Money 3.5×avgWin" to ZStrategySimOptions(maxLossRub = moneyStopDynamic),
                "48ч + минус" to ZStrategySimOptions(forcedHoldHoursIfLosing = 48.0),
                "48ч + минус + never-green" to ZStrategySimOptions(
                    forcedHoldHoursIfLosing = 48.0,
                    forcedHoldRequireNeverGreen = true,
                ),
                "T24 + Z0.5 + M4k" to ZStrategySimOptions(
                    forcedTimeStopHours = 24.0,
                    forcedZStopDeviation = 0.5,
                    maxLossRub = 4_000.0,
                ),
                "T24 + 48ч−" to ZStrategySimOptions(
                    forcedTimeStopHours = 24.0,
                    forcedHoldHoursIfLosing = 48.0,
                ),
                "Z0.5 + M4k + 48ч− NG" to ZStrategySimOptions(
                    forcedZStopDeviation = 0.5,
                    maxLossRub = 4_000.0,
                    forcedHoldHoursIfLosing = 48.0,
                    forcedHoldRequireNeverGreen = true,
                ),
            )

            for ((label, options) in rules) {
                val m = checkNotNull(runSim(points, thresholds, options))
                val delta = m.totalPnlRubApprox - baseline.totalPnlRubApprox
                val losses = m.closedTrades.count { it.pnlRubApprox < 0 }
                println(
                    String.format(
                        Locale.US,
                        "%-28s | %5d | %12s | %9s | %10s | %6d",
                        label,
                        m.closedTrades.size,
                        fmt(m.totalPnlRubApprox),
                        fmtSigned(delta),
                        fmt(m.maxDrawdownRubApprox),
                        losses,
                    )
                )
            }
        }

        assertTrue(points.size >= Z_SCORE_ROLLING_MIN_BARS)
    }

    @Test
    fun forcedZStop_longExitsWhenZMovesUp() {
        val points = listOf(
            dp("2026-01-01 10:00", -0.6),
            dp("2026-01-01 10:15", -0.85),
            dp("2026-01-01 10:30", -0.2),
            dp("2026-01-01 10:45", -0.1),
        )
        val th = DynamicThresholds(0.7, 0.5, null)
        val base = checkNotNull(
            runSim(points, th, ZStrategySimOptions(), compound = false, leverage = 1.0)
        )
        val zStop = checkNotNull(
            runSim(
                points,
                th,
                ZStrategySimOptions(forcedZStopDeviation = 0.5),
                compound = false,
                leverage = 1.0,
            )
        )
        assertTrue(base.closedTrades.size >= 1)
        assertTrue(zStop.closedTrades.size >= 1)
        assertTrue(zStop.closedTrades.first().exitDate <= "2026-01-01 10:30")
    }

    private fun runSim(
        points: List<DataPoint>,
        thresholds: DynamicThresholds,
        simOptions: ZStrategySimOptions,
        compound: Boolean = false,
        leverage: Double = LEVERAGE,
    ): PortfolioMetrics? =
        buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = NOTIONAL,
            leverage = leverage,
            commissionPercentPerSide = COMMISSION,
            periodDescription = "forced-exit",
            compoundReturns = compound,
            exitMode = ZStrategyExitMode.FixedThreshold,
            simOptions = simOptions,
        )

    private suspend fun loadPoints(): Pair<LocalDate, List<DataPoint>> {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue("Нет 15м MOEX", entities.isNotEmpty())
        val points = applyZScoresDefault(entities.map { it.toDataPoint() })
        return today to points
    }

    private fun dp(label: String, z: Double) = DataPoint(
        timestampMillis = 0L,
        tradeDate = label,
        tatnClose = 650.0,
        tatnpClose = 620.0,
        spreadPercent = 5.0 + z,
        diff = 30.0,
        zScore = z,
    )

    private fun fmt(v: Double): String = String.format(Locale.US, "%.0f", v)

    private fun fmtSigned(v: Double): String =
        (if (v >= 0) "+" else "") + String.format(Locale.US, "%.0f", v)
}
