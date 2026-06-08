package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SandboxTradeClosedNotifyTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun computeSandboxClosedTradePnl_longPosition() {
        val pnl = computeSandboxClosedTradePnl(
            execution = SandboxSpreadExecUi(
                tradeId = "D-001",
                signalType = StrategySignalType.EnterLong,
                zScore = -1.2,
                barTimestampMillis = 0L,
                executedAtMillis = 0L,
                entrySpreadPercent = 3.0,
                source = PortfolioExecSource.AUTO,
                directionLabel = "long",
                entryTimeMsk = "2026-06-01 10:00",
                longLegTicker = "TATN",
                shortLegTicker = "TATNP",
                longLegSideRu = "покупка",
                shortLegSideRu = "продажа",
                volumeText = "1+1",
                confirmLabel = "авто",
                correlationTag = "t",
                notificationIdsText = "—",
                legs = emptyList(),
            ),
            exitSpreadPercent = 3.5,
            entryDateLabel = "2026-06-01",
            exitDateLabel = "2026-06-01",
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
        )
        assertEquals(3_500.0, pnl.grossRub, 1.0)
        assertTrue(pnl.netRub < pnl.grossRub)
    }

    @Test
    fun buildSandboxTradeClosedNotificationBody_includesExitSignalAndAccountPnl() {
        val monday = LocalDate.of(2026, 6, 1)
        val entryTs = monday.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val exitTs = monday.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        val journal = listOf(
            StrategySignalEvent(entryTs, StrategySignalType.EnterLong, -1.2, entryTs + 1_000L),
            StrategySignalEvent(exitTs, StrategySignalType.ExitLong, -0.5, exitTs + 1_000L),
        )
        val execution = SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = StrategySignalType.EnterLong,
            zScore = -1.2,
            barTimestampMillis = entryTs,
            executedAtMillis = entryTs,
            entrySpreadPercent = 3.0,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = formatPortfolioExecutionTableMsk(entryTs),
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка",
            shortLegSideRu = "продажа",
            volumeText = "1+1",
            confirmLabel = "авто",
            correlationTag = "t",
            notificationIdsText = "—",
            legs = emptyList(),
        )
        val tradePnl = SandboxClosedTradePnl(
            grossRub = 3500.0,
            commissionRub = 560.0,
            overnightRub = 0.0,
            netRub = 2940.0,
            exitSpreadPercent = 2.5,
        )
        val body = buildSandboxTradeClosedNotificationBody(
            execution = execution,
            exitSignalType = StrategySignalType.ExitLong,
            exitBarTimestampMillis = exitTs,
            exitZScore = -0.5,
            tradePnl = tradePnl,
            accountTotalPnlRub = 2940.0,
            journalEvents = journal,
            notionalRub = 100_000.0,
            leverage = 7.0,
            portfolioTotalRub = "1 002 940.00 ₽",
            closedAtMillis = exitTs + 2_000L,
        )
        assertTrue(body.contains("Сигнал выхода: 2 long 26-06-01 14:00"))
        assertTrue(body.contains("Сделка: 1 long"))
        assertTrue(body.contains("PnL сделки: +2940"))
        assertTrue(body.contains("Общий PnL счёта: +2940"))
        assertTrue(body.contains("Баланс счёта: 1 002 940.00 ₽"))
    }
}
