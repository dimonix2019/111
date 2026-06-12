package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Диагностика фантомной ~19ч сделки 10–11.06.2026 (SHORT 18:00 → 11.06 13:15).
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexJune1011PhantomDiagTest`
 */
class MoexJune1011PhantomDiagTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)

    @Test
    fun diagnose_june1011_shortRoundTripsAndZAt1745_1800() = runBlocking {
        val from = LocalDate.of(2026, 3, 1)
        val till = LocalDate.of(2026, 6, 15)
        val entities = fetchPortfolio15mSpreadEntities(from, till)
        val points = applyZScoresDefault(entities.map { it.toDataPoint() })

        fun zAt(label: String): Double? =
            points.firstOrNull { it.tradeDate == label }?.zScore

        val metrics = buildZStrategyPortfolioMetrics(
            points = points,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "diag",
        )

        println("=== JUNE 10-11 PHANTOM DIAG (MOEX ${from}…${till}, ${points.size} bars) ===")
        listOf(
            "2026-06-10 17:30",
            "2026-06-10 17:45",
            "2026-06-10 18:00",
            "2026-06-10 18:15",
        ).forEach { label ->
            val p = points.firstOrNull { it.tradeDate == label }
            if (p != null) {
                println("  $label Z=${String.format(Locale.US, "%.3f", p.zScore)} spread=${String.format(Locale.US, "%.3f", p.spreadPercent)}%")
            } else {
                println("  $label — нет бара")
            }
        }

        val prev1745 = points.firstOrNull { it.tradeDate == "2026-06-10 17:30" }
        val bar1745 = points.firstOrNull { it.tradeDate == "2026-06-10 17:45" }
        val bar1800 = points.firstOrNull { it.tradeDate == "2026-06-10 18:00" }
        if (prev1745 != null && bar1745 != null && bar1800 != null) {
            val sigExit = determineZStrategySignalBetweenBars(
                prev1745, bar1745, ZStrategyPosition.Short, thresholds,
            )
            val sigEntry = determineZStrategySignalBetweenBars(
                bar1745, bar1800, ZStrategyPosition.Flat, thresholds,
            )
            println("  signal 17:45 exitShort=$sigExit (prevZ=${prev1745.zScore}, z=${bar1745.zScore})")
            println("  signal 18:00 enterShort=$sigEntry (prevZ=${bar1745.zScore}, z=${bar1800.zScore})")
            println("  consecutive 17:45→18:00=${isConsecutiveM15Bar(bar1745, bar1800)}")
        }

        val metricsAlt = run {
            val tweaked = points.map { p ->
                if (p.tradeDate == "2026-06-10 18:00") p.copy(spreadPercent = bar1745?.spreadPercent ?: p.spreadPercent) else p
            }
            val scored = applyZScoresDefault(tweaked.map { it.copy(zScore = 0.0) })
            buildZStrategyPortfolioMetrics(
                points = scored,
                thresholds = thresholds,
                notionalRub = 100_000.0,
                leverage = 7.0,
                commissionPercentPerSide = 0.04,
                periodDescription = "diag-alt",
            )
        }
        val z1800Alt = metricsAlt?.let {
            applyZScoresDefault(
                points.map { if (it.tradeDate == "2026-06-10 18:00") it.copy(spreadPercent = bar1745?.spreadPercent ?: it.spreadPercent, zScore = 0.0) else it.copy(zScore = 0.0) },
            ).firstOrNull { it.tradeDate == "2026-06-10 18:00" }?.zScore
        }
        println("  Z@18:00 if spread=17:45 (${bar1745?.spreadPercent}): $z1800Alt")

        listOf(
            "2026-06-10 06:45",
            "2026-06-10 10:00",
            "2026-06-10 11:45",
            "2026-06-11 12:00",
            "2026-06-11 13:15",
            "2026-06-11 14:00",
        ).forEach { label ->
            val p = points.firstOrNull { it.tradeDate == label }
            if (p != null) {
                println("  $label Z=${String.format(Locale.US, "%.3f", p.zScore)} spread=${String.format(Locale.US, "%.3f", p.spreadPercent)}%")
            }
        }

        println("--- closed trades (SHORT, exit 10-11.06) ---")
        metrics?.closedTrades.orEmpty()
            .filter { it.direction == ZStrategyPosition.Short }
            .filter {
                it.entryDate.startsWith("2026-06-10") || it.entryDate.startsWith("2026-06-11") ||
                    it.exitDate.startsWith("2026-06-10") || it.exitDate.startsWith("2026-06-11")
            }
            .forEach { t ->
                val h = simTradeDurationMillis(t.entryDate, t.exitDate)?.div(3_600_000.0)
                println("  ${t.entryDate} → ${t.exitDate}  dur=${h?.let { String.format(Locale.US, "%.1f", it) }}h")
            }
    }
}
