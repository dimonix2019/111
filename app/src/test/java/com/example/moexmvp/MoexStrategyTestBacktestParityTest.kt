package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Сравнение «Тест страт.» (prod-like) vs бэктест-сетка (100k×7) на одном MOEX 255д ряду.
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexStrategyTestBacktestParityTest`
 */
class MoexStrategyTestBacktestParityTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)

    @Test
    fun compareStrategyTestVsBacktest_07_05_255d() = runBlocking {
        val (_, raw) = loadPoints()
        val withZ = applyZScoresDefault(raw.map { it.copy() })
        val backtestPoints = withZ
        val liveSimPoints = prepareM15PointsForZStrategySim(
            points = withZ,
            journalEvents = listOf(
                StrategySignalEvent(
                    timestampMillis = withZ.first().timestampMillis,
                    signalType = StrategySignalType.EnterLong,
                    zScore = 0.9,
                ),
            ),
            journalThresholds = thresholds,
            applyJournalOverlay = false,
        )
        val journalSimPoints = prepareM15PointsForZStrategySim(
            points = raw.map { it.copy() },
            journalEvents = emptyList(),
            journalThresholds = thresholds,
            applyJournalOverlay = true,
        )

        val backtest = runBacktestGrid(backtestPoints)
        val backtestSlip = runBacktestGrid(
            backtestPoints,
            simOptions = ZStrategySimOptions(slippageSpreadPts = DEFAULT_STRATEGY_TEST_SLIPPAGE_SPREAD_PTS),
        )
        val strategyTest = runStrategyTestProdLike(liveSimPoints)
        val withJournalOverlay = runStrategyTestProdLike(journalSimPoints)

        val out = buildString {
            appendLine("=== StrategyTest vs Backtest parity 0.7/0.5 ===")
            appendLine("bars=${raw.size} ${raw.first().tradeDate} … ${raw.last().tradeDate}")
            appendLine(formatRow("backtest 100k×7 Z-recalc", backtest))
            appendLine(formatRow("backtest + slip 0.05", backtestSlip))
            appendLine(formatRow("strategy-test prod-like live Z", strategyTest))
            appendLine(formatRow("strategy-test journal overlay Z-recalc", withJournalOverlay))
            appendLine("Δ prod-live vs backtest PnL: ${fmt(strategyTest.totalPnlRubApprox - backtest.totalPnlRubApprox)} ₽")
            appendLine("Δ trades: ${strategyTest.closedTrades.size - backtest.closedTrades.size}")
            appendLine("closed sum backtest=${fmt(backtest.closedTrades.sumOf { it.pnlRubApprox })}")
            appendLine("closed sum prod=${fmt(strategyTest.closedTrades.sumOf { it.pnlRubApprox })}")
            val prodNotionals = strategyTest.closedTrades.map { it.executionNotionalRub }
            appendLine("prod notional min=${fmt(prodNotionals.minOrNull() ?: 0.0)} max=${fmt(prodNotionals.maxOrNull() ?: 0.0)}")
            val bt = backtest.closedTrades.first()
            val pr = strategyTest.closedTrades.first()
            appendLine("trade0 backtest ${bt.entryDate}→${bt.exitDate} spread=${bt.pnlSpreadPoints} pnl=${fmt(bt.pnlRubApprox)} notional=${fmt(bt.executionNotionalRub)}")
            appendLine("trade0 prod     ${pr.entryDate}→${pr.exitDate} spread=${pr.pnlSpreadPoints} pnl=${fmt(pr.pnlRubApprox)} notional=${fmt(pr.executionNotionalRub)}")
            val btByExit = backtest.closedTrades.associateBy { it.exitDate }
            val prByExit = strategyTest.closedTrades.associateBy { it.exitDate }
            val common = btByExit.keys.intersect(prByExit.keys)
            appendLine("matching exits=${common.size}/${backtest.closedTrades.size}")
            val matchedDelta = common.sumOf { btByExit.getValue(it).pnlRubApprox - prByExit.getValue(it).pnlRubApprox }
            appendLine("matched pnl delta sum=${fmt(matchedDelta)}")
        }
        File("/tmp/moex_st_parity.txt").writeText(out)
        println(out)

        assertTrue(backtest.closedTrades.isNotEmpty())
        assertTrue(liveSimPoints === withZ)
        assertEquals(backtest.closedTrades.size, strategyTest.closedTrades.size)
        assertTrue(
            "grid backtest without slip should be strongly positive on 0.7/0.5",
            backtest.totalPnlRubApprox > 10_000.0,
        )
    }

    private fun runBacktestGrid(
        points: List<DataPoint>,
        simOptions: ZStrategySimOptions = ZStrategySimOptions(),
    ): PortfolioMetrics =
        checkNotNull(
            buildZStrategyPortfolioMetrics(
                points = points,
                thresholds = thresholds,
                notionalRub = 100_000.0,
                leverage = 7.0,
                commissionPercentPerSide = 0.04,
                periodDescription = "backtest-grid",
                compoundReturns = false,
                exitMode = ZStrategyExitMode.FixedThreshold,
                simOptions = simOptions,
            )
        )

    private fun runStrategyTestProdLike(points: List<DataPoint>): PortfolioMetrics =
        checkNotNull(
            buildZStrategyPortfolioMetrics(
                points = points,
                thresholds = thresholds,
                notionalRub = 100_000.0,
                leverage = 1.0,
                commissionPercentPerSide = 0.04,
                periodDescription = "strategy-test",
                compoundReturns = false,
                exitMode = ZStrategyExitMode.FixedThreshold,
                simOptions = ZStrategySimOptions(slippageSpreadPts = DEFAULT_STRATEGY_TEST_SLIPPAGE_SPREAD_PTS),
                prodLikeSizing = ZStrategyProdLikeSizing(
                    accountSizeRub = 100_000.0,
                    capitalUsagePercent = 80.0,
                    leverageForLots = 7.0,
                ),
            )
        )

    private fun formatRow(label: String, m: PortfolioMetrics): String =
        "$label | PnL=${fmt(m.totalPnlRubApprox)} real=${fmt(m.cumulativeRealizedRubApprox)} " +
            "unrl=${fmt(m.unrealizedRubApprox)} DD=${fmt(m.maxDrawdownRubApprox)} " +
            "trades=${m.closedTrades.size} open=${m.openPosition?.direction} " +
            "comm=${fmt(m.totalCommissionRub)} " +
            "avgNotional=${fmt(m.closedTrades.map { it.executionNotionalRub }.average().takeIf { !it.isNaN() } ?: 0.0)}"

    private suspend fun loadPoints(): Pair<LocalDate, List<DataPoint>> {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue("Нет 15м MOEX", entities.isNotEmpty())
        val points = entities.map { it.toDataPoint() }
        return today to points
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.0f", v)
}
