package com.example.moexmvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

class MoexMarketsSpreadDeltaTest {

    @Test
    fun spreadDeltaFromDayOpenSeries_zerosAt0730Bar() {
        val points = listOf(
            point("2026-05-19 07:30", spread = 4.78),
            point("2026-05-19 10:00", spread = 5.00),
            point("2026-05-19 12:00", spread = 5.10),
        )
        val series = requireNotNull(spreadDeltaFromDayOpenSeries(points))
        assertEquals(3, series.deltasPp.size)
        assertEquals(0.0, series.deltasPp[0], 1e-9)
        assertEquals(0.22, series.deltasPp[1], 1e-9)
        assertEquals(0.32, series.deltasPp[2], 1e-9)
        assertEquals(4.78, series.dayOpenSpreadPercent!!, 1e-9)
    }

    @Test
    fun spreadDeltaFromDayOpenSeries_resetsEachTradingDay() {
        val points = listOf(
            point("2026-05-18 07:30", spread = 4.50),
            point("2026-05-18 18:00", spread = 5.00),
            point("2026-05-19 07:30", spread = 4.80),
            point("2026-05-19 12:00", spread = 5.20),
        )
        val series = requireNotNull(spreadDeltaFromDayOpenSeries(points))
        assertEquals(4, series.deltasPp.size)
        assertEquals(0.0, series.deltasPp[0], 1e-9)
        assertEquals(0.50, series.deltasPp[1], 1e-9)
        assertEquals(0.0, series.deltasPp[2], 1e-9)
        assertEquals(0.40, series.deltasPp[3], 1e-9)
        assertEquals(4.80, series.dayOpenSpreadPercent!!, 1e-9)
    }

    @Test
    fun buildIntraday1mSpreadDeltaSeries_usesM15DayOpen() {
        val zone = ZoneId.of("Europe/Moscow")
        val today = LocalDate.now(zone)
        val openLabel = LocalDateTime.of(today.year, today.month, today.dayOfMonth, 7, 30)
            .format(portfolio15mLabelFormatter)
        val m15 = listOf(
            point(openLabel, spread = 4.78),
            point(
                LocalDateTime.of(today.year, today.month, today.dayOfMonth, 10, 0)
                    .format(portfolio15mLabelFormatter),
                spread = 5.00,
            ),
        )
        val aligned = AlignedIntraday1mQuotes(
            labels = listOf("10:00", "10:01"),
            tatnCloses = listOf(650.0, 651.0),
            tatnpCloses = listOf(600.0, 600.5),
        )
        val series = buildIntraday1mSpreadDeltaSeries(m15, aligned, zone = zone)
        requireNotNull(series)
        assertEquals(2, series.deltasPp.size)
        assertEquals(4.78, series.dayOpenSpreadPercent, 1e-9)
        val spread10 = spreadPercentFromPairCloses(650.0, 600.0)!!
        assertEquals(spread10 - 4.78, series.deltasPp[0], 1e-9)
        val spread11 = spreadPercentFromPairCloses(651.0, 600.5)!!
        assertEquals(spread11 - 4.78, series.deltasPp[1], 1e-9)
    }

    @Test
    fun spreadPnlRubPerSpreadPoint_scalesLinearly() {
        assertEquals(894.4, spreadPnlRubPerSpreadPoint(89_440.0), 0.01)
        assertEquals(894.4, spreadPnlToRubApprox(1.0, 89_440.0), 0.01)
    }

