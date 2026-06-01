package com.example.moexmvp

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессии по сценариям, которые ломались в UI (1W/1M/3M, кэш, viewport, порядок баров).
 */
class MoexAppRegressionTest {

    @Test
    fun allChartPeriods_filterBuildChartAndViewportStayValid() {
        val full = syntheticM15Points(calendarDays = 100)
        Period.entries.forEach { period ->
            val filtered = filterM15PointsForMarketsPeriod(full, period)
            if (filtered.size < 2) return@forEach
            assertTimestampsAscending(filtered, "period=$period filter")
            val (pts, candles) = buildM15ZChartDisplay(filtered)
            assertTrue("period=$period chart empty", pts.isNotEmpty())
            assertEquals("period=$period size", pts.size, candles.size)
            assertTimestampsAscending(pts, "period=$period chart points")
            val visibleDays = calendarDaysForMarketsZChartPeriod(period)
            val (width, start) = chartInitialWindowForLastCalendarDays(pts, visibleDays)
            assertValidChartWindow(width, start, "period=$period")
            val viewport = ChartViewportState(
                initialWindowWidth = width,
                initialWindowStart = start,
                dataYMin = -3.0,
                dataYMax = 3.0,
            )
            viewport.resetWindow(width, start)
            viewport.zoomX(1.22f)
            viewport.panX(-0.05f)
        }
    }

    @Test
    fun buildM15ZChartSeriesForUi_largeFilteredMonthCompletesWithoutNetwork() = runBlocking {
        val full = syntheticM15Points(calendarDays = 45)
        val month = filterM15PointsForMarketsPeriod(full, Period.OneMonth)
        val (pts, candles) = buildM15ZChartSeriesForUi(month)
        assertTrue(pts.isNotEmpty())
        assertEquals(pts.size, candles.size)
        assertTrue(pts.size <= CHART_MAX_DISPLAY_BARS + 1)
    }

    @Test
    fun oneWeekAndOneMonth_filteredSizesAreOrdered() {
        val full = syntheticM15Points(calendarDays = 60)
        val d1 = filterM15PointsForMarketsPeriod(full, Period.OneDay)
        val w1 = filterM15PointsForMarketsPeriod(full, Period.OneWeek)
        val m1 = filterM15PointsForMarketsPeriod(full, Period.OneMonth)
        assertTrue(d1.size <= w1.size)
        assertTrue(w1.size <= m1.size)
        assertTrue(m1.size <= full.size)
    }

    @Test
    fun descendingInput_restoredBeforeChartBuild() {
        val ordered = syntheticM15Points(calendarDays = 5)
        val reversed = ordered.asReversed()
        val (pts, candles) = buildM15ZChartDisplay(reversed)
        assertEquals(ordered.first().tradeDate, pts.first().tradeDate)
        assertEquals(ordered.last().tradeDate, pts.last().tradeDate)
        assertEquals(pts.size, candles.size)
    }

    @Test
    fun chartViewportState_resetWindow_neverExceedsBoundsForTightWindows() {
        listOf(0.06f, 0.15f, 0.5f, 1f).forEach { width ->
            val startUpper = (1f - width).coerceAtLeast(0f)
            val start = startUpper * 0.7f
            val state = ChartViewportState(width, start, -2.0, 2.0)
            state.resetWindow(width, start)
            assertTrue(state.windowStart >= 0f)
            assertTrue(state.windowStart + state.windowWidth <= 1.0001f)
        }
    }

    @Test
    fun marketsSnapshot_decodeDisplayTtl_allowsWeekOldCache() {
        val json = encodeMarketsSnapshotJson(sampleSnapshot(), savedAtMillis = 1_000_000L)
        val decoded = decodeMarketsSnapshotIfFresh(
            raw = json,
            nowMillis = 1_000_000L + MARKETS_SNAPSHOT_DISPLAY_MAX_AGE_MS - 60_000L,
            ttlMs = MARKETS_SNAPSHOT_DISPLAY_MAX_AGE_MS,
        )
        assertNotNull(decoded)
    }

    @Test
    fun periodFrom_coversOneWeekAndOneMonth() {
        val till = LocalDate.of(2026, 5, 20)
        val fromWeek = Period.OneWeek.from(till)
        val fromMonth = Period.OneMonth.from(till)
        assertTrue(fromWeek.isBefore(till))
        assertTrue(fromMonth.isBefore(fromWeek) || fromMonth == fromWeek)
    }

    @Test
    fun buildZStrategyTapMetrics_oneMonthPoints_doesNotThrow() {
        val full = syntheticM15Points(calendarDays = 35)
        val month = filterM15PointsForMarketsPeriod(full, Period.OneMonth)
        val metrics = buildZStrategyPortfolioMetrics(
            points = month,
            thresholds = DynamicThresholds(entry = 0.8, exit = 0.7, calculatedDate = null),
            notionalRub = 100_000.0,
            leverage = 7.0,
            commissionPercentPerSide = 0.04,
            periodDescription = "1M regression",
            compoundReturns = false,
        )
        assertNotNull(metrics)
    }

    private fun assertValidChartWindow(width: Float, start: Float, label: String) {
        assertTrue("$label width=$width", width in CHART_ZOOM_MIN_WINDOW..1f)
        assertTrue("$label start=$start", start >= 0f)
        assertTrue("$label start+width", start + width <= 1.02f)
    }

    private fun assertTimestampsAscending(points: List<DataPoint>, label: String) {
        for (i in 1 until points.size) {
            assertTrue(
                "$label idx=$i",
                points[i].timestampMillis >= points[i - 1].timestampMillis,
            )
        }
    }

    private fun syntheticM15Points(calendarDays: Int): List<DataPoint> {
        val zone = ZoneId.of("Europe/Moscow")
        val start = LocalDate.of(2026, 2, 1)
        val barsPerDay = 32
        return buildList {
            for (day in 0 until calendarDays) {
                val date = start.plusDays(day.toLong())
                for (bar in 0 until barsPerDay) {
                    val dt = date.atTime(10, 0).plusMinutes(bar * 15L)
                    val ts = dt.atZone(zone).toInstant().toEpochMilli()
                    val spread = 10.0 + day * 0.01 + bar * 0.001
                    add(
                        DataPoint(
                            timestampMillis = ts,
                            tradeDate = dt.format(portfolio15mLabelFormatter),
                            tatnClose = 100.0,
                            tatnpClose = 90.0,
                            spreadPercent = spread,
                            diff = 10.0,
                            zScore = (spread - 10.0) * 0.5,
                        )
                    )
                }
            }
        }
    }

    private fun sampleSnapshot(): UiState.Success {
        val p = DataPoint(
            timestampMillis = 1_700_000_000_000L,
            tradeDate = "2026-05-13",
            tatnClose = 600.0,
            tatnpClose = 580.0,
            spreadPercent = 3.44,
            diff = 20.0,
            zScore = -1.25,
        )
        return UiState.Success(
            points = listOf(p),
            loadedAt = "2026-05-13 12:00:00",
            tatnCandles = emptyList(),
            tatnpCandles = emptyList(),
            marketsDataSource = MarketsDataSource.Network,
        )
    }
}
