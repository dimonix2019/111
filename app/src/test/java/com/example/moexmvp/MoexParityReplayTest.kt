package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Baseline parity: MOEX 255д, пороги 0.7/0.5 (лучшая пара на 3г бэктесте).
 * Replay всех пересечений Z на закрытых барах — эталон для сравнения с журналом live.
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexParityReplayTest`
 */
class MoexParityReplayTest {

    private val zone = ZoneId.of("Europe/Moscow")

    private val parityThresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)

    private companion object {
        const val NOTIONAL = 100_000.0
        const val LEVERAGE = 7.0
        const val COMMISSION = 0.04
    }

    @Test
    fun moexParity_baseline_07_05_closedBars_255d() = runBlocking {
        val (_, points) = loadPoints()
        val metrics = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = parityThresholds,
            notionalRub = NOTIONAL,
            leverage = LEVERAGE,
            commissionPercentPerSide = COMMISSION,
            periodDescription = "parity 255d",
            compoundReturns = false,
            exitMode = ZStrategyExitMode.FixedThreshold,
        )
        checkNotNull(metrics)

        val edges = collectEdgeSignals(points, parityThresholds)
        val entries = edges.count {
            it == ZStrategySignal.EnterLong || it == ZStrategySignal.EnterShort
        }
        val exits = edges.count {
            it == ZStrategySignal.ExitLong || it == ZStrategySignal.ExitShort
        }

        println("=== PARITY BASELINE (closed 15m bars) ===")
        println("Пороги: вход |Z|=${parityThresholds.entry}, выход |Z|=${parityThresholds.exit}")
        println("Параметры: ${NOTIONAL.toInt()} ₽, x$LEVERAGE, комиссия $COMMISSION%/сторона, Z rolling 30д")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров)")
        println("Сигналы replay: входов=$entries выходов=$exits всего=${edges.size}")
        println(
            "Симуляция: PnL ${fmt(metrics.totalPnlRubApprox)} ₽, сделок ${metrics.closedTrades.size}, " +
                "max DD ${fmt(metrics.maxDrawdownRubApprox)} ₽, комис. ${fmt(metrics.totalCommissionRub)} ₽"
        )
        println()
        println("Экспортируйте журнал из приложения (Журнал → Экспорт) и сравните:")
        println("  cd strategy-web && python3 scripts/journal_vs_sim_parity.py --journal <файл.json>")

        assertTrue("Нет сигналов replay", edges.isNotEmpty())
        assertTrue("Нет закрытых сделок", metrics.closedTrades.isNotEmpty())
    }

    @Test
    fun moexParity_signalRules_matchSimOnLastBar() = runBlocking {
        val (_, points) = loadPoints()
        if (points.size < 2) return@runBlocking
        var pos = ZStrategyPosition.Flat
        for (index in 1 until points.size) {
            val sig = determineZStrategySignal(
                points[index - 1].zScore,
                points[index].zScore,
                pos,
                parityThresholds
            )
            pos = when (sig) {
                ZStrategySignal.EnterLong -> ZStrategyPosition.Long
                ZStrategySignal.EnterShort -> ZStrategyPosition.Short
                ZStrategySignal.ExitLong, ZStrategySignal.ExitShort -> ZStrategyPosition.Flat
                ZStrategySignal.None -> pos
            }
        }
        val prev = points[points.size - 2]
        val last = points.last()
        val liveSig = zStrategySignalOnLast15mBar(points, pos, parityThresholds)
        val replaySig = determineZStrategySignal(prev.zScore, last.zScore, pos, parityThresholds)
        assertTrue(
            "zStrategySignalOnLast15mBar должен совпадать с replay: live=$liveSig replay=$replaySig pos=$pos",
            liveSig == replaySig
        )
    }

    private suspend fun loadPoints(): Pair<LocalDate, List<DataPoint>> {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue("Нет 15м данных MOEX ($from…$till)", entities.isNotEmpty())
        val points = applyZScoresDefault(entities.map { it.toDataPoint() })
        assertTrue("Мало точек для Z", points.size >= 2)
        return today to points
    }

    private fun collectEdgeSignals(
        points: List<DataPoint>,
        thresholds: DynamicThresholds
    ): List<ZStrategySignal> {
        if (points.size < 2) return emptyList()
        val out = mutableListOf<ZStrategySignal>()
        var pos = ZStrategyPosition.Flat
        for (index in 1 until points.size) {
            val sig = determineZStrategySignal(
                points[index - 1].zScore,
                points[index].zScore,
                pos,
                thresholds
            )
            if (sig != ZStrategySignal.None) {
                out += sig
            }
            pos = when (sig) {
                ZStrategySignal.EnterLong -> ZStrategyPosition.Long
                ZStrategySignal.EnterShort -> ZStrategyPosition.Short
                ZStrategySignal.ExitLong, ZStrategySignal.ExitShort -> ZStrategyPosition.Flat
                ZStrategySignal.None -> pos
            }
        }
        return out
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
