package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexOpenTradePnlForecastTest {

    private fun m15Point(ts: Long, spread: Double, z: Double) = DataPoint(
        timestampMillis = ts,
        tradeDate = "2026-06-29 16:30",
        tatnClose = 576.0,
        tatnpClose = 542.0,
        spreadPercent = spread,
        diff = 0.0,
        zScore = z,
    )

    private fun openLongExec(entryZ: Double = -2.16, exitZ: Double = -1.45) = SandboxSpreadExecUi(
        tradeId = "D-001",
        signalType = StrategySignalType.EnterLong,
        zScore = entryZ,
        barTimestampMillis = 1_000L,
        executedAtMillis = 2_000L,
        entrySpreadPercent = 6.0,
        source = PortfolioExecSource.AUTO,
        directionLabel = "long",
        entryTimeMsk = "2026-06-29 16:30",
        longLegTicker = "TATN",
        shortLegTicker = "TATNP",
        longLegSideRu = "покупка 80 лот",
        shortLegSideRu = "продажа 80 лот",
        volumeText = "80+80",
        confirmLabel = "авто",
        correlationTag = "t",
        notificationIdsText = "—",
        legs = emptyList(),
        exitZDisplay = exitZ,
        quantityLots = 80,
        executionNotionalRub = 89_440.0,
    )

    @Test
    fun buildForecast_longAtZeroSpreadTarget_positiveGross() {
        val points = (0 until 60).map { i ->
            m15Point(i * 900_000L, spread = 6.5 + (i % 5) * 0.02, z = 0.0)
        }
        val forecast = buildOpenTradePnlForecast(
            exec = openLongExec(),
            points = points,
            entryThreshold = 1.8,
            exitThreshold = 1.3,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            executionMode = TinkoffExecutionMode.Prod,
        )
        assertNotNull(forecast)
        val atZero = forecast!!.rows.first { it.label.contains("Z = 0") }
        assertTrue(atZero.grossRub > 0.0)
        assertTrue(atZero.spreadDeltaPts > 0.0)
    }

    @Test
    fun buildForecast_marksExitLevel() {
        val points = (0 until 60).map { i ->
            m15Point(i * 900_000L, spread = 6.5, z = 0.0)
        }
        val forecast = buildOpenTradePnlForecast(
            exec = openLongExec(),
            points = points,
            entryThreshold = 1.8,
            exitThreshold = 1.3,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            executionMode = TinkoffExecutionMode.Prod,
        )!!
        val exitRow = forecast.rows.first { it.isExitLevel }
        assertEquals(-1.3, exitRow.zTarget, 0.001)
    }

    @Test
    fun buildForecast_entryRowNearZeroGross() {
        val points = (0 until 60).map { i ->
            m15Point(i * 900_000L, spread = 6.0 + (i % 3) * 0.01, z = 0.0)
        }
        val forecast = buildOpenTradePnlForecast(
            exec = openLongExec(entryZ = -2.16, exitZ = Double.NaN),
            points = points,
            entryThreshold = 1.8,
            exitThreshold = 1.3,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            executionMode = TinkoffExecutionMode.Prod,
        )!!
        val entryRow = forecast.rows.first { it.label.startsWith("Z₀") }
        assertEquals(0.0, entryRow.grossRub, 500.0)
    }
}
