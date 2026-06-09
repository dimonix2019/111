package com.example.moexmvp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SandboxTradeOpenedNotifyTest {

    private val zone = ZoneId.of("Europe/Moscow")

    @Test
    fun buildSandboxTradeOpenedNotificationBody_includesSignalNumberAndTradeFields() {
        val monday = LocalDate.of(2026, 6, 1)
        val barTs = monday.atTime(10, 15).atZone(zone).toInstant().toEpochMilli()
        val received = barTs + 5_000L
        val journal = listOf(
            StrategySignalEvent(
                timestampMillis = barTs,
                signalType = StrategySignalType.EnterLong,
                zScore = -1.2,
                receivedAtMillis = received,
            )
        )
        val execution = SandboxSpreadExecUi(
            tradeId = "D-001",
            signalType = StrategySignalType.EnterLong,
            zScore = -1.2,
            barTimestampMillis = barTs,
            executedAtMillis = received + 1_000L,
            entrySpreadPercent = 3.15,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = formatPortfolioExecutionTableMsk(received + 1_000L),
            entrySignalId = "1 long 2026-06-01 10:15",
            entrySignalBarTimeMsk = formatPortfolioExecutionTableMsk(barTs),
            entrySignalReceivedMsk = formatMessageReceivedAtMsk(received),
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "покупка 1 лот",
            shortLegSideRu = "продажа 1 лот",
            volumeText = "1+1 лот",
            confirmLabel = "авто",
            correlationTag = "t",
            notificationIdsText = "—",
            legs = listOf(
                SandboxSpreadOrderLegUi("TATN", "покупка 1 лот", "исполнено"),
                SandboxSpreadOrderLegUi("TATNP", "продажа 1 лот", "исполнено"),
            ),
        )
        val body = buildSandboxTradeOpenedNotificationBody(
            execution = execution,
            journalEvents = journal,
            notionalRub = 100_000.0,
            leverage = 7.0,
            portfolioTotalRub = "1 050 000 ₽",
            portfolioCashRub = "50 000 ₽",
            openedAtMillis = received + 2_000L,
        )
        assertTrue(body.contains("Сигнал: 1 long 26-06-01 10:15"))
        assertTrue(body.contains("Сделка: 1 long"))
        assertTrue(body.contains("Z вход: -1.20"))
        assertTrue(body.contains("спрэд вход: 3.15%"))
        assertTrue(body.contains("Нога 1: TATN"))
        assertTrue(body.contains("Нога 2: TATNP"))
        assertTrue(body.contains("Баланс портфеля: 1 050 000 ₽"))
    }
}
