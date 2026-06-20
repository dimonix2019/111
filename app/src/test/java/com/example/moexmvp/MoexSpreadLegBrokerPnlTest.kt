package com.example.moexmvp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexSpreadLegBrokerPnlTest {
    @Test
    fun parseSpreadLegBrokerPnl_readsExpectedYieldPerTicker() {
        val portfolio = JSONObject(
            """
            {
              "positions": [
                {
                  "ticker": "TATN",
                  "expectedYield": {"units": "-31", "nano": -910000000, "currency": "rub"}
                },
                {
                  "ticker": "TATNP",
                  "expectedYield": {"units": "40", "nano": 0, "currency": "rub"}
                }
              ]
            }
            """.trimIndent()
        )
        val pnl = parseSpreadLegBrokerPnl(portfolio, StrategySignalType.EnterLong)!!
        assertEquals(-31.91, pnl.longLegYieldRub, 0.02)
        assertEquals(40.0, pnl.shortLegYieldRub, 0.01)
        assertEquals(8.09, pnl.netGrossRub, 0.03)
    }

    @Test
    fun enrichOpenSandboxExecutions_worksWithoutMoexWhenBrokerSnapshotPresent() {
        val exec = SandboxSpreadExecUi(
            tradeId = "P-002",
            signalType = StrategySignalType.EnterLong,
            zScore = -0.86,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 0.0,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-06-16 07:45",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка 10 лот",
            shortLegSideRu = "продажа 10 лот",
            volumeText = "10+10 лот",
            confirmLabel = "авто",
            correlationTag = "tag",
            notificationIdsText = "—",
            legs = emptyList(),
            quantityLots = 10,
            executionNotionalRub = 11_190.0,
        )
        val broker = SpreadLegBrokerPnl(
            longLegYieldRub = -31.91,
            shortLegYieldRub = 40.0,
            longLegPriceRub = 572.4,
            shortLegPriceRub = 538.7,
            longLegQuantity = 10,
            shortLegQuantity = -10,
        )
        val out = enrichOpenSandboxExecutions(
            executions = listOf(exec),
            points = emptyList(),
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            pnlLeverage = 1.0,
            brokerLegPnl = broker,
        ).single()
        assertEquals(-31.91, out.legLongPnlSplitRubApprox, 0.02)
        assertEquals(40.0, out.legShortPnlSplitRubApprox, 0.01)
        assertEquals(6.26, out.entrySpreadPercent, 0.05)
    }

    @Test
    fun portfolioStaleMoexWarning_mentionsBrokerWhenProdPnlReady() {
        val stale = listOf(
            DataPoint(1_000L, "2026-06-16 08:45", 572.0, 538.0, 6.2, 0.0, -1.0),
        )
        val msg = portfolioStaleMoexWarning(stale, prodBrokerPnlReady = true)
        assertTrue(msg!!.contains("боевого счёта"))
    }

    @Test
    fun enrichOpenSandboxExecutions_usesBrokerLegPnlOnProd() {
        val exec = SandboxSpreadExecUi(
            tradeId = "P-001",
            signalType = StrategySignalType.EnterLong,
            zScore = -0.86,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 6.20,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-06-16 07:45",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка 10 лот",
            shortLegSideRu = "продажа 10 лот",
            volumeText = "10+10 лот",
            confirmLabel = "авто",
            correlationTag = "tag",
            notificationIdsText = "—",
            legs = emptyList(),
            quantityLots = 10,
            executionNotionalRub = 11_190.0,
        )
        val points = listOf(
            DataPoint(1_000L, "2026-06-16 07:45", 572.4, 538.7, 6.20, 0.0, -0.86),
            DataPoint(2_000L, "2026-06-16 09:15", 572.4, 538.7, 6.20, 0.0, -1.01),
        )
        val broker = SpreadLegBrokerPnl(longLegYieldRub = -31.91, shortLegYieldRub = 40.0)
        val out = enrichOpenSandboxExecutions(
            executions = listOf(exec),
            points = points,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            pnlLeverage = 1.0,
            brokerLegPnl = broker,
        ).single()
        assertEquals(-31.91, out.legLongPnlSplitRubApprox, 0.02)
        assertEquals(40.0, out.legShortPnlSplitRubApprox, 0.01)
        assertEquals(4.09, out.netPnlRubApprox, 0.5)
    }

    @Test
    fun monitorOpenTradePnl_usesBrokerLegYieldsLikeTInvest() {
        val broker = SpreadLegBrokerPnl(
            longLegYieldRub = -421.9,
            shortLegYieldRub = 617.4,
        )
        val exec = SandboxSpreadExecUi(
            tradeId = "P-004",
            signalType = StrategySignalType.EnterLong,
            zScore = -1.21,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 6.20,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-06-18 07:00",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка 67 лот",
            shortLegSideRu = "продажа 67 лот",
            volumeText = "67+67 лот",
            confirmLabel = "авто",
            correlationTag = "tag",
            notificationIdsText = "—",
            legs = emptyList(),
            quantityLots = 67,
            executionNotionalRub = 35_617.0,
        )
        val points = listOf(
            DataPoint(1_000L, "2026-06-18 07:00", 537.9, 506.5, 6.20, 0.0, -1.21),
            DataPoint(2_000L, "2026-06-19 21:30", 531.6, 497.3, 6.98, 0.0, 0.75),
        )
        val moexSim = enrichOpenSandboxExecutions(
            executions = listOf(exec),
            points = points,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            pnlLeverage = 1.0,
        ).single()
        val brokerOut = enrichOpenSandboxExecutions(
            executions = listOf(exec),
            points = points,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            pnlLeverage = 1.0,
            brokerLegPnl = broker,
        ).single()
        assertEquals(-421.9, brokerOut.legLongPnlSplitRubApprox, 0.1)
        assertEquals(617.4, brokerOut.legShortPnlSplitRubApprox, 0.1)
        assertEquals(195.5, broker.netGrossRub, 0.1)
        assertTrue(brokerOut.netPnlRubApprox < moexSim.netPnlRubApprox)
    }
}
