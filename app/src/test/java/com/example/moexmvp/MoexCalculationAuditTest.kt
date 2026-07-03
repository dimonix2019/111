package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Сквозной аудит формул PnL, лот-сайзинга, Z и прогноза (Prod TATN/TATNP).
 */
class MoexCalculationAuditTest {

    @Test
    fun spreadPnlToRub_scalesNotionalBySpreadDeltaPoints() {
        assertEquals(894.4, spreadPnlToRubApprox(1.0, 89_440.0), 0.01)
        assertEquals(-447.2, spreadPnlToRubApprox(-0.5, 89_440.0), 0.01)
    }

    @Test
    fun openSpreadMtmPoints_longProfitWhenSpreadWidens_shortWhenNarrows() {
        assertEquals(0.5, openSpreadMtmPoints(StrategySignalType.EnterLong, 6.0, 6.5), 0.001)
        assertEquals(-0.5, openSpreadMtmPoints(StrategySignalType.EnterLong, 6.5, 6.0), 0.001)
        assertEquals(0.5, openSpreadMtmPoints(StrategySignalType.EnterShort, 6.5, 6.0), 0.001)
        assertEquals(-0.5, openSpreadMtmPoints(StrategySignalType.EnterShort, 6.0, 6.5), 0.001)
    }

    @Test
    fun prodPnlLeverage_isOne_sandboxUsesConfiguredLeverage() {
        assertEquals(1.0, portfolioPnlLeverageMultiplier(TinkoffExecutionMode.Prod, 7.0), 0.001)
        assertEquals(7.0, portfolioPnlLeverageMultiplier(TinkoffExecutionMode.Sandbox, 7.0), 0.001)
    }

    @Test
    fun brokerEnrichment_netEqualsGrossMinusEntryCommissionSameDay() {
        val exec = SandboxSpreadExecUi(
            tradeId = "P-80",
            signalType = StrategySignalType.EnterLong,
            zScore = -2.16,
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
            quantityLots = 80,
            executionNotionalRub = 89_440.0,
        )
        val broker = SpreadLegBrokerPnl(longLegYieldRub = 624.0, shortLegYieldRub = -494.0)
        val points = listOf(
            DataPoint(1_000L, "2026-06-29 16:30", 576.0, 542.0, 6.0, 0.0, -2.16),
            DataPoint(2_000L, "2026-06-29 18:00", 576.0, 542.0, 6.62, 0.0, 0.49),
        )
        val out = enrichOpenSandboxExecutions(
            executions = listOf(exec),
            points = points,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            pnlLeverage = 1.0,
            brokerLegPnl = broker,
        ).single()
        assertEquals(130.0, broker.netGrossRub, 0.01)
        assertEquals(624.0, out.legLongPnlSplitRubApprox, 0.01)
        assertEquals(-494.0, out.legShortPnlSplitRubApprox, 0.01)
        val (commRub, ovnRub) = portfolioTradeCommissionAndOvernightRub(
            notionalRub = 89_440.0,
            leverage = 1.0,
            commissionPercentPerSide = 0.04,
            entryDateLabel = "2026-06-29 16:30",
            exitDateLabel = "2026-06-29 18:00",
            includeExitCommission = false,
        )
        assertEquals(0.0, ovnRub, 0.01)
        assertEquals(130.0 - commRub - ovnRub, out.netPnlRubApprox, 0.05)
    }

    @Test
    fun userScenario_forecastNowMatchesBrokerFact_scenariosBelowUncalibratedMoex() {
        val points = (0 until 60).map { i ->
            DataPoint(
                timestampMillis = i * 900_000L,
                tradeDate = "2026-06-29 16:30",
                tatnClose = 576.0,
                tatnpClose = 542.0,
                spreadPercent = 6.62,
                diff = 0.0,
                zScore = 0.49,
            )
        }
        val exec = SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = StrategySignalType.EnterLong,
            zScore = -2.16,
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
            exitZDisplay = 0.49,
            quantityLots = 80,
            executionNotionalRub = 89_440.0,
            legLongPnlSplitRubApprox = 624.0,
            legShortPnlSplitRubApprox = -494.0,
            netPnlRubApprox = 94.22,
        )
        val forecast = buildOpenTradePnlForecast(
            exec = exec,
            points = points,
            entryThreshold = 1.8,
            exitThreshold = 1.3,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            executionMode = TinkoffExecutionMode.Prod,
        )!!
        assertTrue(forecast.calibratedFromBroker)
        val now = forecast.rows.first { it.isBrokerFact }
        assertEquals(130.0, now.grossRub, 0.01)
        assertEquals(94.22, now.netRubApprox, 0.05)

        val exitRow = forecast.rows.first { it.isExitLevel }
        val z0Row = forecast.rows.first { it.label.contains("Z = 0") }
        assertTrue(exitRow.grossRub < now.grossRub)
        assertTrue(z0Row.grossRub < now.grossRub)