    @Test
    fun resolveSpreadDeltaChartRubAxis_fixedScaleDoesNotDependOnMoexDelta() {
        val exec = SandboxSpreadExecUi(
            tradeId = "P-2",
            signalType = StrategySignalType.EnterLong,
            zScore = -2.0,
            barTimestampMillis = 1_000L,
            executedAtMillis = 2_000L,
            entrySpreadPercent = 8.0,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-05-17 10:00",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "L",
            shortLegSideRu = "S",
            volumeText = "1+1",
            confirmLabel = "авто",
            correlationTag = "t",
            notificationIdsText = "—",
            legs = emptyList(),
            executionNotionalRub = 89_440.0,
            legLongPnlSplitRubApprox = 280.0,
            legShortPnlSplitRubApprox = 30.0,
            netPnlRubApprox = 253.0,
        )
        val wide = resolveSpreadDeltaChartRubAxis(
            openExec = exec,
            currentDeltaPp = 0.69,
            sourcePoints = emptyList(),
            executionMode = TinkoffExecutionMode.Prod,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            tradeAmountRub = 10_000.0,
        )
        val flat = resolveSpreadDeltaChartRubAxis(
            openExec = exec,
            currentDeltaPp = 0.05,
            sourcePoints = emptyList(),
            executionMode = TinkoffExecutionMode.Prod,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            tradeAmountRub = 10_000.0,
        )
        assertEquals(SpreadDeltaChartPnlAxisMode.NetBrokerCalibrated, wide.mode)
        assertEquals(894.4, wide.rubPerSpreadPoint, 0.01)
        assertEquals(wide.rubPerSpreadPoint, flat.rubPerSpreadPoint, 1e-9)
        assertEquals(wide.netOffsetRub, flat.netOffsetRub, 1e-9)
    }

    @Test
    fun patchSpreadDeltaTailFromBroker_movesLineToBrokerImpliedDelta() {
        val moexTail = 0.69
        val brokerGross = 310.0
        val effNotional = 89_440.0
        val patched = patchSpreadDeltaTailFromBroker(
            listOf(0.1, 0.2, moexTail),
            brokerGross,
            effNotional,
        )
        val brokerTail = brokerImpliedSpreadDeltaPp(brokerGross, effNotional)!!
        assertEquals(brokerTail, patched.last(), 1e-6)
        assertTrue(abs(brokerTail - moexTail) > 0.05)
    }

    @Test
    fun applyLiveSpreadToM15ChartPoints_patchesLastBarOnly() {
        val points = listOf(
            point("2026-05-19 10:00", spread = 5.00),
            point("2026-05-19 10:15", spread = 5.10),
        )
        val patched = applyLiveSpreadToM15ChartPoints(points, 5.25)
        assertEquals(5.00, patched[0].spreadPercent, 1e-9)
        assertEquals(5.25, patched[1].spreadPercent, 1e-9)
    }

    @Test
    fun resolveSpreadDeltaCalibrationPp_prefersMoexDeltaForShutterParity() {
        val brokerDelta = brokerImpliedSpreadDeltaPp(130.0, 89_440.0)!!
        val calib = resolveSpreadDeltaCalibrationPp(0.5, 130.0, 89_440.0)
        assertEquals(0.5, calib!!, 1e-6)
        assertTrue(abs(brokerDelta - 0.5) > 0.05)
    }

    @Test
    fun resolveSpreadDeltaCalibrationPp_fallsBackToBrokerWhenMoexFlat() {
        val brokerDelta = brokerImpliedSpreadDeltaPp(253.0, 89_440.0)!!
        val calib = resolveSpreadDeltaCalibrationPp(0.0, 253.0, 89_440.0)
        assertEquals(brokerDelta, calib!!, 1e-6)
    }

    @Test
    fun buildSpreadDelta15mChartContext_tailNetNearBrokerShutter() {
        val points = listOf(
            point("2026-05-17 10:00", spread = 8.0),
            point("2026-05-18 10:00", spread = 8.69),
        )
        val gross = 310.0
        val net = 253.0
        val exec = SandboxSpreadExecUi(
            tradeId = "D-broker",
            signalType = StrategySignalType.EnterLong,
            zScore = -2.0,
            barTimestampMillis = points[0].timestampMillis,
            executedAtMillis = points[0].timestampMillis + 1,
            entrySpreadPercent = 8.0,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-05-17 10:00",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "L",
            shortLegSideRu = "S",
            volumeText = "1+1",
            confirmLabel = "авто",
            correlationTag = "t",
            notificationIdsText = "—",
            legs = emptyList(),
            quantityLots = 1,
            executionNotionalRub = 89_440.0,
            legLongPnlSplitRubApprox = 280.0,
            legShortPnlSplitRubApprox = 30.0,
            netPnlRubApprox = net,
        )
        val ctx = requireNotNull(
            buildSpreadDelta15mChartContext(
                chartPoints = points,
                sourcePoints = points,
                openExec = exec,
                executionMode = TinkoffExecutionMode.Prod,
                leverage = 7.0,
                commissionPercentPerSide = 0.04,
                tradeAmountRub = 10_000.0,
            ),
        )
        assertTrue(ctx.pnlAxisBrokerCalibrated)
        val brokerTail = brokerImpliedSpreadDeltaPp(gross, 89_440.0)!!
        assertEquals(brokerTail, ctx.deltasPp.last(), 1e-4)
        val tailNet = spreadDeltaNetRubAtPp(
            ctx.deltasPp.last(),
            SpreadDeltaChartRubAxis(ctx.rubPerSpreadPoint, ctx.netOffsetRub, SpreadDeltaChartPnlAxisMode.NetBrokerCalibrated),
        )
        assertEquals(net, tailNet, 2.0)
        assertEquals(net, signalMonitorOpenTradeSnapshot(exec)!!.pnlRub, 0.01)
    }

