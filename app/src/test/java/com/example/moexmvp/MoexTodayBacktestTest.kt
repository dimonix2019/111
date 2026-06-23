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
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_thresholdGapSweep_0_2_5`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_noSignalDayStreaks_threshold08_07`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_compare_threshold08_07_vs_08_05_notional50k`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_dual50k_vs_single100k`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_baseline_vs_pullbackEntry_peakExit`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_pullbackEntry_only_fixedExit07`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_pullbackEntry_peakTrail_grid`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_pyramid_report_clearing`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_pyramid_add_depth_grid`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_pyramid_normalized_on_capital`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_pyramid_depth_gt1`
 * - `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_zMovementAnalytics`
 */
class MoexTodayBacktestTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private companion object {
        const val SWEEP_STEP = 0.1
        const val SWEEP_MAX = 2.1
        const val BACKTEST_ACCOUNT_50K_RUB = 50_000.0
        const val BACKTEST_ACCOUNT_100K_RUB = 100_000.0
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
        println("=== MOEX 255д: сравнение порогов, prod-like ${BACKTEST_ACCOUNT_50K_RUB.toInt()} ₽ счёт ===")
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
            val m = buildProdLikeStrategySimMetrics(
                points = points,
                thresholds = th,
                accountSizeRub = BACKTEST_ACCOUNT_50K_RUB,
                commissionPercentPerSide = BACKTEST_COMMISSION_PCT_PER_SIDE,
                periodDescription = "$label · ${PORTFOLIO_M15_LOOKBACK_DAYS}д prod-like",
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
        val m07 = buildProdLikeStrategySimMetrics(
            points = points,
            thresholds = variants[0].second,
            accountSizeRub = BACKTEST_ACCOUNT_50K_RUB,
            commissionPercentPerSide = BACKTEST_COMMISSION_PCT_PER_SIDE,
            periodDescription = "cmp",
        )!!
        val m05 = buildProdLikeStrategySimMetrics(
            points = points,
            thresholds = variants[1].second,
            accountSizeRub = BACKTEST_ACCOUNT_50K_RUB,
            commissionPercentPerSide = BACKTEST_COMMISSION_PCT_PER_SIDE,
            periodDescription = "cmp",
        )!!
        println("---")
        println(
            "Δ PnL (0.5 vs 0.7 выход): ${fmt(m05.totalPnlRubApprox - m07.totalPnlRubApprox)} ₽ · " +
                "Δ сделок: ${m05.closedTrades.size - m07.closedTrades.size}"
        )
    }

    @Test
    fun moexBacktest_255d_baseline_vs_pullbackEntry_peakExit() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val notional = BACKTEST_ACCOUNT_100K_RUB
        val baseline = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = notional
        )!!
        val improved07 = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = notional,
            exitMode = ZStrategyExitMode.ZPeakTrailing,
            zPeakTrailZ = 0.07,
            entryPullbackZ = 0.07
        )!!
        val improved05 = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = notional,
            exitMode = ZStrategyExitMode.ZPeakTrailing,
            zPeakTrailZ = 0.05,
            entryPullbackZ = 0.05
        )!!
        val improved10 = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = notional,
            exitMode = ZStrategyExitMode.ZPeakTrailing,
            zPeakTrailZ = 0.10,
            entryPullbackZ = 0.10
        )!!

        println("=== MOEX 255д: база vs «лучший Z» (откат вход + трейл выход) @ 100k ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        println("База: вход/выход сразу по 0.8/0.7 · Улучш.: ждём откат Z, выход — трейл от пика Z")
        println(
            String.format(
                Locale.US,
                "%-34s | %5s | %12s | %12s | %10s | %8s",
                "вариант",
                "сделок",
                "PnL чистый ₽",
                "max DD ₽",
                "комис. ₽",
                "WR %"
            )
        )
        println("-".repeat(96))
        for ((label, m) in listOf(
            "1) база 0.8/0.7 фикс." to baseline,
            "2) откат+трейл 0.07" to improved07,
            "3) откат+трейл 0.05" to improved05,
            "4) откат+трейл 0.10" to improved10
        )) {
            println(
                String.format(
                    Locale.US,
                    "%-34s | %5d | %12.2f | %12.2f | %10.2f | %7.1f",
                    label,
                    m.closedTrades.size,
                    m.totalPnlRubApprox,
                    m.maxDrawdownRubApprox,
                    m.totalCommissionRub,
                    m.winRate
                )
            )
        }
        println("---")
        println("Δ PnL (2−1): ${fmt(improved07.totalPnlRubApprox - baseline.totalPnlRubApprox)} ₽")
        println("Δ сделок (2−1): ${improved07.closedTrades.size - baseline.closedTrades.size}")
        println("Δ max DD (2−1): ${fmt(improved07.maxDrawdownRubApprox - baseline.maxDrawdownRubApprox)} ₽")
        assertTrue(baseline.closedTrades.isNotEmpty() || improved07.closedTrades.isNotEmpty())
    }

    @Test
    fun moexBacktest_255d_pullbackEntry_only_fixedExit07() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val notional = BACKTEST_ACCOUNT_100K_RUB
        val baseline = runBacktest(points, THRESH_08_07, notional)!!
        val rows = pullbackTrailGridSteps().map { delta ->
            delta to runBacktest(
                points = points,
                thresholds = THRESH_08_07,
                accountSizeRub = notional,
                entryPullbackZ = delta,
                exitMode = ZStrategyExitMode.FixedThreshold
            )!!
        }

        println("=== MOEX 255д: только откат входа Z, выход фикс. 0.7 @ 100k ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        println("База: вход сразу 0.8/0.7 · Тест: ждём откат Z после 0.8, выход как в базе")
        printBacktestHeaderWide()
        printBacktestRowWide("1) база 0.8/0.7", baseline)
        for ((delta, m) in rows) {
            printBacktestRowWide("откат вход ${fmt(delta)} / выход 0.7", m)
        }
        val best = rows.maxByOrNull { it.second.totalPnlRubApprox }!!
        println("---")
        println(
            "Лучший откат входа: ${fmt(best.first)} → PnL ${fmt(best.second.totalPnlRubApprox)} ₽ " +
                "(Δ к базе ${fmt(best.second.totalPnlRubApprox - baseline.totalPnlRubApprox)} ₽, " +
                "сделок ${best.second.closedTrades.size} vs ${baseline.closedTrades.size})"
        )
        assertTrue(baseline.closedTrades.isNotEmpty())
    }

    @Test
    fun moexBacktest_255d_pullbackEntry_peakTrail_grid() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val notional = BACKTEST_ACCOUNT_100K_RUB
        val baseline = runBacktest(points, THRESH_08_07, notional)!!
        val steps = pullbackTrailGridSteps()
        val cells = mutableListOf<PullbackGridCell>()
        for (entryPb in steps) {
            for (trail in steps) {
                val m = runBacktest(
                    points = points,
                    thresholds = THRESH_08_07,
                    accountSizeRub = notional,
                    entryPullbackZ = entryPb,
                    exitMode = ZStrategyExitMode.ZPeakTrailing,
                    zPeakTrailZ = trail
                )!!
                cells += PullbackGridCell(entryPb, trail, m)
            }
        }

        println("=== MOEX 255д: сетка откат входа × трейл выхода @ 100k, пороги 0.8/0.7 ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        printBacktestRowWide("база 0.8/0.7 фикс.", baseline)
        println()
        println("PnL ₽ (строка = откат входа, столбец = трейл выхода):")
        print("        ")
        for (trail in steps) {
            print(String.format(Locale.US, "%8.2f", trail))
        }
        println()
        for (entryPb in steps) {
            print(String.format(Locale.US, "%5.2f |", entryPb))
            for (trail in steps) {
                val pnl = cells.first { it.entryPullbackZ == entryPb && it.zPeakTrailZ == trail }
                    .metrics.totalPnlRubApprox
                print(String.format(Locale.US, "%8.0f", pnl))
            }
            println()
        }
        println()
        println("Топ-5 комбинаций по PnL:")
        printBacktestHeaderWide()
        cells.sortedByDescending { it.metrics.totalPnlRubApprox }.take(5).forEach { cell ->
            printBacktestRowWide(
                "вх ${fmt(cell.entryPullbackZ)} / трейл ${fmt(cell.zPeakTrailZ)}",
                cell.metrics
            )
        }
        val best = cells.maxByOrNull { it.metrics.totalPnlRubApprox }!!
        val worst = cells.minByOrNull { it.metrics.totalPnlRubApprox }!!
        println("---")
        println(
            "Лучшая ячейка: вх ${fmt(best.entryPullbackZ)} / трейл ${fmt(best.zPeakTrailZ)} → " +
                "PnL ${fmt(best.metrics.totalPnlRubApprox)} ₽, сделок ${best.metrics.closedTrades.size}, " +
                "WR ${fmt(best.metrics.winRate)}%"
        )
        println(
            "Δ к базе: ${fmt(best.metrics.totalPnlRubApprox - baseline.totalPnlRubApprox)} ₽ · " +
                "худшая ячейка PnL ${fmt(worst.metrics.totalPnlRubApprox)} ₽"
        )
        assertTrue(cells.size == steps.size * steps.size)
    }

    @Test
    fun moexBacktest_255d_pyramid_report_clearing() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val notional = BACKTEST_ACCOUNT_100K_RUB
        val baseline = runBacktest(points, THRESH_08_07, notional)!!
        val pyramid = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = notional,
            simOptions = ZStrategySimOptions(
                pyramidAddNotionalRub = 50_000.0,
                pyramidZDepth = 1.0
            )
        )!!
        val reportDays = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = notional,
            simOptions = ZStrategySimOptions(skipTatnReportDays = true)
        )!!
        val clearing = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = notional,
            simOptions = ZStrategySimOptions(
                closeBeforeClearingMsk = MOEX_EVENING_CLEARING_CUTOFF_MSK
            )
        )!!
        val combined = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = notional,
            simOptions = ZStrategySimOptions(
                pyramidAddNotionalRub = 50_000.0,
                pyramidZDepth = 1.0,
                skipTatnReportDays = true,
                closeBeforeClearingMsk = MOEX_EVENING_CLEARING_CUTOFF_MSK
            )
        )!!

        println("=== MOEX 255д: пирамидинг / отчётные дни / выход до клиринга @ 100k 0.8/0.7 ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        printBacktestHeaderWide()
        printBacktestRowWide("1) база 0.8/0.7", baseline)
        printBacktestRowWide("2) пир. +50k @ |Z|≥1.0", pyramid)
        printBacktestRowWide("3) без отчёт. дн. TATN", reportDays)
        printBacktestRowWide("4) выход до 18:45 МСК", clearing)
        printBacktestRowWide("5) всё вместе", combined)
        println("---")
        for ((label, m) in listOf(
            "пир." to pyramid,
            "отчёт" to reportDays,
            "клир." to clearing,
            "всё" to combined
        )) {
            println(
                "Δ PnL ($label − база): ${fmt(m.totalPnlRubApprox - baseline.totalPnlRubApprox)} ₽ · " +
                    "Δ сделок: ${m.closedTrades.size - baseline.closedTrades.size}"
            )
        }
        assertTrue(baseline.closedTrades.isNotEmpty())
    }

    @Test
    fun moexBacktest_255d_pyramid_normalized_on_capital() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val base100 = runBacktest(points, THRESH_08_07, BACKTEST_ACCOUNT_100K_RUB)!!
        val pyr50 = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = BACKTEST_ACCOUNT_100K_RUB,
            simOptions = ZStrategySimOptions(
                pyramidAddNotionalRub = 50_000.0,
                pyramidZDepth = 1.0
            )
        )!!
        val pyr100 = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = BACKTEST_ACCOUNT_100K_RUB,
            simOptions = ZStrategySimOptions(
                pyramidAddNotionalRub = 100_000.0,
                pyramidZDepth = 1.0
            )
        )!!
        val flat150 = runBacktest(points, THRESH_08_07, 150_000.0)!!
        val flat200 = runBacktest(points, THRESH_08_07, 200_000.0)!!

        println("=== MOEX 255д: пирамидинг vs «просто больше номинал» (нормализация на капитал) ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        println("Пороги 0.8/0.7 · x1 · комиссия ${BACKTEST_COMMISSION_PCT_PER_SIDE}%/сторона")
        println()
        printPyramidCapitalCompareHeader()
        printPyramidCapitalCompareRow("100k база", base100, initialCapitalRub = 100_000.0, peakCapitalRub = 100_000.0)
        printPyramidCapitalCompareRow(
            "100k + пир. +50k @ |Z|≥1",
            pyr50,
            initialCapitalRub = 100_000.0,
            peakCapitalRub = 150_000.0
        )
        printPyramidCapitalCompareRow(
            "100k + пир. +100k @ |Z|≥1",
            pyr100,
            initialCapitalRub = 100_000.0,
            peakCapitalRub = 200_000.0
        )
        printPyramidCapitalCompareRow("150k без пирамиды", flat150, initialCapitalRub = 150_000.0, peakCapitalRub = 150_000.0)
        printPyramidCapitalCompareRow("200k без пирамиды", flat200, initialCapitalRub = 200_000.0, peakCapitalRub = 200_000.0)
        println()
        println("Δ доходность на пиковый капитал (пир.+50k − flat 150k): " +
            fmtPct(returnOnCapitalPercent(pyr50, 150_000.0) - returnOnCapitalPercent(flat150, 150_000.0)) + " п.п.")
        println("Δ доходность на пиковый капитал (пир.+100k − flat 200k): " +
            fmtPct(returnOnCapitalPercent(pyr100, 200_000.0) - returnOnCapitalPercent(flat200, 200_000.0)) + " п.п.")
        assertTrue(base100.closedTrades.isNotEmpty())
    }

    @Test
    fun moexBacktest_255d_zMovementAnalytics() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val entry = THRESH_08_07.entry
        val exit = THRESH_08_07.exit
        val report = buildZMovementReport(
            points = points,
            zoneId = zone,
            entryThreshold = entry,
            exitThreshold = exit,
            pyramidDepth = 1.0,
            topDays = 12
        )!!
        println(formatZMovementReport(report, entry, exit))
        val baseline = runBacktest(points, THRESH_08_07, BACKTEST_ACCOUNT_100K_RUB)!!
        println("---")
        println(
            "Симуляция 0.8/0.7 (для сопоставления): ${baseline.closedTrades.size} сделок, " +
                "PnL ${fmt(baseline.totalPnlRubApprox)} ₽, пересечений входа LONG+SHORT ≈ " +
                "${report.crossings.crossDownEntryLong + report.crossings.crossUpEntryShort}"
        )
        assertTrue(report.barCount >= 100)
        assertTrue(report.zoneShares.sumOf { it.percent } in 99.0..101.0)
    }

    @Test
    fun moexBacktest_255d_pyramid_add_depth_grid() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val notional = BACKTEST_ACCOUNT_100K_RUB
        val baseline = runBacktest(points, THRESH_08_07, notional)!!
        val addSteps = pyramidAddNotionalGridSteps()
        val depthSteps = pyramidZDepthGridSteps()
        val cells = mutableListOf<PyramidGridCell>()
        for (addRub in addSteps) {
            for (depth in depthSteps) {
                val m = runBacktest(
                    points = points,
                    thresholds = THRESH_08_07,
                    accountSizeRub = notional,
                    simOptions = ZStrategySimOptions(
                        pyramidAddNotionalRub = addRub,
                        pyramidZDepth = depth
                    )
                )!!
                cells += PyramidGridCell(addRub, depth, m)
            }
        }

        println("=== MOEX 255д: сетка пирамидинг (добавка ₽ × глубина |Z|) @ 100k, пороги 0.8/0.7 ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        printBacktestRowWide("база 0.8/0.7 (без пир.)", baseline)
        println()
        println("PnL ₽ (строка = добавка номинала, столбец = |Z| для докупки):")
        print("       ")
        for (depth in depthSteps) {
            print(String.format(Locale.US, "%7.2f", depth))
        }
        println()
        for (addRub in addSteps) {
            val addLabel = if (addRub >= 1000) "${(addRub / 1000).roundToInt()}k" else addRub.roundToInt().toString()
            print(String.format(Locale.US, "%5s |", addLabel))
            for (depth in depthSteps) {
                val pnl = cells.first { it.pyramidAddNotionalRub == addRub && it.pyramidZDepth == depth }
                    .metrics.totalPnlRubApprox
                print(String.format(Locale.US, "%7.0f", pnl))
            }
            println()
        }
        println()
        println("Топ-10 комбинаций по PnL (чистый):")
        printPyramidGridTopHeader()
        cells.sortedByDescending { it.metrics.totalPnlRubApprox }.take(10).forEach { cell ->
            printPyramidGridTopRow(cell, baseline.totalPnlRubApprox)
        }
        val ranked = cells.sortedByDescending { it.metrics.totalPnlRubApprox }
        val best = ranked.first()
        val worst = ranked.last()
        println("---")
        println(
            "Лучшая: +${(best.pyramidAddNotionalRub / 1000).roundToInt()}k @ |Z|≥${fmt(best.pyramidZDepth)} → " +
                "PnL ${fmt(best.metrics.totalPnlRubApprox)} ₽, сделок ${best.metrics.closedTrades.size}, " +
                "max DD ${fmt(best.metrics.maxDrawdownRubApprox)} ₽, WR ${fmt(best.metrics.winRate)}%"
        )
        println(
            "Δ к базе: ${fmt(best.metrics.totalPnlRubApprox - baseline.totalPnlRubApprox)} ₽ · " +
                "худшая ячейка PnL ${fmt(worst.metrics.totalPnlRubApprox)} ₽ " +
                "(+${(worst.pyramidAddNotionalRub / 1000).roundToInt()}k @ |Z|≥${fmt(worst.pyramidZDepth)})"
        )
        assertTrue(cells.size == addSteps.size * depthSteps.size)
        assertTrue(ranked.first().metrics.totalPnlRubApprox >= baseline.totalPnlRubApprox)
    }

    /**
     * Пирамидинг только при |Z| строго выше 1.0 (поздняя докупка).
     * Сравнение с эталоном +50k @ |Z|≥1.0 на тех же размерах добавки.
     */
    @Test
    fun moexBacktest_255d_pyramid_depth_gt1() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val notional = BACKTEST_ACCOUNT_100K_RUB
        val baseline = runBacktest(points, THRESH_08_07, notional)!!
        val addSteps = pyramidAddNotionalGridSteps()
        val depthSteps = pyramidZDepthGridStepsGt1()
        val refAt1 = addSteps.associateWith { addRub ->
            runBacktest(
                points = points,
                thresholds = THRESH_08_07,
                accountSizeRub = notional,
                simOptions = ZStrategySimOptions(
                    pyramidAddNotionalRub = addRub,
                    pyramidZDepth = 1.0
                )
            )!!
        }
        val cells = mutableListOf<PyramidGridCell>()
        for (addRub in addSteps) {
            for (depth in depthSteps) {
                val m = runBacktest(
                    points = points,
                    thresholds = THRESH_08_07,
                    accountSizeRub = notional,
                    simOptions = ZStrategySimOptions(
                        pyramidAddNotionalRub = addRub,
                        pyramidZDepth = depth
                    )
                )!!
                cells += PyramidGridCell(addRub, depth, m)
            }
        }

        println("=== MOEX 255д: пирамидинг при |Z| > 1.0 (добавка × глубина) @ 100k 0.8/0.7 ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        printBacktestRowWide("база без пир.", baseline)
        for (addRub in addSteps) {
            val addK = (addRub / 1000).roundToInt()
            printBacktestRowWide("эталон +${addK}k @ |Z|≥1.00", refAt1.getValue(addRub))
        }
        println()
        println("PnL ₽ (строка = добавка, столбец = порог докупки |Z|,):")
        print("       ")
        for (depth in depthSteps) {
            print(String.format(Locale.US, "%7.2f", depth))
        }
        println("  |  Δ vs +50k@1.0")
        for (addRub in addSteps) {
            val addK = (addRub / 1000).roundToInt()
            val ref = refAt1.getValue(addRub)
            print(String.format(Locale.US, "%5sk |", addK))
            for (depth in depthSteps) {
                val pnl = cells.first { it.pyramidAddNotionalRub == addRub && it.pyramidZDepth == depth }
                    .metrics.totalPnlRubApprox
                print(String.format(Locale.US, "%7.0f", pnl))
            }
            val bestGt1 = cells.filter { it.pyramidAddNotionalRub == addRub }
                .maxByOrNull { it.metrics.totalPnlRubApprox }!!
            val deltaVs1 = bestGt1.metrics.totalPnlRubApprox - ref.totalPnlRubApprox
            print(String.format(Locale.US, " %7.0f", deltaVs1))
            println()
        }
        println()
        println("Топ-12 комбинаций (только |Z| > 1.0):")
        printPyramidGridTopHeader()
        cells.sortedByDescending { it.metrics.totalPnlRubApprox }.take(12).forEach { cell ->
            printPyramidGridTopRow(cell, baseline.totalPnlRubApprox)
        }
        println("---")
        println("Сравнение лучшей глубины >1 vs эталон @1.0 (по размеру добавки):")
        for (addRub in addSteps) {
            val addK = (addRub / 1000).roundToInt()
            val ref = refAt1.getValue(addRub)
            val best = cells.filter { it.pyramidAddNotionalRub == addRub }
                .maxByOrNull { it.metrics.totalPnlRubApprox }!!
            println(
                String.format(
                    Locale.US,
                    "  +%dk: лучш. |Z|≥%.2f → PnL %.0f ₽ (сделок %d, DD %.0f) · @1.0 → %.0f ₽ · Δ %.0f ₽",
                    addK,
                    best.pyramidZDepth,
                    best.metrics.totalPnlRubApprox,
                    best.metrics.closedTrades.size,
                    best.metrics.maxDrawdownRubApprox,
                    ref.totalPnlRubApprox,
                    best.metrics.totalPnlRubApprox - ref.totalPnlRubApprox
                )
            )
        }
        val globalBest = cells.maxByOrNull { it.metrics.totalPnlRubApprox }!!
        val globalBestRef = refAt1.getValue(globalBest.pyramidAddNotionalRub)
        println("---")
        println(
            "Глобально лучшая (|Z|>1): +${(globalBest.pyramidAddNotionalRub / 1000).roundToInt()}k @ |Z|≥${fmt(globalBest.pyramidZDepth)} → " +
                "PnL ${fmt(globalBest.metrics.totalPnlRubApprox)} ₽ (Δ к базе ${fmt(globalBest.metrics.totalPnlRubApprox - baseline.totalPnlRubApprox)} ₽, " +
                "Δ к @1.0 ${fmt(globalBest.metrics.totalPnlRubApprox - globalBestRef.totalPnlRubApprox)} ₽)"
        )
        assertTrue(cells.size == addSteps.size * depthSteps.size)
        assertTrue(cells.all { it.metrics.closedTrades.size == baseline.closedTrades.size })
    }

    @Test
    fun moexBacktest_255d_leverage1_vs_7_prodLike_threshold08_07() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val mLev1 = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = DEFAULT_STRATEGY_TEST_ACCOUNT_RUB,
            leverageForLots = 1.0,
            commissionPercentPerSide = BACKTEST_COMMISSION_PCT_PER_SIDE,
        )!!
        val mLev7 = runBacktest(
            points = points,
            thresholds = THRESH_08_07,
            accountSizeRub = DEFAULT_STRATEGY_TEST_ACCOUNT_RUB,
            leverageForLots = 7.0,
            commissionPercentPerSide = BACKTEST_COMMISSION_PCT_PER_SIDE,
        )!!
        println("=== MOEX 255д: prod-like ${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()}k @ 0.8/0.7 — плечо лотов ×1 vs ×7 ===")
        println("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)")
        printBacktestRow("prod ×1 лоты", mLev1)
        printBacktestRow("prod ×7 лоты", mLev7)
        println("---")
        println(
            "Δ PnL (×7 − ×1): ${fmt(mLev7.totalPnlRubApprox - mLev1.totalPnlRubApprox)} ₽ · " +
                "Δ max DD: ${fmt(mLev7.maxDrawdownRubApprox - mLev1.maxDrawdownRubApprox)} ₽"
        )
        assertTrue(mLev7.closedTrades.size == mLev1.closedTrades.size)
    }

    @Test
    fun moexBacktest_255d_dual50k_vs_single100k() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val m50_07 = runBacktest(points, THRESH_08_07, BACKTEST_ACCOUNT_50K_RUB)!!
        val m50_05 = runBacktest(points, THRESH_08_05, BACKTEST_ACCOUNT_50K_RUB)!!
        val m100_07 = runBacktest(points, THRESH_08_07, BACKTEST_ACCOUNT_100K_RUB)!!
        val m100_05 = runBacktest(points, THRESH_08_05, BACKTEST_ACCOUNT_100K_RUB)!!

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

    @Test
    fun moexBacktest_255d_thresholdGapSweep_0_2_5() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val steps = thresholdSweepSteps(min = 0.2, max = 2.5)
        val cells = runThresholdGapSweep(points, steps)
        assertTrue("Нет результатов сетки", cells.isNotEmpty())
        printThresholdGapSweepReport(
            title = "MOEX ISS 255д — сетка порогов 0.2…2.5 (prod-like)",
            rangeLabel = "${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров 15м)",
            paramsLabel = "${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()}k ₽ prod-like, комиссия ${BACKTEST_COMMISSION_PCT_PER_SIDE}%/сторона, Z+guard",
            cells = cells,
        )
    }

    /**
     * Гипотеза: раздельные пороги входа (|Z| выше) и выхода (|Z| ниже) дают больше PnL, чем «узкий» фикс. зазор.
     * Сетка 0.2…2.5 шаг 0.1, exit < entry.
     *
     * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexTodayBacktestTest.moexBacktest_255d_asymmetricThresholdHypothesis_0_2_5`
     */
    @Test
    fun moexBacktest_255d_asymmetricThresholdHypothesis_0_2_5() = runBlocking {
        val (_, points) = loadTatn15mPoints()
        val steps = thresholdSweepSteps(min = 0.2, max = 2.5)
        val cells = runThresholdGapSweep(points, steps)
        assertTrue("Нет результатов сетки", cells.isNotEmpty())

        val best = cells.maxByOrNull { it.pnl }!!
        val baseline075 = cells.find { near(it.entry, 0.7) && near(it.exit, 0.5) }
        val baseline087 = cells.find { near(it.entry, 0.8) && near(it.exit, 0.7) }

        // Для каждого entry — лучший exit и «минимальный зазор» entry−0.1
        val bestExitPerEntry = cells.groupBy { it.entry }.mapValues { (_, g) -> g.maxByOrNull { it.pnl }!! }
        val minGapPerEntry = steps.associateWith { entry ->
            val tightExit = (entry - 0.1).coerceAtLeast(steps.first())
            cells.find { near(it.entry, entry) && near(it.exit, tightExit) }
        }.filterValues { it != null }.mapValues { it.value!! }

        val tunedBeatsTight = bestExitPerEntry.count { (entry, bestCell) ->
            val tight = minGapPerEntry[entry] ?: return@count false
            bestCell.pnl > tight.pnl + 1.0
        }
        val tunedTotal = bestExitPerEntry.size
        val profitable = cells.count { it.pnl > 0.0 }
        val profitablePct = 100.0 * profitable / cells.size

        val top10 = cells.sortedByDescending { it.pnl }.take(10)
        val top10AvgGap = top10.map { it.gap }.average()

        val report = buildString {
            appendLine("=== Гипотеза: раздельные пороги вход/выход (255д MOEX, prod-like) ===")
            appendLine("Сетка: вход и выход 0.2…2.5 шаг 0.1, exit < entry")
            appendLine(
                "Prod-like: ${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()}k ₽, " +
                    "${DEFAULT_STRATEGY_TEST_CAPITAL_USAGE_PERCENT.toInt()}%, ×7 лоты, " +
                    "комиссия ${BACKTEST_COMMISSION_PCT_PER_SIDE}%, slip ${DEFAULT_STRATEGY_TEST_SLIPPAGE_SPREAD_PTS}п, Z+guard"
            )
            appendLine("Ряд: ${points.first().tradeDate} … ${points.last().tradeDate} (${points.size} баров)")
            appendLine("Комбинаций: ${cells.size}, прибыльных: $profitable (${fmt1(profitablePct)}%)")
            appendLine()
            appendLine("BEST: вход ±${fmt(best.entry)} / выход ±${fmt(best.exit)} Δ=${fmt(best.gap)}")
            appendLine("  PnL ${fmt(best.pnl)} ₽, сделок ${best.trades}, max DD ${fmt(best.maxDd)} ₽")
            baseline075?.let {
                appendLine("  baseline 0.7/0.5: PnL ${fmt(it.pnl)} ₽ (${fmt(it.pnl - best.pnl)} vs best)")
            }
            baseline087?.let {
                appendLine("  baseline 0.8/0.7: PnL ${fmt(it.pnl)} ₽")
            }
            appendLine()
            appendLine("Гипотеза: подбор exit отдельно от entry")
            appendLine("  Для $tunedTotal уровней entry лучший exit даёт >+1₽ vs tight Δ=0.1: $tunedBeatsTight раз")
            appendLine("  Средний зазор Δ в TOP-10: ${fmt1(top10AvgGap)}")
            appendLine()
            appendLine("BEST PER GAP Δ:")
            appendLine(String.format(Locale.US, "%4s %5s %5s %12s %7s %10s", "Δ", "вход", "выход", "PnL ₽", "сделок", "DD ₽"))
            cells.groupBy { it.gap }.toSortedMap().forEach { (gap, group) ->
                val row = group.maxByOrNull { it.pnl }!!
                appendLine(
                    String.format(
                        Locale.US,
                        "%4s %5s %5s %12s %7d %10s",
                        fmt(gap), fmt(row.entry), fmt(row.exit), fmt(row.pnl), row.trades, fmt(row.maxDd),
                    )
                )
            }
            appendLine()
            appendLine("TOP 15:")
            cells.sortedByDescending { it.pnl }.take(15).forEach { c ->
                appendLine(
                    "  ±${fmt(c.entry)}/±${fmt(c.exit)} Δ=${fmt(c.gap)} → ${fmt(c.pnl)} ₽ " +
                        "(${c.trades} сд., DD ${fmt(c.maxDd)})"
                )
            }
            appendLine()
            appendLine("Примеры: один entry — разные exit (0.7):")
            cells.filter { near(it.entry, 0.7) }.sortedByDescending { it.pnl }.take(8).forEach { c ->
                appendLine("  exit ±${fmt(c.exit)} Δ=${fmt(c.gap)} → ${fmt(c.pnl)} ₽ (${c.trades} сд.)")
            }
        }
        println(report)
        java.io.File("/tmp/moex_threshold_hypothesis.txt").writeText(report)
        printThresholdGapSweepReport(
            title = "MOEX ISS 255д — сетка 0.2…2.5 prod-like (полный отчёт)",
            rangeLabel = "${points.first().tradeDate} … ${points.last().tradeDate}",
            paramsLabel = "${DEFAULT_STRATEGY_TEST_ACCOUNT_RUB.toInt()}k prod-like",
            cells = cells,
        )

        assertTrue("Лучшая пара должна быть прибыльной", best.pnl > 0.0)
        assertTrue(
            "Раздельная настройка exit должна иногда побеждать tight Δ=0.1",
            tunedBeatsTight > tunedTotal / 4,
        )
    }

    private fun runThresholdGapSweep(
        points: List<DataPoint>,
        steps: List<Double>,
    ): List<ThresholdSweepCell> {
        val cells = mutableListOf<ThresholdSweepCell>()
        for (entry in steps) {
            for (exit in steps) {
                if (exit >= entry) continue
                val m = runBacktest(
                    points = points,
                    thresholds = DynamicThresholds(entry, exit, null),
                ) ?: continue
                cells += ThresholdSweepCell(entry, exit, m)
            }
        }
        return cells
    }

    private fun near(a: Double, b: Double, eps: Double = 0.011): Boolean = kotlin.math.abs(a - b) < eps

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun runBacktest(
        points: List<DataPoint>,
        thresholds: DynamicThresholds,
        accountSizeRub: Double = DEFAULT_STRATEGY_TEST_ACCOUNT_RUB,
        leverageForLots: Double = 7.0,
        capitalUsagePercent: Double = DEFAULT_STRATEGY_TEST_CAPITAL_USAGE_PERCENT,
        commissionPercentPerSide: Double = BACKTEST_COMMISSION_PCT_PER_SIDE,
        exitMode: ZStrategyExitMode = ZStrategyExitMode.FixedThreshold,
        zPeakTrailZ: Double = DEFAULT_STRATEGY_TEST_Z_PEAK_TRAIL,
        entryPullbackZ: Double = 0.0,
        simOptions: ZStrategySimOptions = defaultProdLikeSimOptions(),
    ): PortfolioMetrics? = buildProdLikeStrategySimMetrics(
        points = points,
        thresholds = thresholds,
        accountSizeRub = accountSizeRub,
        capitalUsagePercent = capitalUsagePercent,
        leverageForLots = leverageForLots,
        commissionPercentPerSide = commissionPercentPerSide,
        periodDescription = "MOEX ${PORTFOLIO_M15_LOOKBACK_DAYS}д prod-like",
        compoundReturns = false,
        exitMode = exitMode,
        zPeakTrailZ = zPeakTrailZ,
        entryPullbackZ = entryPullbackZ,
        simOptions = simOptions,
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

    private fun printBacktestHeaderWide() {
        println(
            String.format(
                Locale.US,
                "%-28s | %5s | %12s | %12s | %10s | %6s",
                "вариант",
                "сделок",
                "PnL ₽",
                "max DD ₽",
                "комис. ₽",
                "WR %"
            )
        )
        println("-".repeat(88))
    }

    private fun printBacktestRowWide(label: String, m: PortfolioMetrics) {
        println(
            String.format(
                Locale.US,
                "%-28s | %5d | %12.2f | %12.2f | %10.2f | %6.1f",
                label,
                m.closedTrades.size,
                m.totalPnlRubApprox,
                m.maxDrawdownRubApprox,
                m.totalCommissionRub,
                m.winRate
            )
        )
    }

    private fun pullbackTrailGridSteps(): List<Double> =
        listOf(0.03, 0.05, 0.07, 0.09, 0.11, 0.13, 0.15)

    /** Добавка номинала при пирамидинге (₽), стартовая позиция 100k. */
    private fun pyramidAddNotionalGridSteps(): List<Double> =
        listOf(25_000.0, 50_000.0, 75_000.0, 100_000.0)

    /** Глубина |Z| для одной докупки (должна быть ≥ порога входа 0.8). */
    private fun pyramidZDepthGridSteps(): List<Double> =
        listOf(0.85, 0.90, 0.95, 1.00, 1.10, 1.20)

    /** Докупка только при более глубоком экстремуме Z (строго > 1.0). */
    private fun pyramidZDepthGridStepsGt1(): List<Double> =
        listOf(1.05, 1.10, 1.15, 1.20, 1.25, 1.30, 1.40, 1.50, 1.75, 2.00)

    private data class PullbackGridCell(
        val entryPullbackZ: Double,
        val zPeakTrailZ: Double,
        val metrics: PortfolioMetrics
    )

    private data class PyramidGridCell(
        val pyramidAddNotionalRub: Double,
        val pyramidZDepth: Double,
        val metrics: PortfolioMetrics
    )

    private fun returnOnCapitalPercent(m: PortfolioMetrics, capitalRub: Double): Double =
        if (capitalRub > 0) m.totalPnlRubApprox / capitalRub * 100.0 else 0.0

    private fun printPyramidCapitalCompareHeader() {
        println(
            String.format(
                Locale.US,
                "%-26s | %5s | %10s | %8s | %8s | %8s | %8s | %7s",
                "вариант",
                "сделок",
                "PnL ₽",
                "ret 100k",
                "ret peak",
                "DD/100k",
                "DD/peak",
                "PnL/DD"
            )
        )
        println("-".repeat(98))
    }

    private fun printPyramidCapitalCompareRow(
        label: String,
        m: PortfolioMetrics,
        initialCapitalRub: Double,
        peakCapitalRub: Double,
    ) {
        val retInitial = returnOnCapitalPercent(m, initialCapitalRub)
        val retPeak = returnOnCapitalPercent(m, peakCapitalRub)
        val ddInitialPct = if (initialCapitalRub > 0) m.maxDrawdownRubApprox / initialCapitalRub * 100.0 else 0.0
        val ddPeakPct = if (peakCapitalRub > 0) m.maxDrawdownRubApprox / peakCapitalRub * 100.0 else 0.0
        val pnlPerDd = if (m.maxDrawdownRubApprox > 0) m.totalPnlRubApprox / m.maxDrawdownRubApprox else 0.0
        println(
            String.format(
                Locale.US,
                "%-26s | %5d | %10.0f | %7.2f%% | %7.2f%% | %6.1f%% | %6.1f%% | %7.2f",
                label,
                m.closedTrades.size,
                m.totalPnlRubApprox,
                retInitial,
                retPeak,
                ddInitialPct,
                ddPeakPct,
                pnlPerDd
            )
        )
    }

    private fun fmtPct(v: Double): String = String.format(Locale.US, "%+.2f", v)

    private fun printPyramidGridTopHeader() {
        println(
            String.format(
                Locale.US,
                "%-22s | %5s | %12s | %12s | %10s | %6s | %10s",
                "добавка / |Z|",
                "сделок",
                "PnL ₽",
                "max DD ₽",
                "комис. ₽",
                "WR %",
                "Δ vs база"
            )
        )
        println("-".repeat(96))
    }

    private fun printPyramidGridTopRow(cell: PyramidGridCell, baselinePnlRub: Double) {
        val addK = (cell.pyramidAddNotionalRub / 1000).roundToInt()
        val m = cell.metrics
        println(
            String.format(
                Locale.US,
                "%-22s | %5d | %12.2f | %12.2f | %10.2f | %6.1f | %10.2f",
                "+${addK}k @ |Z|≥${fmt(cell.pyramidZDepth)}",
                m.closedTrades.size,
                m.totalPnlRubApprox,
                m.maxDrawdownRubApprox,
                m.totalCommissionRub,
                m.winRate,
                m.totalPnlRubApprox - baselinePnlRub
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
        val points = prepareMoexPointsForProdLikeSim(entities.map { it.toDataPoint() })
        assertTrue("Мало точек для Z", points.size >= 2)
        return today to points
    }

    private fun runPortfolioMetrics(
        points: List<DataPoint>,
        thresholds: DynamicThresholds,
        today: LocalDate,
    ): PortfolioMetrics? = buildProdLikeStrategySimMetrics(
        points = points,
        thresholds = thresholds,
        periodDescription = "MOEX sweep $today prod-like",
    )

    private fun thresholdSweepSteps(min: Double = 0.0, max: Double = SWEEP_MAX): List<Double> {
        val steps = mutableListOf<Double>()
        var v = min
        while (v <= max + 1e-9) {
            steps += (v * 10).roundToInt() / 10.0
            v += SWEEP_STEP
        }
        return steps
    }

    private data class ThresholdSweepCell(
        val entry: Double,
        val exit: Double,
        val metrics: PortfolioMetrics,
    ) {
        val gap: Double = ((entry - exit) * 10).roundToInt() / 10.0
        val pnl: Double get() = metrics.totalPnlRubApprox
        val trades: Int get() = metrics.closedTrades.size
        val maxDd: Double get() = metrics.maxDrawdownRubApprox
    }

    private fun printThresholdGapSweepReport(
        title: String,
        rangeLabel: String,
        paramsLabel: String,
        cells: List<ThresholdSweepCell>,
    ) {
        println("=== $title ===")
        println(rangeLabel)
        println(paramsLabel)
        println("Валидных комбинаций (exit < entry): ${cells.size}")
        val best = cells.maxByOrNull { it.pnl }!!
        println()
        println("BEST OVERALL: вход ±${fmt(best.entry)} / выход ±${fmt(best.exit)} Δ=${fmt(best.gap)}")
        println(
            "  PnL ${fmt(best.pnl)} ₽, сделок ${best.trades}, max DD ${fmt(best.maxDd)} ₽"
        )
        val baseline = cells.find { kotlin.math.abs(it.entry - 0.8) < 0.01 && kotlin.math.abs(it.exit - 0.7) < 0.01 }
        baseline?.let {
            println(
                "  baseline 0.8/0.7: PnL ${fmt(it.pnl)} ₽, сделок ${it.trades}"
            )
        }
        println()
        println("BEST PER GAP (вход − выход):")
        println(String.format(Locale.US, "%4s %5s %5s %12s %7s %10s", "Δ", "вход", "выход", "PnL ₽", "сделок", "DD ₽"))
        cells.groupBy { it.gap }
            .toSortedMap()
            .forEach { (gap, group) ->
                val row = group.maxByOrNull { it.pnl }!!
                println(
                    String.format(
                        Locale.US,
                        "%4s %5s %5s %12s %7d %10s",
                        fmt(gap),
                        fmt(row.entry),
                        fmt(row.exit),
                        fmt(row.pnl),
                        row.trades,
                        fmt(row.maxDd),
                    )
                )
            }
        println()
        println("TOP 10:")
        cells.sortedByDescending { it.pnl }.take(10).forEach { c ->
            println(
                "  ±${fmt(c.entry)}/±${fmt(c.exit)} Δ=${fmt(c.gap)} → ${fmt(c.pnl)} ₽ " +
                    "(${c.trades} сд., DD ${fmt(c.maxDd)})"
            )
        }
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
