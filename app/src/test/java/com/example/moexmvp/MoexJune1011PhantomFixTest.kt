package com.example.moexmvp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** MOEX 10–11.06.2026: spread-guard убирает фантом SHORT ~19ч (18:00 → 11.06 13:15). */
class MoexJune1011PhantomFixTest {

  private val thresholds = DynamicThresholds(entry = 0.7, exit = 0.5, calculatedDate = null)

  @Test
  fun spreadGuardZ_removesPhantom19hShortOnJune1011() = runBlocking {
    val from = LocalDate.of(2026, 3, 1)
    val till = LocalDate.of(2026, 6, 15)
    val entities = fetchPortfolio15mSpreadEntities(from, till)
    val raw = entities.map { it.toDataPoint() }
    val guarded = prepareM15PointsForZStrategySim(raw)

    val metrics = buildZStrategyPortfolioMetrics(
      points = guarded,
      thresholds = thresholds,
      notionalRub = 100_000.0,
      leverage = 7.0,
      commissionPercentPerSide = 0.04,
      periodDescription = "june1011-guard",
    ) ?: error("metrics")

    val phantom = metrics.closedTrades.firstOrNull {
      it.direction == ZStrategyPosition.Short &&
        it.entryDate == "2026-06-10 18:00"
    }
    assertFalse("phantom 18:00 entry: $phantom", phantom != null)

    val longHold = metrics.closedTrades.firstOrNull {
      val h = simTradeDurationMillis(it.entryDate, it.exitDate)?.toDouble()?.div(3_600_000.0)
      h != null && h >= 18.0 && h <= 20.0
    }
    assertTrue("unexpected ~19h trade: $longHold", longHold == null)

    val juneShorts = metrics.closedTrades.filter {
      it.direction == ZStrategyPosition.Short &&
        (it.entryDate.startsWith("2026-06-10") || it.entryDate.startsWith("2026-06-11"))
    }
    println("june SHORT trades after spread-guard: ${juneShorts.size}")
    juneShorts.forEach { t ->
      val h = simTradeDurationMillis(t.entryDate, t.exitDate)?.div(3_600_000.0)
      println("  ${t.entryDate} → ${t.exitDate} (${String.format(Locale.US, "%.1f", h)}h)")
    }
  }
}
