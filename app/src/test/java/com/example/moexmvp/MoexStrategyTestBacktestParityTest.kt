package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Parity prod-like симуляции «Тест страт.»: live Z vs journal overlay.
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexStrategyTestBacktestParityTest`
 */
class MoexStrategyTestBacktestParityTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)

    @Test
    fun compareStrategyTestProdLike_liveZ_vs_journalOverlay_07_05_255d() = runBlocking {
        val (_, raw) = loadPoints()
        val liveSimPoints = prepareM15PointsForZStrategySim(
            points = raw,
            journalEvents = listOf(
                StrategySignalEvent(
                    timestampMillis = raw.first().timestampMillis,
                    signalType = StrategySignalType.EnterLong,
                    zScore = 0.9,
                ),
            ),
            journalThresholds = thresholds,
            applyJournalOverlay = false,
        )
        val journalSimPoints = prepareM15PointsForZStrategySim(
            points = raw,
            journalEvents = emptyList(),
            journalThresholds = thresholds,
            applyJournalOverlay = true,
        )

        val strategyTestLive = runStrategyTestProdLike(liveSimPoints)
        val withJournalOverlay = runStrategyTestProdLike(journalSimPoints)

        val out = buildString {
            appendLine("=== StrategyTest prod-like parity 0.7/0.5 (10k) ===")
            appendLine("bars=${raw.size} ${raw.first().tradeDate} … ${raw.last().tradeDate}")
            appendLine(formatRow("prod-like live Z", strategyTestLive))
            appendLine(formatRow("prod-like journal overlay Z", withJournalOverlay))
            appendLine("Δ live vs overlay PnL: ${fmt(strategyTestLive.totalPnlRubApprox - withJournalOverlay.totalPnlRubApprox)} ₽")
            appendLine("Δ trades: ${strategyTestLive.closedTrades.size - withJournalOverlay.closedTrades.size}")
            val prodNotionals = strategyTestLive.closedTrades.map { it.executionNotionalRub }
            appendLine("avg notional=${fmt(prodNotionals.average().takeIf { !it.isNaN() } ?: 0.0)}")
        }
        File("/tmp/moex_st_parity.txt").writeText(out)
        println(out)

        assertTrue(strategyTestLive.closedTrades.isNotEmpty())
        assertTrue(liveSimPoints !== raw)
    }

    private fun runStrategyTestProdLike(points: List<DataPoint>): PortfolioMetrics =
        checkNotNull(
            buildProdLikeStrategySimMetrics(
                points = points,
                thresholds = thresholds,
                periodDescription = "strategy-test prod-like",
            )
        )

    private fun formatRow(label: String, m: PortfolioMetrics): String =
        "$label | PnL=${fmt(m.totalPnlRubApprox)} real=${fmt(m.cumulativeRealizedRubApprox)} " +
            "unrl=${fmt(m.unrealizedRubApprox)} DD=${fmt(m.maxDrawdownRubApprox)} " +
            "trades=${m.closedTrades.size} open=${m.openPosition?.direction} " +
            "comm=${fmt(m.totalCommissionRub)} ret=${fmt(m.totalReturnPercent)}% " +
            "avgNotional=${fmt(m.closedTrades.map { it.executionNotionalRub }.average().takeIf { !it.isNaN() } ?: 0.0)}"

    private suspend fun loadPoints(): Pair<LocalDate, List<DataPoint>> {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue("Нет 15м MOEX", entities.isNotEmpty())
        return today to entities.map { it.toDataPoint() }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.0f", v)
}
