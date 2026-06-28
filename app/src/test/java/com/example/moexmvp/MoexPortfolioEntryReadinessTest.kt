package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MoexPortfolioEntryReadinessTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val thresholds = DynamicThresholds(entry = 1.8, exit = 1.3, calculatedDate = null)

    private fun bar(date: LocalDate, hour: Int, minute: Int, z: Double): DataPoint {
        val ts = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        return DataPoint(
            timestampMillis = ts,
            tradeDate = formatPortfolioExecutionTableMsk(ts),
            tatnClose = 500.0,
            tatnpClose = 480.0,
            spreadPercent = 4.17,
            diff = 20.0,
            zScore = z,
        )
    }

    @Test
    fun describeNoCrossingReason_flat_alreadyBelowLongThreshold() {
        val reason = describeNoCrossingReason(
            position = ZStrategyPosition.Flat,
            prevZ = -1.90,
            curZ = -1.85,
            thresholds = thresholds,
        )
        assertTrue(reason.contains("пересечения не было") || reason.contains("ниже порога LONG"))
    }

    @Test
    fun describeNoCrossingReason_flat_neutralZone() {
        val reason = describeNoCrossingReason(
            position = ZStrategyPosition.Flat,
            prevZ = -0.5,
            curZ = -0.71,
            thresholds = thresholds,
        )
        assertTrue(reason.contains("нейтральной"))
    }

    @Test
    fun buildReport_blocksWhenBarsNotConsecutive() {
        val d = LocalDate.of(2026, 6, 27)
        val fri = bar(d.minusDays(2), 23, 30, -1.0)
        val mon = bar(d, 10, 0, -1.9)
        val report = buildPortfolioEntryReadiness(
            PortfolioEntryReadinessInput(
                points = listOf(fri, mon),
                position = ZStrategyPosition.Flat,
                thresholds = thresholds,
                monitorEnabled = true,
                networkAvailable = true,
                autoExecute = true,
                execUiState = SandboxExecUiState.Ready,
                dailySignalLimit = DailySignalLimit(date = d.toString(), sentCount = 0),
                hasPendingVirtualTrade = false,
                nowMillis = mon.timestampMillis + 60_000L,
            )
        )
        assertNotNull(report.primaryBlocker)
        assertTrue(report.items.any { it.label.contains("Соседние") && it.status == PortfolioEntryReadinessStatus.Blocked })
    }

    @Test
    fun buildReport_okWhenEnterLongCrossing() {
        val d = LocalDate.of(2026, 6, 27)
        val prev = bar(d, 10, 0, -1.5)
        val cur = bar(d, 10, 15, -1.85)
        val report = buildPortfolioEntryReadiness(
            PortfolioEntryReadinessInput(
                points = listOf(prev, cur),
                position = ZStrategyPosition.Flat,
                thresholds = thresholds,
                monitorEnabled = true,
                networkAvailable = true,
                autoExecute = false,
                execUiState = SandboxExecUiState.Ready,
                dailySignalLimit = DailySignalLimit(date = d.toString(), sentCount = 0),
                hasPendingVirtualTrade = false,
                nowMillis = cur.timestampMillis + 60_000L,
            )
        )
        assertTrue(report.items.any { it.label.contains("Пересечение на последней паре") && it.status == PortfolioEntryReadinessStatus.Ok })
        assertTrue(report.items.none { it.label.contains("Пересечение Z") && it.status == PortfolioEntryReadinessStatus.Blocked })
    }

    @Test
    fun buildReport_blocksWithoutEnoughBars() {
        val d = LocalDate.of(2026, 6, 27)
        val only = bar(d, 10, 15, -1.0)
        val report = buildPortfolioEntryReadiness(
            PortfolioEntryReadinessInput(
                points = List(Z_SCORE_ROLLING_MIN_BARS - 1) { only },
                position = ZStrategyPosition.Flat,
                thresholds = thresholds,
                monitorEnabled = true,
                networkAvailable = true,
                autoExecute = true,
                execUiState = SandboxExecUiState.Ready,
                dailySignalLimit = DailySignalLimit(date = d.toString(), sentCount = 0),
                hasPendingVirtualTrade = false,
            )
        )
        assertTrue(report.items.any { it.label.startsWith("Баров для Z") && it.status == PortfolioEntryReadinessStatus.Blocked })
    }
}
