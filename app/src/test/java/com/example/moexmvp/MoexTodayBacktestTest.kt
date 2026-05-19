package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Интеграционный прогон: MOEX ISS → 15м ряд → симуляция (как «Тест страт.»).
 * Запуск: ./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest
 */
class MoexTodayBacktestTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    @Test
    fun moexTodayBacktest_tatnTatnp_threshold08_07() = runBlocking {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()

        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue("Нет 15м данных с MOEX ($from…$till)", entities.isNotEmpty())

        val points = applyZScores(entities.map { it.toDataPoint() })
        assertTrue("Мало точек для Z", points.size >= 2)

        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = today.toString())
        val metrics = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.05,
            periodDescription = "MOEX live backtest",
            compoundReturns = false
        )
        checkNotNull(metrics)

        val todayPrefix = today.toString()
        val todayTrades = metrics.closedTrades.filter {
            it.exitDate.startsWith(todayPrefix) || it.entryDate.startsWith(todayPrefix)
        }
        val todayBars = points.filter {
            Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() == today
        }

        println("=== MOEX backtest TATN/TATNP ===")
        println("МСК сегодня: $today")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        println("Пороги: вход |Z|=${thresholds.entry}, выход |Z|=${thresholds.exit}")
        println("Последний бар: ${points.last().tradeDate} Z=${fmt(points.last().zScore)} spread=${fmt(points.last().spreadPercent)}%")
        if (points.size >= 2) {
            val prev = points[points.size - 2]
            val last = points.last()
            val pos = inferPositionAtEnd(metrics, points, thresholds)
            val sig = determineZStrategySignal(prev.zScore, last.zScore, pos, thresholds)
            println("Пересечение на последнем баре (prev→last): $sig (позиция $pos)")
        }
        println("Баров 15м за сегодня: ${todayBars.size}")
        todayBars.takeLast(8).forEach { p ->
            println("  ${p.tradeDate} Z=${fmt(p.zScore)} spread=${fmt(p.spreadPercent)}%")
        }
        println("Закрытые сделки с датой входа/выхода сегодня: ${todayTrades.size}")
        todayTrades.forEach { t ->
            println(
                "  ${t.direction} ${t.entryDate} → ${t.exitDate} | " +
                    "валовый ${fmt(t.grossPnlRubApprox)} комис. ${fmt(t.commissionRubApprox)} " +
                    "оверн. ${fmt(t.overnightRubApprox)} чистый ${fmt(t.pnlRubApprox)} ₽"
            )
        }
        val todayEntries = countSignalsOnDay(points, thresholds, today, entryOnly = true)
        val todayExits = countSignalsOnDay(points, thresholds, today, entryOnly = false)
        println("Сигналы-пересечения за сегодня (replay по ряду): входов $todayEntries, выходов $todayExits")
        println(
            "Итого симуляция: PnL ${fmt(metrics.totalPnlRubApprox)} ₽, сделок ${metrics.closedTrades.size}, " +
                "max DD ${fmt(metrics.maxDrawdownRubApprox)} ₽"
        )
        println("Открытая позиция: ${metrics.openPosition?.direction ?: "Flat"}")
    }

    private fun inferPositionAtEnd(
        metrics: PortfolioMetrics,
        points: List<DataPoint>,
        thresholds: DynamicThresholds
    ): ZStrategyPosition {
        metrics.openPosition?.direction?.let { return it }
        var pos = ZStrategyPosition.Flat
        for (index in 1 until points.size) {
            val sig = determineZStrategySignal(
                points[index - 1].zScore,
                points[index].zScore,
                pos,
                thresholds
            )
            pos = when (sig) {
                ZStrategySignal.EnterLong -> ZStrategyPosition.Long
                ZStrategySignal.EnterShort -> ZStrategyPosition.Short
                ZStrategySignal.ExitLong, ZStrategySignal.ExitShort -> ZStrategyPosition.Flat
                ZStrategySignal.None -> pos
            }
        }
        return pos
    }

    private fun countSignalsOnDay(
        points: List<DataPoint>,
        thresholds: DynamicThresholds,
        day: LocalDate,
        entryOnly: Boolean
    ): Int {
        var pos = ZStrategyPosition.Flat
        var count = 0
        for (index in 1 until points.size) {
            val barDay = Instant.ofEpochMilli(points[index].timestampMillis).atZone(zone).toLocalDate()
            if (barDay != day) {
                pos = advancePosition(points, index, pos, thresholds)
                continue
            }
            val sig = determineZStrategySignal(
                points[index - 1].zScore,
                points[index].zScore,
                pos,
                thresholds
            )
            val isEntry = sig == ZStrategySignal.EnterLong || sig == ZStrategySignal.EnterShort
            val isExit = sig == ZStrategySignal.ExitLong || sig == ZStrategySignal.ExitShort
            if (entryOnly && isEntry) count++
            if (!entryOnly && isExit) count++
            pos = when (sig) {
                ZStrategySignal.EnterLong -> ZStrategyPosition.Long
                ZStrategySignal.EnterShort -> ZStrategyPosition.Short
                ZStrategySignal.ExitLong, ZStrategySignal.ExitShort -> ZStrategyPosition.Flat
                ZStrategySignal.None -> pos
            }
        }
        return count
    }

    private fun advancePosition(
        points: List<DataPoint>,
        index: Int,
        pos: ZStrategyPosition,
        thresholds: DynamicThresholds
    ): ZStrategyPosition {
        val sig = determineZStrategySignal(
            points[index - 1].zScore,
            points[index].zScore,
            pos,
            thresholds
        )
        return when (sig) {
            ZStrategySignal.EnterLong -> ZStrategyPosition.Long
            ZStrategySignal.EnterShort -> ZStrategyPosition.Short
            ZStrategySignal.ExitLong, ZStrategySignal.ExitShort -> ZStrategyPosition.Flat
            ZStrategySignal.None -> pos
        }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
}