        val uncalibrated = buildOpenTradePnlForecast(
            exec = exec.copy(
                legLongPnlSplitRubApprox = Double.NaN,
                legShortPnlSplitRubApprox = Double.NaN,
                netPnlRubApprox = Double.NaN,
            ),
            points = points,
            entryThreshold = 1.8,
            exitThreshold = 1.3,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            executionMode = TinkoffExecutionMode.Prod,
        )!!
        assertFalse(uncalibrated.calibratedFromBroker)
        val uncalZ0 = uncalibrated.rows.first { it.label.contains("Z = 0") }
        assertTrue(
            "MOEX μ+σ без калибровки завышает Z=0: ${uncalZ0.grossRub} vs ${z0Row.grossRub}",
            uncalZ0.grossRub > z0Row.grossRub * 2,
        )
    }

    @Test
    fun userScenario_forecastCalibratedFractions_matchLinearExtrapolation() {
        val entryZ = -2.16
        val currentZ = 0.49
        val brokerGross = 130.0
        val zDeltaCurrent = currentZ - entryZ
        val exitZ = -1.3
        val fractionExit = (exitZ - entryZ) / zDeltaCurrent
        assertEquals(0.3245, fractionExit, 0.001)
        assertEquals(brokerGross * fractionExit, 42.19, 0.5)
    }

    @Test
    fun lotSizing_100kFlatProd_matches626LotsAnd700kNotional() {
        val sizing = computeSpreadQuantityLots(
            SpreadLotSizingInput(
                cashRub = 100_000.0,
                priceTatN = 576.0,
                priceTatNp = 542.0,
                liquidPortfolioRub = 100_000.0,
                correctedMarginRub = 0.0,
                leverageForNotional = 7.0,
            )
        )
        assertEquals(626, sizing.quantityLots)
        assertTrue(sizing.executionNotionalRub in 695_000.0..705_000.0)
        assertEquals(626, sizing.lotsFromLeverage)
    }

    @Test
    fun lotSizing_openPosition_limitsByMarginHeadroomNotLeverage() {
        val sizing = computeSpreadQuantityLots(
            SpreadLotSizingInput(
                cashRub = 5_000.0,
                priceTatN = 576.0,
                priceTatNp = 542.0,
                liquidPortfolioRub = 100_000.0,
                correctedMarginRub = 50_000.0,
                leverageForNotional = 7.0,
            )
        )
        assertTrue(sizing.lotsFromLeverage > sizing.quantityLots)
        assertEquals(sizing.lotsFromMarginHeadroom, sizing.quantityLots)
        assertTrue(sizing.quantityLots in 40..50)
    }

    @Test
    fun zScoreAtSpread_matchesRollingFormula() {
        val spreads = (0 until 50).map { 6.0 + (it % 7) * 0.05 }
        val points = spreads.mapIndexed { i, s ->
            DataPoint(i * 900_000L, "2026-06-01", 576.0, 542.0, s, 0.0, 0.0)
        }
        val stats = rollingSpreadStatsAt(points, points.lastIndex)!!
        val lastSpread = points.last().spreadPercent
        val expectedZ = (lastSpread - stats.mean) / stats.stdDev
        val scored = applyZScoresRolling(points).last().zScore
        assertEquals(expectedZ, scored, 1e-9)
    }

    @Test
    fun resolveTradeNotional_prefersExecutionNotional_overDefault100k() {
        val exec = SandboxSpreadExecUi(
            tradeId = "P-1",
            signalType = StrategySignalType.EnterLong,
            zScore = -2.0,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 6.0,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-06-29 16:30",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "80+80",
            confirmLabel = "авто",
            correlationTag = "t",
            notificationIdsText = "—",
            legs = emptyList(),
            quantityLots = 80,
            executionNotionalRub = 89_440.0,
        )
        val points = listOf(
            DataPoint(1_000L, "2026-06-29 16:30", 576.0, 542.0, 6.0, 0.0, -2.0),
        )
        assertEquals(89_440.0, resolveTradeNotionalRubForPnl(exec, points), 0.01)
        val forecastPoints = (0 until 60).map { i ->
            DataPoint(i * 900_000L, "2026-06-29", 576.0, 542.0, 6.5, 0.0, 0.0)
        }
        assertNotNull(
            buildOpenTradePnlForecast(
                exec = exec,
                points = forecastPoints,
                entryThreshold = 1.8,
                exitThreshold = 1.3,
                leverage = 7.0,
                commissionPercentPerSide = 0.04,
                executionMode = TinkoffExecutionMode.Prod,
            ),
        )
        assertEquals(
            89_440.0,
            buildOpenTradePnlForecast(
                exec = exec,
                points = forecastPoints,
                entryThreshold = 1.8,
                exitThreshold = 1.3,
                leverage = 7.0,
                commissionPercentPerSide = 0.04,
                executionMode = TinkoffExecutionMode.Prod,
            )!!.notionalRub,
            0.01,
        )
    }

    @Test
    fun sandboxOpenMtm_usesLeverageOnNotional_prodDoesNot() {
        val points = listOf(
            DataPoint(1_000L, "a", 576.0, 542.0, 6.0, 0.0, -2.0),
            DataPoint(2_000L, "b", 576.0, 542.0, 6.5, 0.0, 0.0),
        )
        val exec = SandboxSpreadExecUi(
            tradeId = "S-1",
            signalType = StrategySignalType.EnterLong,
            zScore = -2.0,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 6.0,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-06-29 16:30",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "1+1",
            confirmLabel = "авто",
            correlationTag = "t",
            notificationIdsText = "—",
            legs = emptyList(),
            quantityLots = 1,
            executionNotionalRub = 1_118.0,
        )
        val sandbox = enrichOpenSandboxExecutions(
            executions = listOf(exec),
            points = points,
            notionalRub = 1_118.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            pnlLeverage = 7.0,
        ).single()
        val prod = enrichOpenSandboxExecutions(
            executions = listOf(exec),
            points = points,
            notionalRub = 1_118.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            pnlLeverage = 1.0,
        ).single()
        assertTrue(abs(sandbox.netPnlRubApprox) > abs(prod.netPnlRubApprox) * 5.0)
    }
}
