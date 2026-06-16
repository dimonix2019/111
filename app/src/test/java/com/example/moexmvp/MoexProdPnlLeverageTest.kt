package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Test

class MoexProdPnlLeverageTest {
    @Test
    fun prodPnl_usesRealNotionalWithoutLeverage() {
        val exec = SandboxSpreadExecUi(
            tradeId = "P-001",
            signalType = StrategySignalType.EnterLong,
            zScore = -0.86,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 6.30,
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
            DataPoint(1_000L, "2026-06-16 07:45", 576.0, 543.0, 6.30, 0.0, -0.86),
            DataPoint(2_000L, "2026-06-16 08:15", 575.9, 542.8, 6.20, 0.0, -1.10),
        )
        val sandbox = enrichOpenSandboxExecutions(
            executions = listOf(exec),
            points = points,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            pnlLeverage = 7.0,
        ).single()
        val prod = enrichOpenSandboxExecutions(
            executions = listOf(exec),
            points = points,
            notionalRub = DEFAULT_PORTFOLIO_NOTIONAL_RUB,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            pnlLeverage = portfolioPnlLeverageMultiplier(TinkoffExecutionMode.Prod, 7.0),
        ).single()
        assertEquals(1.0, portfolioPnlLeverageMultiplier(TinkoffExecutionMode.Prod, 7.0), 0.001)
        assertEquals(7.0, kotlin.math.abs(sandbox.netPnlRubApprox / prod.netPnlRubApprox), 0.5)
    }

    @Test
    fun resolveTradeNotional_fromQuantityLotsWhenMissingExecutionNotional() {
        val exec = SandboxSpreadExecUi(
            tradeId = "P-002",
            signalType = StrategySignalType.EnterLong,
            zScore = -1.0,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 6.0,
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
        )
        val points = listOf(
            DataPoint(1_000L, "2026-06-16 07:45", 576.0, 543.0, 6.0, 0.0, -1.0),
        )
        assertEquals(11_190.0, resolveTradeNotionalRubForPnl(exec, points), 0.01)
    }
}
