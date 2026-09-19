package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * MOEX ISS → режимы Z [−2,0] / [0,+2], переходы, walk-forward прогноз.
 *
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexZRegimeMoexIntegrationTest`
 */
class MoexZRegimeMoexIntegrationTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun moexZRegime_analysis_255d_and_3y() = runBlocking {
        analyzeLookback("255д", PORTFOLIO_M15_LOOKBACK_DAYS)
        analyzeLookback("3г", 1095L)
    }

    private suspend fun analyzeLookback(label: String, lookbackDays: Long) {
        val today = LocalDate.now(zone)
        val from = today.minusDays(lookbackDays)
        val entities = fetchPortfolio15mSpreadEntitiesChunked(from, portfolioM15MoexFetchTillDate())
        assertTrue("Нет данных MOEX $label", entities.isNotEmpty())
        val points = prepareMoexPointsForProdLikeSim(entities.map { it.toDataPoint() })
        assertTrue("Мало баров $label", points.size >= 100)

        for (windowDays in listOf(10, 15)) {
            val report = buildZRegimeAnalysisReport(points, windowTradingDays = windowDays)!!
            val text = formatZRegimeAnalysisReport(report, windowDays)
            println("========== $label · окно ${windowDays}д ==========")
            println(text)

            val runs = zRegimeRunLengths(report.windows)
            val avgRun = runs.map { it.second }.average()
            val meanGap = meanCalendarDaysBetweenWindowStarts(report.windows)
            println("Средняя длина серии одного режима: ${"%.1f".format(avgRun)} окон (~${"%.0f".format(avgRun * (meanGap ?: (windowDays * 1.4)))} календ. дн.)")
            println("Топ серии: ${runs.sortedByDescending { it.second }.take(5).joinToString { "${zRegimeLabelRu(it.first)}×${it.second}" }}")
            println()

            // Апрель–июнь 2026 (наблюдения пользователя)
            val spring2026 = report.monthlyWindows.filter {
                it.windowId in listOf("2026-04", "2026-05", "2026-06")
            }
            if (spring2026.isNotEmpty()) {
                println("Апр–Июн 2026 (помесячно):")
                spring2026.forEach { w ->
                    println(
                        "  ${w.windowId}: meanZ=${"%.2f".format(w.meanZ)} " +
                            "[-2,0]=${"%.0f".format(w.bandShares.negativeBandShare * 100)}% " +
                            "[0,+2]=${"%.0f".format(w.bandShares.positiveBandShare * 100)}% " +
                            "→ ${zRegimeLabelRu(w.regime)}"
                    )
                }
                println()
            }
        }
    }
}
