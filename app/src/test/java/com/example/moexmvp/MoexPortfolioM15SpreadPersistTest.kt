package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MoexPortfolioM15SpreadPersistTest {

    @Test
    fun fillM15SpreadFromLegClosesInPlace_backfillsZeroSpread() {
        val label = "2026-05-19 10:00"
        val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
            .atZone(ZoneId.of("Europe/Moscow"))
            .toInstant()
            .toEpochMilli()
        val entity = PortfolioM15SpreadEntity(
            tsMillis = ts,
            tatnClose = 650.0,
            tatnpClose = 600.0,
            spreadPercent = 0.0,
            diff = 0.0,
        )
        val points = mutableListOf(entity.toDataPoint())
        assertTrue(fillM15SpreadFromLegClosesInPlace(points, listOf(entity)))
        val expected = spreadPercentFromPairCloses(650.0, 600.0)!!
        assertEquals(expected, points[0].spreadPercent, 1e-9)
        assertEquals(50.0, points[0].diff, 1e-9)
    }

    @Test
    fun fillM15SpreadFromLegClosesInPlace_skipsValidSpread() {
        val label = "2026-05-19 10:00"
        val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
            .atZone(ZoneId.of("Europe/Moscow"))
            .toInstant()
            .toEpochMilli()
        val entity = PortfolioM15SpreadEntity(
            tsMillis = ts,
            tatnClose = 650.0,
            tatnpClose = 600.0,
            spreadPercent = 8.33,
            diff = 50.0,
        )
        val points = mutableListOf(entity.toDataPoint())
        assertFalse(fillM15SpreadFromLegClosesInPlace(points, listOf(entity)))
    }

    @Test
    fun spreadDeltaFromDayOpenSeriesForDisplayWindow_usesRollingBaseDayOpen() {
        val base = listOf(
            point("2026-05-18 07:30", spread = 4.50),
            point("2026-05-18 18:00", spread = 5.00),
            point("2026-05-19 07:30", spread = 4.80),
            point("2026-05-19 12:00", spread = 5.20),
        )
        val window = base.filter { it.tradeDate.startsWith("2026-05-19") }
        val series = requireNotNull(spreadDeltaFromDayOpenSeriesForDisplayWindow(window, base))
        assertEquals(2, series.deltasPp.size)
        assertEquals(0.0, series.deltasPp[0], 1e-9)
        assertEquals(0.40, series.deltasPp[1], 1e-9)
    }

    @Test
    fun spreadDeltaSnapshotsForSeries_matchesDayOpenSeries() {
        val points = listOf(
            point("2026-05-19 07:30", spread = 4.78),
            point("2026-05-19 10:00", spread = 5.00),
        )
        val snaps = requireNotNull(spreadDeltaSnapshotsForSeries(points))
        val series = requireNotNull(spreadDeltaFromDayOpenSeries(points))
        assertEquals(series.deltasPp.size, snaps.size)
        snaps.zip(series.deltasPp).forEach { (snap, delta) ->
            assertEquals(delta, snap.deltaPp, 1e-9)
        }
    }

    @Test
    fun buildSpreadDelta15mChartContext_usesRollingBaseForPastPeriod() {
        val base = listOf(
            point("2026-05-18 07:30", spread = 4.50),
            point("2026-05-19 07:30", spread = 4.80),
            point("2026-05-19 12:00", spread = 5.20),
        )
        val window = base.filter { it.tradeDate.startsWith("2026-05-19") }
        val ctx = requireNotNull(
            buildSpreadDelta15mChartContext(
                chartPoints = window,
                sourcePoints = base,
                openExec = null,
                executionMode = TinkoffExecutionMode.Sandbox,
                leverage = 1.0,
                commissionPercentPerSide = 0.0,
                tradeAmountRub = 10_000.0,
                rollingBase = base,
            ),
        )
        assertEquals(0.0, ctx.deltasPp[0], 1e-9)
        assertEquals(0.40, ctx.deltasPp[1], 1e-9)
    }

    private fun point(label: String, spread: Double): DataPoint {
        val ts = LocalDateTime.parse(label, portfolio15mLabelFormatter)
        return DataPoint(
            timestampMillis = ts.atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli(),
            tradeDate = label,
            tatnClose = 100.0,
            tatnpClose = 90.0,
            spreadPercent = spread,
            diff = 10.0,
            zScore = 0.0,
        )
    }
}