    @Test
    fun buildSpreadDelta15mChartContext_fromEntry_spansMultipleDaysWithoutDailyReset() {
        val points = listOf(
            point("2026-05-17 10:00", spread = 8.0),
            point("2026-05-18 10:00", spread = 8.2),
            point("2026-05-19 10:00", spread = 8.5),
        )
        val exec = SandboxSpreadExecUi(
            tradeId = "D-1",
            signalType = StrategySignalType.EnterLong,
            zScore = -2.0,
            barTimestampMillis = points[0].timestampMillis,
            executedAtMillis = points[0].timestampMillis + 1,
            entrySpreadPercent = 8.0,
            source = PortfolioExecSource.AUTO,
            directionLabel = "long",
            entryTimeMsk = "2026-05-17 10:00",
            longLegTicker = "TATN",
            shortLegTicker = "TATNP",
            longLegSideRu = "L",
            shortLegSideRu = "S",
            volumeText = "1+1",
            confirmLabel = "авто",
            correlationTag = "t",
            notificationIdsText = "—",
            legs = emptyList(),
            quantityLots = 1,
            executionNotionalRub = 89_440.0,
            legLongPnlSplitRubApprox = 300.0,
            legShortPnlSplitRubApprox = 147.2,
            netPnlRubApprox = 447.2,
        )
        val ctx = requireNotNull(
            buildSpreadDelta15mChartContext(
                chartPoints = points,
                sourcePoints = points,
                openExec = exec,
                executionMode = TinkoffExecutionMode.Prod,
                leverage = 7.0,
                commissionPercentPerSide = 0.04,
                tradeAmountRub = 10_000.0,
            ),
        )
        assertTrue(ctx.fromEntry)
        assertTrue(ctx.pnlAxisBrokerCalibrated)
        assertEquals(0.0, ctx.deltasPp[0], 1e-9)
        assertEquals(0.2, ctx.deltasPp[1], 1e-9)
        val tailNet = spreadDeltaNetRubAtPp(
            ctx.deltasPp[2],
            SpreadDeltaChartRubAxis(ctx.rubPerSpreadPoint, ctx.netOffsetRub, SpreadDeltaChartPnlAxisMode.NetBrokerCalibrated),
        )
        assertEquals(447.2, tailNet, 2.0)
    }

    @Test
    fun buildSpreadDelta15mChartContext_withoutOpenTrade_usesDayOpenDelta() {
        val points = listOf(
            point("2026-05-19 07:30", spread = 4.78),
            point("2026-05-19 10:00", spread = 5.00),
        )
        val ctx = requireNotNull(
            buildSpreadDelta15mChartContext(
                chartPoints = points,
                sourcePoints = points,
                openExec = null,
                executionMode = TinkoffExecutionMode.Prod,
                leverage = 7.0,
                commissionPercentPerSide = 0.04,
                tradeAmountRub = 10_000.0,
            ),
        )
        assertFalse(ctx.fromEntry)
        assertEquals(0.0, ctx.deltasPp[0], 1e-9)
        assertEquals(0.22, ctx.deltasPp[1], 1e-9)
        assertEquals(100.0, ctx.rubPerSpreadPoint, 1e-9)
    }

    @Test
    fun formatSpreadDeltaPp_includesSign() {
        assertEquals("+0.22", formatSpreadDeltaPp(0.22))
        assertEquals("-0.15", formatSpreadDeltaPp(-0.15))
        assertEquals("+0.00", formatSpreadDeltaPp(0.0))
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
