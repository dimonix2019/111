package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Интеграционный прогон: MOEX ISS → 15м ряд → симуляция (как «Тест страт.»).
 *
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexTodayBacktest_tatnTatnp_threshold08_07`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexTodayBacktest_thresholdSweep_today`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_noSignalDayStreaks_threshold08_07`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_compare_threshold08_07_vs_08_05_notional50k`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_dual50k_vs_single100k`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_100k_leverage7_threshold08_07`
 */
class MoexTodayBacktestTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private companion object {
        const val SWEEP_STEP = 0.1
        const val SWEEP_MAX = 2.1
        const val BACKTEST_NOTIONAL_50K_RUB = 50_000.0
        const val BACKTEST_NOTIONAL_100K_RUB = 100_000.0
        const val BACKTEST_LEVERAGE_X1 = 1.0
        const val BACKTEST_LEVERAGE_X7 = 7.0
        const val BACKTEST_COMMISSION_PCT_PER_SIDE = 0.04
        val THRESH_08_07 = DynamicThresholds(0.8, 0.7, null)
        val THRESH_08_05 = DynamicThresholds(0.8, 0.5, null)
    }

    @Test
    fun moexTodayBacktest_tatnTatnp_threshold08_07() = runBlocking {
        val (today, points) = loadTatn15mPoints()
        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = today.toString())
        val metrics = runPortfolioMetrics(points, thresholds, today)
        printSingleBacktestReport(today, points, thresholds, metrics)
    }

    @Test
    fun moexBacktest_noSignalDayStreaks_threshold08_07() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null)
        val tradingDays = tradingDaysWithBars(points)
        val entriesPerDay = tradingDays.map { countSignalsOnDay(points, thresholds, it, entryOnly = true) }
        val exitsPerDay = tradingDays.map { countSignalsOnDay(points, thresholds, it, entryOnly = false) }
        val anyPerDay = entriesPerDay.zip(exitsPerDay) { e, x -> e + x }

        val noEntryDays = entriesPerDay.count { it == 0 }
        val noAnySignalDays = anyPerDay.count { it == 0 }
        val entryStreaks = consecutiveQuietStreakLengths(entriesPerDay.map { it == 0 })
        val anyStreaks = consecutiveQuietStreakLengths(anyPerDay.map { it == 0 })

        println("=== MOEX: дни без сигналов (255д, пороги 0.8/0.7) ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate}")
        println("Торговых дней с 15м барами: ${tradingDays.size}")
        println(
            "Дней без входа: $noEntryDays (${pct(noEntryDays, tradingDays.size)}%), " +
                "без любого сигнала (вход/выход): $noAnySignalDays (${pct(noAnySignalDays, tradingDays.size)}%)"
        )
        printStreakBlock("Серии подряд без входа", entryStreaks)
        printStreakBlock("Серии подряд без любого сигнала", anyStreaks)
        val tailNoEntry = trailingQuietDays(entriesPerDay.map { it == 0 })
        val tailNoAny = trailingQuietDays(anyPerDay.map { it == 0 })
        println("Текущая серия на конец ряда (${tradingDays.last()}): без входа $tailNoEntry дн., без сигналов $tailNoAny дн.")
    }

    @Test
    fun moexBacktest_255d_compare_threshold08_07_vs_08_05_notional50k() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val variants = listOf(
            "вход 0.8 / выход 0.7" to DynamicThresholds(0.8, 0.7, null),
            "вход 0.8 / выход 0.5" to DynamicThresholds(0.8, 0.5, null)
        )
        println("=== MOEX 255д: сравнение порогов, ${BACKTEST_NOTIONAL_50K_RUB.toInt()} ₽ на сделку (x${BACKTEST_LEVERAGE_X1}) ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        println(
            "Комиссия ${BACKTEST_COMMISSION_PCT_PER_SIDE}% / сторона, без капитализации, выход по фикс. порогу |Z|"
        )
        println(
            String.format(
                Locale.US,
                "%-22s | %5s | %12s | %12s | %10s | %8s",
                "вариант",
                "сделок",
                "PnL чистый ₽",
                "max DD ₽",
                "комис. ₽",
                "оверн. ₽"
            )
        )
        println("-".repeat(88))
        for ((label, th) in variants) {
            val m = buildZStrategyPortfolioMetrics(
                points = points,
                thresholds = th,
                notionalRub = BACKTEST_NOTIONAL_50K_RUB,
                leverage = BACKTEST_LEVERAGE_X1,
                commissionPercentPerSide = BACKTEST_COMMISSION_PCT_PER_SIDE,
                periodDescription = "$label · ${PORTFOLIO_M15_LOOKBACK_DAYS}д",
                compoundReturns = false,
                exitMode = ZStrategyExitMode.FixedThreshold
            )
            checkNotNull(m) { "Нет метрик для $label" }
            println(
                String.format(
                    Locale.US,
                    "%-22s | %5d | %12.2f | %12.2f | %10.2f | %8.2f",
                    label,
                    m.closedTrades.size,
                    m.totalPnlRubApprox,
                    m.maxDrawdownRubApprox,
                    m.totalCommissionRub,
                    m.totalOvernightRub
                )
            )
            val gross = m.closedTrades.sumOf { it.grossPnlRubApprox }
            println(
                "  win/loss: ${m.winCount} / ${m.lossCount} · WR ${fmt(m.winRate)}% · " +
                    "валовый ${fmt(gross)} · средний чистый ${fmt(m.totalPnlRubApprox / m.closedTrades.size.coerceAtLeast(1))} ₽"
            )
            m.closedTrades.takeLast(3).forEach { t ->
                println(
                    "  … ${t.direction} ${t.entryDate}→${t.exitDate} чистый ${fmt(t.pnlRubApprox)} ₽"
                )
            }
        }
        val m07 = buildZStrategyPortfolioMetrics(
            points, variants[0].second, BACKTEST_NOTIONAL_50K_RUB, BACKTEST_LEVERAGE_X1,
            BACKTEST_COMMISSION_PCT_PER_SIDE, "cmp", false, ZStrategyExitMode.FixedThreshold
        )!!
        val m05 = buildZStrategyPortfolioMetrics(
            points, variants[1].second, BACKTEST_NOTIONAL_50K_RUB, BACKTEST_LEVERAGE_X1,
            BACKTEST_COMMISSION_PCT_PER_SIDE, "cmp", false, ZStrategyExitMode.FixedThreshold
        )!!
        println("---")
        println(
            "Δ PnL (0.5 vs 0.7 выход): ${fmt(m05.totalPnlRubApprox - m07.totalPnlRubApprox)} ₽ · " +
                "Δ сделок: ${m05.closedTrades.size - m07.closedTrades.size}"
        )
    }

    @Test
    fun moexBacktest_255d_100k_leverage7_threshold08_07() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val mX1 = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            notionalRub = BACKTEST_NOTIONAL_100K_RUB,
            leverage = BACKTEST_LEVERAGE_X1,
            commissionPercentPerSide = BACKTEST_COMMISSION_PCT_PER_SIDE
        )!!
        val mX7 = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            notionalRub = BACKTEST_NOTIONAL_100K_RUB,
            leverage = BACKTEST_LEVERAGE_X7,
            commissionPercentPerSide = BACKTEST_COMMISSION_PCT_PER_SIDE
        )!!
        println("=== MOEX 255д: 100k @ 0.8/0.7 — x1 vs x7 (комиссия ${BACKTEST_COMMISSION_PCT_PER_SIDE}%/сторона) ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        printBacktestRow("100k · x1", mX1)
        printBacktestRow("100k · x7", mX7)
        println("---")
        println(
            "Δ PnL (x7 − x1): ${fmt(mX7.totalPnlRubApprox - mX1.totalPnlRubApprox)} ₽ · " +
                "Δ max DD: ${fmt(mX7.maxDrawdownRubApprox - mX1.maxDrawdownRubApprox)} ₽"
        )
        assertTrue(mX7.closedTrades.size == mX1.closedTrades.size)
    }

    @Test
    fun moexBacktest_255d_dual50k_vs_single100k() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val m50_07 = runBacktest(points, THRESH_08_07, BACKTEST_NOTIONAL_50K_RUB)!!
        val m50_05 = runBacktest(points, THRESH_08_05, BACKTEST_NOTIONAL_50K_RUB)!!
        val m100_07 = runBacktest(points, THRESH_08_07, BACKTEST_NOTIONAL_100K_RUB)!!
        val m100_05 = runBacktest(points, THRESH_08_05, BACKTEST_NOTIONAL_100K_RUB)!!

        val dualPnl = m50_07.totalPnlRubApprox + m50_05.totalPnlRubApprox
        val dualCommission = m50_07.totalCommissionRub + m50_05.totalCommissionRub
        val dualTrades = m50_07.closedTrades.size + m50_05.closedTrades.size
        val dualMaxDd = maxDrawdownOfSummedDailyEquity(m50_07, m50_05)
        val overlapBars = countBarsWithBothLegsOpen(points, THRESH_08_07, THRESH_08_05)

        println("=== MOEX 255д: 2×50k (0.8/0.7 + 0.8/0.5) vs 1×100k ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров)")
        println("Капитал везде ~100k ₽ (две ноги по 50k или одна на 100k), x1, комиссия 0.04%/сторона")
        println()
        printBacktestRow("2 ноги: 50k @ 0.8/0.7", m50_07)
        printBacktestRow("2 ноги: 50k @ 0.8/0.5", m50_05)
        println(
            String.format(
                Locale.US,
                "%-28s | %5d | %12.2f | %12.2f | %10.2f | %8s",
                "ИТОГО 2×50k (сумма ног)",
                dualTrades,
                dualPnl,
                dualMaxDd,
                dualCommission,
                "—"
            )
        )
        println("  баров 15м с обеими ногами в позиции: $overlapBars")
        println()
        printBacktestRow("1 сделка: 100k @ 0.8/0.7", m100_07)
        printBacktestRow("1 сделка: 100k @ 0.8/0.5", m100_05)
        println("---")
        val vs07 = dualPnl - m100_07.totalPnlRubApprox
        val vs05 = dualPnl - m100_05.totalPnlRubApprox
        println("Δ PnL: 2×50k минус 100k@0.8/0.7 = ${fmt(vs07)} ₽")
        println("Δ PnL: 2×50k минус 100k@0.8/0.5 = ${fmt(vs05)} ₽")
        println(
            "Δ комиссий: 2×50k ${fmt(dualCommission)} vs 100k@0.7 ${fmt(m100_07.totalCommissionRub)} " +
                "(+${fmt(dualCommission - m100_07.totalCommissionRub)} ₽ при двух ногах)"
        )
        println(
            "Эфф. PnL на 100k капитала: 2×50k ${fmt(dualPnl)} (${fmt(dualPnl / 100_000 * 100)}%) · " +
                "100k@0.7 ${fmt(m100_07.totalPnlRubApprox)} (${fmt(m100_07.totalReturnPercent)}%)"
        )
    }

    @Test
    fun moexTodayBacktest_thresholdSweep_today() = runBlocking {
        val (today, points) = loadTatn15mPoints()
        val steps = thresholdSweepSteps()
        val todayPrefix = today.toString()

        println("=== MOEX backtest TATN/TATNP — сетка порогов за сегодня ===")
        println("МСК сегодня: $today")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        println("Последний Z=${fmt(points.last().zScore)} spread=${fmt(points.last().spreadPercent)}%")
        println(
            "Сетка: вход и выход от ${fmt(SWEEP_STEP)} до ${fmt(SWEEP_MAX)} шаг ${fmt(SWEEP_STEP)} " +
                "(${steps.size}×${steps.size} = ${steps.size * steps.size} комбинаций)"
        )
        println(
            "вход|выход | входов | выходов | закр.сегодня | PnL закр.₽ | Δequity сегодня₽ | позиция"
        )
        println("-------+--------+--------+-------------+-------------+------------------+--------")

        var combosWithActivity = 0
        for (entry in steps) {
            for (exit in steps) {
                val thresholds = DynamicThresholds(entry = entry, exit = exit, calculatedDate = today.toString())
                val metrics = runPortfolioMetrics(points, thresholds, today) ?: continue
                val row = todaySweepRow(points, metrics, thresholds, today, todayPrefix)
                if (row.hasActivityToday) combosWithActivity++
                println(row.formatLine())
            }
        }
        println("---")
        println("Комбинаций с активностью сегодня (вход/выход/закрытие): $combosWithActivity")
    }

    private fun runBacktest(
        points: List<DataPoint>,
        thresholds: DynamicThresholds,
        notionalRub: Double,
        leverage: Double = BACKTEST_LEVERAGE_X1,
        commissionPercentPerSide: Double = BACKTEST_COMMISSION_PCT_PER_SIDE
    ): PortfolioMetrics? =
        buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = notionalRub,
            leverage = leverage,
            commissionPercentPerSide = commissionPercentPerSide,
            periodDescription = "MOEX ${PORTFOLIO_M15_LOOKBACK_DAYS}д",
            compoundReturns = false,
            exitMode = ZStrategyExitMode.FixedThreshold
        )

    private fun printBacktestRow(label: String, m: PortfolioMetrics) {
        println(
            String.format(
                Locale.US,
                "%-28s | %5d | %12.2f | %12.2f | %10.2f | %7.1f%%",
                label,
                m.closedTrades.size,
                m.totalPnlRubApprox,
                m.maxDrawdownRubApprox,
                m.totalCommissionRub,
                m.totalReturnPercent
            )
        )
    }

    /** Max DD по сумме дневных equity двух независимых симуляций (общий счёт 100k). */
    private fun maxDrawdownOfSummedDailyEquity(vararg legs: PortfolioMetrics): Double {
        val byDay = linkedMapOf<String, Double>()
        for (m in legs) {
            m.equityCurveLabels.zip(m.equityCurveRub).forEach { (day, eq) ->
                byDay[day] = (byDay[day] ?: 0.0) + eq
            }
        }
        val series = byDay.values.toList()
        if (series.isEmpty()) return 0.0
        return drawdownRubSeriesFromEquity(series).maxOrNull() ?: 0.0
    }

    /** Сколько 15м баров обе ноги одновременно не Flat (двойная экспозиция на спрэд). */
    private fun countBarsWithBothLegsOpen(
        points: List<DataPoint>,
        thA: DynamicThresholds,
        thB: DynamicThresholds
    ): Int {
        if (points.size < 2) return 0
        var posA = ZStrategyPosition.Flat
        var posB = ZStrategyPosition.Flat
        var count = 0
        for (index in 1 until points.size) {
            posA = advancePosition(points, index, posA, thA)
            posB = advancePosition(points, index, posB, thB)
            if (posA != ZStrategyPosition.Flat && posB != ZStrategyPosition.Flat) count++
        }
        return count
    }

    private suspend fun loadTatn15mPoints(): Pair<LocalDate, List<DataPoint>> {
        val today = LocalDate.now(zone)
        val from = today.minusDays(PORTFOLIO_M15_LOOKBACK_DAYS)
        val till = portfolioM15MoexFetchTillDate()
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, till)
        assertTrue("Нет 15м данных с MOEX ($from…$till)", entities.isNotEmpty())
        val points = applyZScores(entities.map { it.toDataPoint() })
        assertTrue("Мало точек для Z", points.size >= 2)
        return today to points
    }

    private fun runPortfolioMetrics(
        points: List<DataPoint>,
        thresholds: DynamicThresholds,
        today: LocalDate
    ): PortfolioMetrics? =
        buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.05,
            periodDescription = "MOEX sweep $today",
            compoundReturns = false
        )

    private fun thresholdSweepSteps(): List<Double> {
        val steps = mutableListOf<Double>()
        var v = SWEEP_STEP
        while (v <= SWEEP_MAX + 1e-9) {
            steps += (v * 10).roundToInt() / 10.0
            v += SWEEP_STEP
        }
        return steps
    }

    private fun todaySweepRow(
        points: List<DataPoint>,
        metrics: PortfolioMetrics,
        thresholds: DynamicThresholds,
        today: LocalDate,
        todayPrefix: String
    ): TodaySweepRow {
        val todayEntries = countSignalsOnDay(points, thresholds, today, entryOnly = true)
        val todayExits = countSignalsOnDay(points, thresholds, today, entryOnly = false)
        val todayClosed = metrics.closedTrades.filter { it.exitDate.startsWith(todayPrefix) }
        val closedPnlToday = todayClosed.sumOf { it.pnlRubApprox }
        val equityDeltaToday = todayEquityDeltaRub(metrics, today)
        val open = metrics.openPosition?.direction?.name ?: "Flat"
        val hasActivity =
            todayEntries > 0 || todayExits > 0 || todayClosed.isNotEmpty() || open != "Flat"
        return TodaySweepRow(
            entry = thresholds.entry,
            exit = thresholds.exit,
            entriesToday = todayEntries,
            exitsToday = todayExits,
            closedToday = todayClosed.size,
            closedPnlTodayRub = closedPnlToday,
            equityDeltaTodayRub = equityDeltaToday,
            openPosition = open,
            hasActivityToday = hasActivity
        )
    }

    /** Δ дневной equity (последний снимок за день на кривой симуляции). */
    private fun todayEquityDeltaRub(metrics: PortfolioMetrics, today: LocalDate): Double {
        val labels = metrics.equityCurveLabels
        val equity = metrics.equityCurveRub
        if (labels.isEmpty() || equity.isEmpty()) return 0.0
        val todayKey = today.toString()
        val todayIdx = labels.indexOf(todayKey)
        if (todayIdx < 0) return 0.0
        val prevEq = if (todayIdx > 0) equity[todayIdx - 1] else 0.0
        return equity[todayIdx] - prevEq
    }

    private fun printSingleBacktestReport(
        today: LocalDate,
        points: List<DataPoint>,
        thresholds: DynamicThresholds,
        metrics: PortfolioMetrics?
    ) {
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

    private data class TodaySweepRow(
        val entry: Double,
        val exit: Double,
        val entriesToday: Int,
        val exitsToday: Int,
        val closedToday: Int,
        val closedPnlTodayRub: Double,
        val equityDeltaTodayRub: Double,
        val openPosition: String,
        val hasActivityToday: Boolean
    ) {
        fun formatLine(): String =
            String.format(
                Locale.US,
                "%4.1f | %4.1f | %6d | %7d | %11d | %11s | %16s | %s",
                entry,
                exit,
                entriesToday,
                exitsToday,
                closedToday,
                String.format(Locale.US, "%.2f", closedPnlTodayRub),
                String.format(Locale.US, "%.2f", equityDeltaTodayRub),
                openPosition
            )
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

    private fun tradingDaysWithBars(points: List<DataPoint>): List<LocalDate> =
        points
            .map { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
            .distinct()
            .sorted()

    /** Длины подряд идущих «тихих» дней (quietFlags[i] == true). */
    private fun consecutiveQuietStreakLengths(quietFlags: List<Boolean>): List<Int> {
        if (quietFlags.isEmpty()) return emptyList()
        val streaks = mutableListOf<Int>()
        var run = 0
        for (quiet in quietFlags) {
            if (quiet) {
                run++
            } else if (run > 0) {
                streaks += run
                run = 0
            }
        }
        if (run > 0) streaks += run
        return streaks
    }

    private fun trailingQuietDays(quietFlags: List<Boolean>): Int {
        var n = 0
        for (i in quietFlags.indices.reversed()) {
            if (!quietFlags[i]) break
            n++
        }
        return n
    }

    private fun pct(part: Int, total: Int): String =
        if (total <= 0) "0" else String.format(Locale.US, "%.1f", 100.0 * part / total)

    private fun printStreakBlock(title: String, streaks: List<Int>) {
        if (streaks.isEmpty()) {
            println("$title: серий нет (каждый день с сигналом)")
            return
        }
        val avg = streaks.average()
        val min = streaks.minOrNull() ?: 0
        val max = streaks.maxOrNull() ?: 0
        val totalQuietInStreaks = streaks.sum()
        println(
            "$title: серий=${streaks.size}, суммарно тихих дней в сериях=$totalQuietInStreaks, " +
                "средняя длина серии=${String.format(Locale.US, "%.1f", avg)} дн., " +
                "мин=${min} дн., макс=${max} дн."
        )
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
}
