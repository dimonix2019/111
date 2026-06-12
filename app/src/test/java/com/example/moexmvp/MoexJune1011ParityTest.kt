package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * MOEX 10–11.06.2026: parity ключевых SHORT round-trip с журналом после spread-guard.
 * `./gradlew testDebugUnitTest --tests com.example.moexmvp.MoexJune1011ParityTest`
 */
class MoexJune1011ParityTest {

    private val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)

    @Test
    fun june1011_guardedSim_matchesJournalCoreShortRoundTrips() = runBlocking {
        val from = LocalDate.of(2026, 3, 1)
        val till = LocalDate.of(2026, 6, 15)
        val raw = fetchPortfolio15mSpreadEntities(from, till).map { it.toDataPoint() }
        val sim = prepareM15PointsForZStrategySim(raw)
        val metrics = buildZStrategyPortfolioMetrics(
            points = sim,
            thresholds = thresholds,
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "june1011-parity",
        ) ?: error("metrics")

        assertFalse(
            metrics.closedTrades.any {
                it.direction == ZStrategyPosition.Short && it.entryDate == "2026-06-10 18:00"
            },
        )
        val longHold = metrics.closedTrades.firstOrNull {
            val h = simTradeDurationMillis(it.entryDate, it.exitDate)?.toDouble()?.div(3_600_000.0)
            h != null && h >= 18.0 && h <= 20.0
        }
        assertTrue("unexpected ~19h trade: $longHold", longHold == null)

        val juneShorts = metrics.closedTrades.filter {
            it.direction == ZStrategyPosition.Short &&
                (it.entryDate.startsWith("2026-06-10") || it.entryDate.startsWith("2026-06-11"))
        }
        assertEquals(4, juneShorts.size)

        val byEntry = juneShorts.associateBy { it.entryDate }
        assertEquals("2026-06-10 17:45", byEntry["2026-06-10 11:45"]?.exitDate)
        assertEquals("2026-06-11 13:15", byEntry["2026-06-11 12:00"]?.exitDate)
        assertEquals("2026-06-11 23:15", byEntry["2026-06-11 14:30"]?.exitDate)
    }
}
